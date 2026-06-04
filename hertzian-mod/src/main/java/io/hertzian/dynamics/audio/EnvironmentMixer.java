package io.hertzian.dynamics.audio;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * Shadow mixer that re-renders the game's own sound effects as the radio
 * picks them up, the way a real handheld held near a firefight carries the
 * gunfire under the operator's voice.
 *
 * <p>
 * Why this is a real capture and not a synthesis. Paulscode has already
 * decoded every sound effect from its .ogg into raw PCM, held in the
 * SoundBuffer as a byte array with an AudioFormat. The mixin tap hands
 * that exact PCM here, along with the source's world position and gain, so
 * what bleeds into the radio is the genuine waveform the game is playing,
 * not an imitation. No decoder is pulled in because the decode already
 * happened inside the engine.
 *
 * <p>
 * Loud or close only. Each tap is attenuated by its distance to the
 * listener using the source gain the game assigned, so a distant quiet
 * sound contributes almost nothing, and the summed mono chunk is then
 * passed through a loudness gate. Only a chunk whose level clears the gate
 * goes on air, which is exactly the "only the loud or close sounds"
 * behaviour: a nearby explosion passes, a footstep two hundred blocks away
 * does not.
 *
 * <p>
 * Wall clock playback. A tap stores the wall clock time it started; the
 * read cursor is derived from elapsed real time rather than a running
 * counter, so the mixer keeps no per call state, taps expire on their own
 * when real time passes their end, and the bleed stays in sync with what
 * the player actually hears to within a few milliseconds.
 *
 * <p>
 * Threading. Paulscode calls the tap from its own command thread, so new
 * taps land in a concurrent queue. {@link #drain} runs on the client thread
 * (the push to talk handler), promotes queued taps into a thread confined
 * active list, and does all the mixing there.
 */
public final class EnvironmentMixer {

    private static final int ENGINE_RATE = 48_000;
    private static final int MAX_TAPS = 48;

    /**
     * Reference distance for the inverse square attenuation, blocks. A
     * handheld microphone is a point sensor: a sound is at full level only
     * when it happens almost on top of the radio, and falls away sharply.
     * Two blocks puts the knee close enough that a gunshot fired at the
     * player's own position is loud while one a few blocks off is already
     * faint.
     */
    private static final double REF_DIST = 2.5;
    /**
     * Hard hearing radius of the microphone, blocks. Past this the mic
     * simply does not pick the sound up. A real handheld hears metres, not
     * the tens of blocks the earlier value implied, so a distant battle is
     * inaudible no matter how loud it is at its own source.
     */
    private static final double CULL_DIST = 18.0;
    /** Tail kept after a tap's nominal end, seconds, to cover decode jitter. */
    private static final double TAIL_SEC = 0.05;

    /**
     * Output level of the environment once it has passed the mic, a low
     * background so it sits under speech rather than competing with it.
     */
    private static final float BLEED_LEVEL = 0.5f;
    /**
     * Self noise floor the microphone adds, peak amplitude. A cheap mic is
     * never silent; this hiss is always present and is what the gate of a
     * real radio would ride on, so the captured environment never sounds
     * like a clean studio feed.
     */
    private static final float MIC_NOISE = 0.012f;
    /**
     * How hard speech ducks the environment. While the operator is talking
     * the mic's automatic gain pulls down, so the background all but
     * vanishes and surfaces again only in the gaps between words, the way a
     * close talking mic behaves. This is the residual environment level
     * during speech.
     */
    private static final float DUCK_FLOOR = 0.12f;

    private static final EnvironmentMixer INSTANCE = new EnvironmentMixer();

    public static EnvironmentMixer get() {
        return INSTANCE;
    }

    private final ConcurrentLinkedQueue<Tap> incoming = new ConcurrentLinkedQueue<>();
    private final List<Tap> active = new ArrayList<>();

    // Bad mic coloring filter state, client thread only.
    private float hpX = 0f, hpY = 0f, lpY = 0f;
    private static final float HP_R = 0.9615f; // one pole high pass near 300 Hz
    private static final float LP_A = 0.325f; // one pole low pass near 3 kHz

    // Self noise generator state.
    private long noiseState = 0x2545F4914F6CDD1DL;

    private EnvironmentMixer() {}

    /**
     * Called from the Paulscode command thread when a source starts. The
     * PCM byte array is shared and immutable once decoded, so it is kept by
     * reference rather than copied. Streaming sources (music, records) and
     * unsupported formats are dropped here so only short positional effects
     * become taps.
     */
    public void onSourcePlay(byte[] data, AudioFormat fmt, float gain, double x, double y, double z, boolean loop,
        boolean streaming) {
        if (streaming || data == null || data.length == 0 || fmt == null) return;
        if (gain <= 0f) return;
        if (fmt.getSampleSizeInBits() != 16) return; // decoded ogg is 16 bit; skip the rest
        int channels = fmt.getChannels();
        if (channels < 1 || channels > 2) return;

        Tap t = new Tap();
        t.data = data;
        t.channels = channels;
        t.bigEndian = fmt.isBigEndian();
        t.srcRate = fmt.getSampleRate();
        t.frames = data.length / (channels * 2);
        t.gain = gain;
        t.loop = loop;
        t.x = x;
        t.y = y;
        t.z = z;
        t.startNanos = System.nanoTime();
        if (t.frames <= 0 || t.srcRate <= 0) return;
        incoming.add(t);
    }

    /**
     * Called when the mod's own audio bridge plays one chunk of radio
     * audio for a voice at a world position. This is the second capture
     * input: a receiver's SSTV crackle, a nearby station, or another
     * player's handheld are all played through the mod's own AL sources,
     * not through Paulscode, so the game sound tap never sees them. Feeding
     * the chunk here lets the microphone hear another radio's speaker the
     * way it hears any other nearby sound, attenuated by its distance to
     * the operator's radio.
     *
     * <p>
     * The chunk is already demodulated mono PCM at the engine rate, so
     * it becomes a short one shot tap that plays out over its own duration;
     * the steady stream of one chunk per tick reassembles into continuous
     * background. The samples are copied because the caller reuses its
     * buffer; the copy is one short array per chunk per audible radio,
     * cheap against the mix itself.
     */
    public void onRadioPcm(short[] pcm, double x, double y, double z) {
        if (pcm == null || pcm.length == 0) return;
        float[] mono = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            mono[i] = pcm[i] / 32768f;
        }
        Tap t = new Tap();
        t.mono = mono;
        t.channels = 1;
        t.srcRate = ENGINE_RATE;
        t.frames = mono.length;
        t.gain = 1.0f;
        t.loop = false;
        t.x = x;
        t.y = y;
        t.z = z;
        t.startNanos = System.nanoTime();
        incoming.add(t);
    }

    /**
     * Produce one mono chunk of {@code n} samples at the engine rate, the
     * environment as the handheld microphone hears it, ready to add under
     * the voice. {@code voiceLevel} is the operator's own speech level this
     * tick, in roughly [0, 1]; the louder they talk, the harder the
     * environment ducks, so the bleed lives mostly in the gaps between
     * words. The mic adds its own hiss and band limits everything, and the
     * short hearing radius means only sounds happening close to the radio
     * survive at all.
     */
    public float[] drain(int n, float voiceLevel) {
        float[] out = new float[n];
        if (n <= 0) return out;

        Tap t;
        while ((t = incoming.poll()) != null) {
            if (active.size() >= MAX_TAPS) active.remove(0);
            active.add(t);
        }

        double[] listener = listenerPos();
        long now = System.nanoTime();

        Iterator<Tap> it = active.iterator();
        while (it.hasNext()) {
            Tap tap = it.next();
            double elapsed = (now - tap.startNanos) / 1.0e9;
            double durSec = tap.frames / tap.srcRate;
            if (!tap.loop && elapsed > durSec + TAIL_SEC) {
                it.remove();
                continue;
            }
            double dx = tap.x - listener[0];
            double dy = tap.y - listener[1];
            double dz = tap.z - listener[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > CULL_DIST) continue;
            float att = (float) (tap.gain / (1.0 + (dist / REF_DIST) * (dist / REF_DIST)));
            if (att < 1.0e-3f) continue;

            double baseFrame = elapsed * tap.srcRate;
            double step = tap.srcRate / (double) ENGINE_RATE;
            for (int i = 0; i < n; i++) {
                out[i] += att * sampleAt(tap, baseFrame + i * step);
            }
        }

        // Speech ducking. The environment is scaled down by how loud the
        // operator is this tick, so talking buries the background and a
        // pause lets it back up. The duck is not allowed all the way to
        // zero, a thread of environment stays under the voice.
        float duck = 1f - (1f - DUCK_FLOOR) * clamp01(voiceLevel * 3f);

        return runColoring(out, duck);
    }

    /**
     * Band limit, add the mic self noise, duck, and level the chunk so it
     * reads as a cheap handheld microphone rather than a clean feed. The
     * hiss is added before the band limit so the filter shapes it too,
     * which is why even a silent environment carries a faint coloured
     * background.
     */
    private float[] runColoring(float[] buf, float duck) {
        for (int i = 0; i < buf.length; i++) {
            float x = buf[i] + nextNoise() * MIC_NOISE;
            float hp = HP_R * (hpY + x - hpX);
            hpX = x;
            hpY = hp;
            lpY = lpY + LP_A * (hp - lpY);
            float y = lpY * BLEED_LEVEL * duck;
            if (y > 1f) y = 1f;
            else if (y < -1f) y = -1f;
            buf[i] = y;
        }
        return buf;
    }

    /** Cheap white noise in [-1, 1] from an xorshift state. */
    private float nextNoise() {
        noiseState ^= noiseState << 13;
        noiseState ^= noiseState >>> 7;
        noiseState ^= noiseState << 17;
        return (noiseState >> 40) / (float) (1L << 23);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Band limit and level the chunk so it reads as bad mic background. */
    private float[] runColoring(float[] buf, boolean open) {
        for (int i = 0; i < buf.length; i++) {
            float x = buf[i];
            float hp = HP_R * (hpY + x - hpX);
            hpX = x;
            hpY = hp;
            lpY = lpY + LP_A * (hp - lpY);
            float y = open ? lpY * BLEED_LEVEL : 0f;
            if (y > 1f) y = 1f;
            else if (y < -1f) y = -1f;
            buf[i] = y;
        }
        return buf;
    }

    private float sampleAt(Tap t, double frame) {
        long i0 = (long) Math.floor(frame);
        float a = frameMono(t, i0);
        float b = frameMono(t, i0 + 1);
        float fr = (float) (frame - i0);
        return a + (b - a) * fr;
    }

    private float frameMono(Tap t, long frame) {
        // Radio path: the tap already holds mono float samples at the
        // engine rate, so it is read directly. The game sound path below
        // decodes interleaved PCM16 from the shared SoundBuffer instead.
        if (t.mono != null) {
            if (t.loop) {
                frame = ((frame % t.frames) + t.frames) % t.frames;
            } else if (frame < 0 || frame >= t.frames) {
                return 0f;
            }
            return t.mono[(int) frame];
        }

        if (t.loop) {
            frame = ((frame % t.frames) + t.frames) % t.frames;
        } else if (frame < 0 || frame >= t.frames) {
            return 0f;
        }
        int base = (int) (frame * t.channels * 2);
        int acc = 0;
        for (int c = 0; c < t.channels; c++) {
            int idx = base + c * 2;
            if (idx + 1 >= t.data.length) break;
            int s;
            if (t.bigEndian) {
                s = (t.data[idx] << 8) | (t.data[idx + 1] & 0xFF);
            } else {
                s = (t.data[idx + 1] << 8) | (t.data[idx] & 0xFF);
            }
            acc += (short) s;
        }
        return (acc / (float) t.channels) / 32768f;
    }

    private static double[] listenerPos() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc == null ? null : mc.renderViewEntity;
        if (view == null) return new double[] { 0, 0, 0 };
        return new double[] { view.posX, view.posY, view.posZ };
    }

    private static final class Tap {

        byte[] data; // game sound path, interleaved PCM16
        float[] mono; // radio path, ready mono floats at engine rate
        int channels;
        boolean bigEndian;
        double srcRate;
        int frames;
        float gain;
        boolean loop;
        double x, y, z;
        long startNanos;
    }
}
