package io.hertzian.dynamics.tile;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.world.WorldRfState;

/**
 * Synthetic audio source for the {@link io.hertzian.dynamics.block.BlockTestTone}.
 */
public final class TileTestTone extends TileEntity {

    public enum Mode {
        TONE_1KHZ,
        MUSIC,
        SWEEP,
        BUZZER_UVB76
    }

    private static final float SAMPLE_RATE = 48_000.0f;
    private static final int RANGE_BLOCKS = 8;

    /** Close Encounters of the Third Kind, five-note motif. */
    private static final double[] MUSIC_NOTES_HZ = { 392.00, // G4
        440.00, // A4
        349.23, // F4
        174.61, // F3
        261.63, // C4
    };
    private static final int NOTE_SAMPLES = 24_000;
    private static final int NOTE_RAMP = 480;
    private static final int NOTE_GAP_SAMPLES = 4_800;
    private static final int LOOP_GAP_SAMPLES = 96_000;

    private static final int SWEEP_LEN_SAMPLES = 96_000;
    private static final double SWEEP_F0 = 300.0;
    private static final double SWEEP_F1 = 3_000.0;
    private static final int SWEEP_GAP_SAMPLES = 24_000;

    private static final double BUZZ_FUNDAMENTAL_HZ = 810.0;

    /**
     * Peak frequency offset of the random wobble, in Hz. The wobble
     * is updated at a slow rate (about 8 times per second) and
     * interpolated linearly between updates so the resulting pitch
     * variation sounds organic rather than stepped.
     */
    private static final double BUZZ_WOBBLE_HZ = 3.0;

    /**
     * AM modulation frequency that produces the rasping "buzz"
     * character. 30 Hz sits in the perceptual sweet spot for
     * mechanical roughness: above the threshold where AM is heard
     * as tremolo (around 20 Hz) and below the threshold where it
     * starts producing audible difference tones (around 60 Hz).
     */
    private static final double BUZZ_AM_HZ = 30.0;

    /** AM modulation depth. 0.25 means amplitude varies from 0.75 to 1.0. */
    private static final double BUZZ_AM_DEPTH = 0.25;

    /** Active phase duration, samples. 1.2 seconds at 48 kHz. */
    private static final int BUZZ_ACTIVE_SAMPLES = 57_600;

    /** Inter-buzz pause, samples. 1.0 second at 48 kHz. */
    private static final int BUZZ_PAUSE_SAMPLES = 48_000;

    /** Full cycle: active phase plus pause. */
    private static final int BUZZ_CYCLE_SAMPLES = BUZZ_ACTIVE_SAMPLES + BUZZ_PAUSE_SAMPLES;

    /**
     * Carrier phase accumulator for the tonewheel oscillator, in
     * radians. Kept in double precision because at 810 Hz over the
     * 1.2 second active phase the phase accumulates roughly 6100
     * radians, which f32 would round into audible warble.
     */
    private double buzzCarrierPhase = 0.0;

    /**
     * AM modulator phase accumulator, in radians.
     */
    private double buzzAmPhase = 0.0;

    /**
     * Current pitch wobble offset in Hz. Updated occasionally and
     * interpolated linearly between updates by the synthesis loop.
     */
    private double buzzWobbleCurrentHz = 0.0;
    private double buzzWobbleTargetHz = 0.0;

    /**
     * Sample counter for the wobble interpolation. The wobble target
     * is refreshed every {@code SAMPLE_RATE / 8} samples (8 Hz
     * update rate), and the current value linearly approaches the
     * target across that interval. This produces a soft natural
     * wobble that listeners hear as "this is a real mechanical
     * device" rather than as a deliberate LFO.
     */
    private int buzzWobbleCounter = 0;
    private static final int BUZZ_WOBBLE_INTERVAL = (int) (SAMPLE_RATE / 8.0);

    /**
     * Java's {@link Math#random()} is acceptable for the mechanical
     * rattle and attack transientff because those add tiny amounts of
     * uncorrelated noise; the cryptographic quality of the PRNG is
     * irrelevant here, and the convenience of not threading a
     * dedicated Random instance through the call chain is worth the
     * small synchronisation cost on Math.random's shared state.
     */

    private Mode mode = Mode.MUSIC;
    private double phase = 0.0;
    private long sampleIndex = 0L;

    public Mode mode() {
        return mode;
    }

    public void cycleMode() {
        Mode[] all = Mode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        sampleIndex = 0;
        phase = 0.0;
        markDirty();
    }

    public void pumpInto(List<TileRadioTransmitter> transmitters, WorldRfState state, int frameSamples) {
        if (worldObj == null || worldObj.isRemote) return;
        TileRadioTransmitter best = findNearest(transmitters);
        if (best == null) return;
        float[] frame = new float[frameSamples];
        switch (mode) {
            case TONE_1KHZ -> fillTone(frame, 1000.0);
            case MUSIC -> fillMusic(frame);
            case SWEEP -> fillSweep(frame);
            case BUZZER_UVB76 -> fillBuzzerUvb76(frame);
        }
        best.enqueueAudio(frame);
    }

    /** Nearest transmitter, exposed for the block class auto-tune path. */
    public TileRadioTransmitter findNearestTransmitter(List<TileRadioTransmitter> transmitters) {
        return findNearest(transmitters);
    }

    private TileRadioTransmitter findNearest(List<TileRadioTransmitter> transmitters) {
        TileRadioTransmitter best = null;
        double bestSq = (double) RANGE_BLOCKS * RANGE_BLOCKS;
        for (TileRadioTransmitter tx : transmitters) {
            if (tx.getWorldObj() != worldObj) continue;
            double dx = tx.xCoord - xCoord;
            double dy = tx.yCoord - yCoord;
            double dz = tx.zCoord - zCoord;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = tx;
            }
        }
        return best;
    }

    private void fillTone(float[] frame, double hz) {
        double step = 2.0 * Math.PI * hz / SAMPLE_RATE;
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (float) (0.7 * Math.sin(phase));
            phase += step;
            if (phase > Math.PI) phase -= 2.0 * Math.PI;
        }
        sampleIndex += frame.length;
    }

    private void fillMusic(float[] frame) {
        int notesLen = MUSIC_NOTES_HZ.length * (NOTE_SAMPLES + NOTE_GAP_SAMPLES);
        int cycleLen = notesLen + LOOP_GAP_SAMPLES;
        for (int i = 0; i < frame.length; i++) {
            long pos = (sampleIndex + i) % cycleLen;
            double hz;
            float env;
            if (pos >= notesLen) {
                frame[i] = 0f;
                phase = 0.0;
                continue;
            }
            int perNote = NOTE_SAMPLES + NOTE_GAP_SAMPLES;
            int noteIdx = (int) (pos / perNote);
            int inNote = (int) (pos % perNote);
            if (inNote >= NOTE_SAMPLES) {
                frame[i] = 0f;
                phase = 0.0;
                continue;
            }
            hz = MUSIC_NOTES_HZ[noteIdx];
            if (inNote < NOTE_RAMP) {
                env = (float) (0.5 * (1.0 - Math.cos(Math.PI * inNote / NOTE_RAMP)));
            } else if (inNote > NOTE_SAMPLES - NOTE_RAMP) {
                int tail = NOTE_SAMPLES - inNote;
                env = (float) (0.5 * (1.0 - Math.cos(Math.PI * tail / NOTE_RAMP)));
            } else {
                env = 1.0f;
            }
            double step = 2.0 * Math.PI * hz / SAMPLE_RATE;
            phase += step;
            if (phase > Math.PI) phase -= 2.0 * Math.PI;
            frame[i] = (float) (0.6 * env * Math.sin(phase));
        }
        sampleIndex += frame.length;
    }

    private void fillSweep(float[] frame) {
        int cycle = SWEEP_LEN_SAMPLES + SWEEP_GAP_SAMPLES;
        for (int i = 0; i < frame.length; i++) {
            long pos = (sampleIndex + i) % cycle;
            if (pos >= SWEEP_LEN_SAMPLES) {
                frame[i] = 0f;
                phase = 0.0;
                continue;
            }
            double t = (double) pos / SWEEP_LEN_SAMPLES;
            double hz = SWEEP_F0 + t * (SWEEP_F1 - SWEEP_F0);
            double step = 2.0 * Math.PI * hz / SAMPLE_RATE;
            phase += step;
            if (phase > Math.PI) phase -= 2.0 * Math.PI;
            frame[i] = (float) (0.6 * Math.sin(phase));
        }
        sampleIndex += frame.length;
    }

    private void fillBuzzerUvb76(float[] frame) {
        for (int i = 0; i < frame.length; i++) {
            long pos = (sampleIndex + i) % BUZZ_CYCLE_SAMPLES;
            if (pos >= BUZZ_ACTIVE_SAMPLES) {
                // Silent pause. Reset per-buzz state so the next buzz
                // starts cleanly.
                frame[i] = 0f;
                buzzCarrierPhase = 0.0;
                buzzAmPhase = 0.0;
                buzzWobbleCurrentHz = 0.0;
                buzzWobbleTargetHz = 0.0;
                buzzWobbleCounter = 0;
                continue;
            }

            // Normalised position within the active phase, range [0, 1).
            double t = (double) pos / BUZZ_ACTIVE_SAMPLES;

            // Three-stage amplitude envelope. The boundaries (5%,
            // 70%, 95%) and amplitude levels (peak 1.0, sustain
            // droop to 0.70, second rise to 0.90) come from rough
            // digitisation of the triggered-amplitude plot on
            // ominous-valve.com. The raised-cosine ramps at the very
            // start and very end suppress switching clicks.
            float envAmp;
            if (t < 0.05) {
                envAmp = (float) (0.5 - 0.5 * Math.cos(Math.PI * t / 0.05));
            } else if (t < 0.70) {
                double localT = (t - 0.05) / 0.65;
                envAmp = (float) (1.0 - 0.30 * localT);
            } else if (t < 0.95) {
                double localT = (t - 0.70) / 0.25;
                envAmp = (float) (0.70 + 0.20 * localT);
            } else {
                double localT = (t - 0.95) / 0.05;
                envAmp = (float) (0.90 * 0.5 * (1.0 + Math.cos(Math.PI * localT)));
            }

            // Refresh the wobble target every BUZZ_WOBBLE_INTERVAL
            // samples. The Math.random() call is fine here even
            // though it touches a global synchronised PRNG, because
            // it fires only 8 times per second per buzzer block.
            if (buzzWobbleCounter <= 0) {
                buzzWobbleTargetHz = (Math.random() * 2.0 - 1.0) * BUZZ_WOBBLE_HZ;
                buzzWobbleCounter = BUZZ_WOBBLE_INTERVAL;
            }
            buzzWobbleCounter--;
            // Linear interpolation toward the target. The step size
            // is set so the current value reaches the target exactly
            // at the next refresh boundary.
            double wobbleStep = (buzzWobbleTargetHz - buzzWobbleCurrentHz) / Math.max(1, buzzWobbleCounter + 1);
            buzzWobbleCurrentHz += wobbleStep;

            // Carrier and AM phase advance.
            double currentHz = BUZZ_FUNDAMENTAL_HZ + buzzWobbleCurrentHz;
            double carrierStep = 2.0 * Math.PI * currentHz / SAMPLE_RATE;
            double amStep = 2.0 * Math.PI * BUZZ_AM_HZ / SAMPLE_RATE;
            buzzCarrierPhase += carrierStep;
            buzzAmPhase += amStep;

            double carrier = Math.sin(buzzCarrierPhase) + 0.25 * Math.sin(2.0 * buzzCarrierPhase)
                + 0.125 * Math.sin(3.0 * buzzCarrierPhase);
            // Normalisation factor: peak amplitude of the harmonic
            // sum is bounded by 1.0 + 0.25 + 0.125 = 1.375, so we
            // divide to keep the carrier in roughly [-1, 1] before
            // AM modulation.
            carrier *= (1.0 / 1.375);

            // AM modulation. The (1 - cos) form keeps the amplitude
            // strictly non-negative; a (1 + sin) form would also
            // work but would put the modulation peaks at different
            // phases, which sounds slightly different to the ear.
            double amEnvelope = 1.0 - BUZZ_AM_DEPTH + BUZZ_AM_DEPTH * 0.5 * (1.0 - Math.cos(buzzAmPhase));
            double modulated = carrier * amEnvelope;

            // Broadband noise mixed in at low level. Models the
            // open-microphone room ambience plus the AM channel
            // noise. The 0.03 peak amplitude is barely audible
            // against the tone but is essential for the
            // "transmitted through a noisy radio link" feel; without
            // it the synthesis sounds like a clean studio recording.
            double noise = (Math.random() - 0.5) * 0.06;

            // Compose the final sample. The 0.7 headroom factor
            // matches the other test tone modes so switching between
            // them does not produce a perceived loudness jump.
            float sample = (float) (envAmp * (modulated + noise) * 0.7);
            if (sample > 1.0f) sample = 1.0f;
            else if (sample < -1.0f) sample = -1.0f;
            frame[i] = sample;
        }
        sampleIndex += frame.length;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("mode", mode.ordinal());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("mode")) {
            int o = tag.getInteger("mode");
            Mode[] all = Mode.values();
            if (o >= 0 && o < all.length) mode = all[o];
        }
    }
}
