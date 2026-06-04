package io.hertzian.dynamics.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;

/**
 * Client-side voice registry. Voices are keyed by a server-supplied
 * stable string rather than by block position, the bridge garbage
 * collects voices that have not received PCM recently, and a hard
 * cap on simultaneous voices bounds AL source allocation.
 *
 * <p>
 * AL source ceiling
 * --------------------
 * The AL specification places no portable upper bound on the number
 * of simultaneously alive sources, but every major implementation
 * enforces one. The OpenAL Soft default and the Windows DirectSound
 * path Minecraft uses both clamp at roughly 32 sources per context.
 * Past that ceiling {@code alGenSources} returns {@code AL_OUT_OF_MEMORY}
 * and leaves a zero handle in the destination buffer, against which
 * every later AL call reports {@code AL_INVALID_NAME}.
 *
 * <p>
 * Keying voices by world coordinates is exact for a stationary block:
 * one block, one stable key, one voice. It does not hold for a
 * handheld radio, whose packet coordinate triple is the player
 * position the server forwards each tick. Under coordinate keying
 * each block of player movement produces a fresh key, and the voice
 * registered against the previous key is unreachable, so the source
 * pool fills with orphaned voices. The string key separates the two
 * concerns:
 * <ul>
 * <li>The {@code voiceKey} field of {@link io.hertzian.dynamics.net.PacketAudioChunk}
 * is the stable identifier the client uses to look up the
 * voice in {@link #VOICES}. The server picks it once per
 * conceptual radio and reuses it across every chunk for
 * that radio.</li>
 * <li>The {@code (x, y, z)} fields of the packet carry the
 * latest world position the AL source should track. The
 * client updates the existing source's AL_POSITION rather
 * than reconstructing the voice.</li>
 * </ul>
 *
 * <p>
 * Stale voice eviction
 * ------------------------
 * A receiver that stops broadcasting (block broken, player puts
 * the handheld away) no longer sends fresh PCM. Without cleanup,
 * its voice would sit idle forever, holding one AL source. The
 * bridge tracks {@code lastSeenTickMs} per entry and disposes any
 * voice that has not received PCM for {@link #IDLE_TIMEOUT_MS}.
 *
 * <p>
 * Hard cap
 * -----------
 * Even with eviction, a flood of voiceKeys (a buggy server, a debug
 * command) could push the bridge past the AL ceiling within one
 * tick. {@link #MAX_VOICES} caps the live set; on overflow the
 * oldest entry (smallest lastSeenTickMs) is disposed before a new
 * one is created.
 *
 * <p>
 * Oscilloscope tap (receiver GUI)
 * ----------------------------------
 * The receiver GUI shows a live audio waveform, the kind of scope an
 * SDR front-end draws. The PCM needed for it already passes through
 * here on its way to the AL queue, so the bridge keeps a small
 * decimated snapshot of the most recent frame per voice key in
 * {@link #WAVEFORMS}. {@link #latestWaveform} hands it to the GUI. The
 * snapshot is peak-decimated to {@link #OSC_SAMPLES} points so it keeps
 * the envelope shape while staying cheap to store and draw; it is pruned
 * on the same lifecycle events as the voice it belongs to, so it never
 * outlives its source.
 *
 * <p>
 * Thread model: the server thread (or the net thread, for dedicated
 * server clients) flags state, and the client tick performs every AL
 * operation. The waveform map is touched only inside the
 * already-synchronized methods and the synchronized accessor, so it
 * shares the class monitor with the rest of the registry.
 */
public final class ClientAudioBridge {

    /**
     * Soft cap on simultaneously live voices. Sized comfortably
     * below the conservative 32-source AL limit so a few vanilla
     * sound effects and a handful of receivers can coexist
     * without saturating the pool.
     */
    private static final int MAX_VOICES = 24;

    /**
     * Maximum gap, in wall-clock milliseconds, between consecutive
     * PCM arrivals before the voice is considered stale and gets
     * disposed. Sized to absorb a transmitter PTT release (about
     * 750 ms) plus a few seconds of network jitter while still
     * cleaning up promptly when a receiver actually disappears.
     */
    private static final long IDLE_TIMEOUT_MS = 5_000L;

    /** Number of points in the decimated waveform snapshot. */
    private static final int OSC_SAMPLES = 96;

    private static final Map<String, VoiceEntry> VOICES = new HashMap<>();

    /** Latest decimated waveform per voice key, for the GUI scope. */
    private static final Map<String, float[]> WAVEFORMS = new HashMap<>();

    private ClientAudioBridge() {}

    /**
     * Build the stable voice key for a block receiver. Kept here
     * so the convention lives in one file; the server side mirrors
     * the same formula in {@link io.hertzian.dynamics.tile.TileRadioReceiver}.
     */
    public static String blockKey(int dim, int x, int y, int z) {
        return "b:" + dim + ":" + x + ":" + y + ":" + z;
    }

    /**
     * Build the stable voice key for a handheld receiver. The UUID
     * string already includes enough entropy that no two players
     * collide; we prefix with {@code "h:"} so the player namespace
     * is distinct from the block namespace.
     */
    public static String handheldKey(java.util.UUID playerId) {
        return "h:" + playerId.toString();
    }

    /**
     * Most recent decimated waveform for the given voice key, or
     * null if no PCM has arrived (or the voice was disposed). The
     * values are in roughly [-1, 1]; the GUI maps them onto its
     * scope box. Returned array must not be mutated by the caller.
     */
    public static synchronized float[] latestWaveform(String voiceKey) {
        return voiceKey == null ? null : WAVEFORMS.get(voiceKey);
    }

    /**
     * Network/server entry. Queues a fresh PCM frame for the voice
     * identified by {@code voiceKey}. The {@code (x, y, z)} tuple
     * is the latest world position the AL source should track; if
     * the voice already exists, its source is moved instead of
     * being reconstructed.
     *
     * <p>
     * If the modulation differs from the existing voice's, the
     * voice is marked for disposal and a replacement is queued;
     * the next client tick promotes the replacement.
     */
    public static synchronized void routePcm(String voiceKey, int dim, int x, int y, int z, Modulation modulation,
        int sampleRateHz, short[] pcm) {
        AudioSubsystem sub = AudioSubsystem.get();
        if (sub == null || !sub.isAvailable()) return;
        if (voiceKey == null || voiceKey.isEmpty()) return;

        long now = System.currentTimeMillis();

        VoiceEntry entry = VOICES.get(voiceKey);
        if (entry == null) {
            // Make room if we are already at the cap. The eviction
            // picks the voice that has gone the longest without
            // a PCM frame, which heuristically matches "the radio
            // the player has lost interest in".
            if (VOICES.size() >= MAX_VOICES) {
                evictOldest();
            }
            entry = new VoiceEntry(modulation, sampleRateHz, x, y, z);
            VOICES.put(voiceKey, entry);
            HertzianDynamics.LOGGER.info("Audio voice queued for key {} at ({}, {}, {})", voiceKey, x, y, z);
        } else if (entry.modulation != modulation) {
            // Modulation change: keep the old voice alive until
            // the new one is ready so the listener does not hear
            // a glitch on switchover.
            entry.disposalRequested = true;
            String replacementKey = voiceKey + "#new";
            VoiceEntry replacement = new VoiceEntry(modulation, sampleRateHz, x, y, z);
            replacement.lastSeenTickMs = now;
            VOICES.put(replacementKey, replacement);
            return;
        } else {
            // Same voice, possibly new position. Update the
            // pending position so the next tick moves the AL
            // source; the AL_POSITION call itself must run on the
            // client thread, so we just stash the floats here.
            entry.pendingPosX = x + 0.5f;
            entry.pendingPosY = y + 0.5f;
            entry.pendingPosZ = z + 0.5f;
            entry.hasPendingPos = true;
        }
        entry.lastSeenTickMs = now;

        // Replace any previous pending frame: a stale buffer is
        // worse than dropping one frame, the listener gets fresh
        // audio every tick this way.
        entry.pendingPcm = pcm;

        // Tap a decimated copy for the GUI scope.
        captureWaveform(voiceKey, pcm);

        // Feed the radio audio into the microphone environment mixer so a
        // nearby radio's speaker bleeds into a transmitting handheld, the
        // way one real radio held next to another is heard over the air.
        // The local player's own handheld voice is skipped: it plays the
        // audio this player receives, and capturing it while transmitting
        // would echo it straight back to the far station, a feedback loop.
        // Other handhelds and every block receiver are captured normally.
        if (!voiceKey.equals(localHandheldKey())) {
            io.hertzian.dynamics.audio.EnvironmentMixer.get()
                .onRadioPcm(pcm, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    /**
     * Voice key of the local player's own handheld, or a sentinel that
     * matches nothing when there is no client player yet. Used to keep a
     * transmitting radio from capturing the audio it is itself receiving.
     */
    private static String localHandheldKey() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return "";
        return handheldKey(mc.thePlayer.getUniqueID());
    }

    /**
     * Peak-decimate {@code pcm} into {@link #OSC_SAMPLES} points and
     * store it under the voice key. Peak decimation (keep the largest
     * magnitude sample of each bucket) preserves the envelope so a
     * tone or voice burst reads as a real waveform rather than an
     * aliased average that would flatten to near zero.
     */
    private static void captureWaveform(String voiceKey, short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        float[] w = new float[OSC_SAMPLES];
        int len = pcm.length;
        for (int i = 0; i < OSC_SAMPLES; i++) {
            int s = (int) ((long) i * len / OSC_SAMPLES);
            int e = (int) ((long) (i + 1) * len / OSC_SAMPLES);
            if (e <= s) e = s + 1;
            if (e > len) e = len;
            float peak = 0f;
            for (int j = s; j < e; j++) {
                float v = pcm[j] / 32768f;
                if (Math.abs(v) > Math.abs(peak)) peak = v;
            }
            w[i] = peak;
        }
        WAVEFORMS.put(voiceKey, w);
    }

    /**
     * Find and dispose the voice with the smallest {@code lastSeenTickMs}.
     * Called when the bridge is at the soft cap and a fresh
     * voiceKey arrives. Logged at info level because eviction is
     * unusual enough to warrant a diagnostic.
     */
    private static void evictOldest() {
        String oldestKey = null;
        long oldestSeen = Long.MAX_VALUE;
        for (Map.Entry<String, VoiceEntry> e : VOICES.entrySet()) {
            if (e.getValue().lastSeenTickMs < oldestSeen) {
                oldestSeen = e.getValue().lastSeenTickMs;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey == null) return;
        VoiceEntry victim = VOICES.remove(oldestKey);
        if (victim != null && victim.voice != null) {
            victim.voice.dispose();
        }
        WAVEFORMS.remove(oldestKey);
        HertzianDynamics.LOGGER.info("Audio voice cap reached; evicted oldest key {}", oldestKey);
    }

    /**
     * Tick callback executed by {@link AudioSubsystem#tick} on the
     * client main thread. Handles three jobs in this order:
     * <ol>
     * <li>Dispose any voice flagged for disposal (modulation
     * change, explicit unregister, idle timeout).</li>
     * <li>Promote any pending replacement voice into the slot
     * its predecessor vacated.</li>
     * <li>For every live voice, apply any pending position
     * update, push any pending PCM, and run the voice's own
     * per-tick maintenance (buffer recycling).</li>
     * </ol>
     */
    static synchronized void tickAllVoices() {
        long now = System.currentTimeMillis();

        // Mark idle voices for disposal. The timeout is wall-clock
        // based rather than tick-based so a paused or lag-spiked
        // client does not aggressively reap voices.
        for (VoiceEntry entry : VOICES.values()) {
            if (now - entry.lastSeenTickMs > IDLE_TIMEOUT_MS) {
                entry.disposalRequested = true;
            }
        }

        // Phase 1 + 2: dispose flagged voices, queue promotions.
        Iterator<Map.Entry<String, VoiceEntry>> it = VOICES.entrySet()
            .iterator();
        List<String> deferredPromotion = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<String, VoiceEntry> e = it.next();
            VoiceEntry entry = e.getValue();
            if (entry.disposalRequested) {
                if (entry.voice != null) {
                    entry.voice.dispose();
                    entry.voice = null;
                }
                String key = e.getKey();
                it.remove();
                WAVEFORMS.remove(key);
                if (key.endsWith("#new")) continue;
                if (VOICES.containsKey(key + "#new")) deferredPromotion.add(key);
            }
        }
        for (String key : deferredPromotion) {
            VoiceEntry rep = VOICES.remove(key + "#new");
            if (rep != null) VOICES.put(key, rep);
        }

        // Phase 3: push audio + position updates into the AL
        // sources, creating them lazily on the first PCM frame.
        for (VoiceEntry entry : VOICES.values()) {
            short[] pending = entry.pendingPcm;
            entry.pendingPcm = null;
            if (pending != null) {
                if (entry.voice == null) {
                    entry.voice = ReceiverVoice
                        .create(entry.sampleRateHz, entry.pendingPosX, entry.pendingPosY, entry.pendingPosZ);
                    if (entry.voice == null) continue;
                    entry.hasPendingPos = false;
                    HertzianDynamics.LOGGER.info(
                        "AL voice instantiated at ({}, {}, {}) modulation={}",
                        entry.pendingPosX,
                        entry.pendingPosY,
                        entry.pendingPosZ,
                        entry.modulation);
                }
                entry.voice.pushPcm(pending);
            }
            if (entry.voice != null && entry.hasPendingPos) {
                entry.voice.setPosition(entry.pendingPosX, entry.pendingPosY, entry.pendingPosZ);
                entry.hasPendingPos = false;
            }
            if (entry.voice != null) entry.voice.tick();
        }
    }

    /**
     * Backward-compatible explicit unregister, addressed by the
     * legacy block coordinate scheme. Kept on the public API for
     * callers that still operate on block positions.
     */
    public static synchronized void unregister(int dim, int x, int y, int z) {
        unregisterByKey(blockKey(dim, x, y, z));
    }

    /**
     * Mark the named voice for disposal on the next client tick.
     * Used by the receiver tile entity's retune path, by the
     * handheld registry when a player powers their radio off, and
     * by any future caller that knows the voice key directly.
     */
    public static synchronized void unregisterByKey(String voiceKey) {
        VoiceEntry entry = VOICES.get(voiceKey);
        if (entry != null) entry.disposalRequested = true;
        VoiceEntry rep = VOICES.get(voiceKey + "#new");
        if (rep != null) rep.disposalRequested = true;
        WAVEFORMS.remove(voiceKey);
        WAVEFORMS.remove(voiceKey + "#new");
    }

    public static synchronized void shutdown() {
        for (VoiceEntry e : VOICES.values()) e.disposalRequested = true;
    }

    static synchronized void shutdownNow() {
        for (VoiceEntry e : VOICES.values()) {
            if (e.voice != null) e.voice.dispose();
        }
        VOICES.clear();
        WAVEFORMS.clear();
    }

    /**
     * Per-voice state. Fields are package-private because all the
     * code that touches them lives in this file and the field-by-
     * field access pattern is cheaper than a constructor + getter
     * dance for a hot path that runs once per tick per voice.
     */
    private static final class VoiceEntry {

        final Modulation modulation;
        final int sampleRateHz;
        ReceiverVoice voice;
        short[] pendingPcm;
        float pendingPosX, pendingPosY, pendingPosZ;
        boolean hasPendingPos;
        long lastSeenTickMs;
        volatile boolean disposalRequested = false;

        VoiceEntry(Modulation m, int sr, float x, float y, float z) {
            this.modulation = m;
            this.sampleRateHz = sr;
            this.pendingPosX = x + 0.5f;
            this.pendingPosY = y + 0.5f;
            this.pendingPosZ = z + 0.5f;
            this.hasPendingPos = true;
            this.lastSeenTickMs = System.currentTimeMillis();
        }
    }
}
