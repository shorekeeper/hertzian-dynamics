package io.hertzian.dynamics.core;

/**
 * Spectrum chunk demodulator. Lives in the {@code core} package
 * (not {@code audio}) because in multiplayer the server runs this
 * code to convert IQ chunks into PCM before sending them to the
 * client. The audio package only consumes the resulting PCM.
 *
 * <p>
 * State carrying: every receiver slot in {@link io.hertzian.dynamics.world.WorldRfState}
 * owns one demodulator instance; recreation on retune is explicit.
 * Channel noise is not synthesised in the audio domain. The rf-core
 * mixer injects a physical thermal-plus-environment noise floor into
 * the IQ chunk before demodulation, so an empty or weak channel
 * produces hiss (or, under FM squelch, silence) that tracks the real
 * signal-to-noise ratio.
 *
 * <p>
 * FM squelch
 * -------------
 * FM demodulation has a well known failure mode: when the carrier
 * disappears (transmitter squelches off-air, or the path closes
 * entirely) the {@code atan2} phase derivative receives only
 * receiver-side noise. The output of {@code atan2} on pure Gaussian
 * noise is uniformly distributed in [-pi, pi], which after the
 * fmGain multiplier clips against the [-1, 1] limiter and produces
 * extremely loud, harsh white noise, the open-squelch sound a real
 * radio makes on an empty channel.
 *
 * <p>
 * The gate is driven by the envelope statistics of the input IQ. An
 * FM modulator produces a unit-amplitude complex exponential, so the
 * magnitude {@code sqrt(I^2 + Q^2)} is nearly constant for a clean
 * carrier. Receiver-side thermal noise added to a possibly absent
 * carrier makes the magnitude Rician; in the no-signal limit it is
 * Rayleigh distributed with coefficient of variation (standard
 * deviation divided by mean) equal to {@code sqrt((4 - pi) / pi)},
 * about 0.5227.
 *
 * <p>
 * The squelch measures the envelope CV across the chunk and produces
 * a target gain on a smooth ramp:
 * <ul>
 * <li>{@code CV <= 0.20}: clear carrier, full gain (open squelch)</li>
 * <li>{@code CV >= 0.45}: dominant noise, zero gain (closed squelch)</li>
 * <li>between: linear ramp</li>
 * </ul>
 * The instantaneous gain is smoothed sample-by-sample with a
 * single-pole filter so open/close transitions are inaudible. The
 * rate constant ({@link #SQUELCH_RATE} = 0.001 at 48 kHz) gives a
 * time constant near 20 ms, fast enough to catch the start of a
 * transmission without clipping the leading edge yet slow enough to
 * ignore brief envelope dips inside a modulated signal.
 *
 * <p>
 * AM, SSB and CW do not use this gate. AM carries the modulation in
 * its envelope, so the envelope CV always reads high and the gate
 * would never open. SSB carries data on a single sideband with no
 * carrier reference, so envelope gating is meaningless. CW has its
 * own threshold inside the demodulator. For those modes the squelch
 * gain is pinned at 1.0.
 *
 * <p>
 * Channel noise
 * ---------------
 * The physical noise floor flows through the demodulator from the
 * rf-core mixer (thermal kTB scaled by the receiver noise figure and
 * the external environment noise). On a strong signal the demodulated
 * audio is clean; on a weak one the hiss rises; on an empty channel
 * the AGC lifts the bare noise to full scale, which an AM or SSB
 * receiver renders as loud static and an FM receiver squelches to
 * silence. No synthetic audio-domain noise is added, since it would
 * duplicate and mask the physical floor.
 *
 * <p>
 * Receive conditioning
 * ----------------------
 * The speech-processor soft clip is applied at the transmitter, as a
 * real radio does. The receive chain performs:
 *
 * <ul>
 * <li>Station ID tap. The raw demodulated audio is fed to a
 * {@link StationIdDecoder} before any filtering, so the 16/18 kHz
 * data subcarrier is still intact. The recovered name is exposed
 * through {@link #lastStationName}.</li>
 * <li>De-emphasis on FM. Broadcast and narrowband FM pre-emphasise
 * the highs at the transmitter; the matched one-pole de-emphasis
 * here is the exact inverse, restoring a clean channel flat while
 * cutting the high-frequency noise the pre-emphasis was fighting.</li>
 * <li>Communications passband per mode: the band limit that gives the
 * output its radio character and removes the inaudible data
 * subcarrier along with out-of-band hiss.</li>
 * </ul>
 *
 * <p>
 * Adjustable wideband noise reduction lives on the receiver tile,
 * not here, because it is keyed off the chunk signal to noise ratio
 * the tile measures; see the receiver for that module.
 */
public final class ChunkDemodulator {

    private final Modulation modulation;
    private final float sampleRateHz;

    private float dcPrevX = 0f, dcPrevY = 0f;
    private static final float DC_R = 0.999f;

    private float prevI = 1f, prevQ = 0f;
    private final float fmGain;

    private double cwPhase = 0.0;
    private final double cwStep;
    private static final float CW_THRESHOLD = 0.05f;

    /**
     * AM modulation index assumed by the demodulator. Mirrors the value
     * baked into rf-core's AmModulator (AmModulator::new(0.8)); the two
     * must agree or the recovered audio comes out at the wrong scale.
     * The DC-blocked envelope is divided by this index, exactly as
     * rf-core's AmDemodulator does with its inv_m term, so a unit
     * modulation depth maps back to unit audio amplitude.
     */
    private static final float AM_MODULATION_INDEX = 0.8f;
    private static final float AM_DEMOD_GAIN = 1.0f / AM_MODULATION_INDEX;

    /**
     * Smoothed squelch gain for FM modes. Starts open so that the
     * first chunk arriving on a previously-quiet receiver is heard
     * once the envelope statistics confirm a carrier; the gate
     * then closes on the next chunk if needed.
     */
    private float squelchGain = 1.0f;

    /**
     * Per-sample blend factor for the squelch smoothing. Picked so
     * the resulting time constant (1 / rate / fs) is roughly 20 ms
     * at 48 kHz, comfortably below the 50 ms tick boundary so a
     * single tick of carrier presence is enough to fully open the
     * gate, while still slow enough to mask sample-level jitter.
     */
    private static final float SQUELCH_RATE = 0.001f;

    /** CV at or below which the gate is fully open. */
    private static final float SQUELCH_CV_OPEN = 0.20f;

    /** CV at or above which the gate is fully closed. */
    private static final float SQUELCH_CV_CLOSE = 0.45f;

    /** Communications passband, per mode, carried across chunks. */
    private final Biquad[] audioChain;

    /** True for FM modes: apply the matched de-emphasis one-pole. */
    private final boolean deemphasis;
    private float deemphState = 0f;
    /**
     * De-emphasis blend factor. alpha = dt / (tau + dt) with a 50 us
     * time constant at 48 kHz; the transmitter pre-emphasis is the exact
     * inverse one-zero filter, so the pair restores a clean channel flat.
     */
    private static final float DEEMPH_ALPHA = 0.294f;

    /** Recovers the embedded station name from the raw demod audio. */
    private final StationIdDecoder stationDecoder = new StationIdDecoder();

    /** Scratch for the station decode tap, grown on demand. */
    private float[] stationTap = new float[0];

    public ChunkDemodulator(Modulation modulation, float sampleRateHz) {
        this.modulation = modulation;
        this.sampleRateHz = sampleRateHz;
        float deviation = switch (modulation) {
            case NARROW_FM -> 5_000f;
            case WIDE_FM -> 15_000f;
            default -> 1f;
        };
        this.fmGain = sampleRateHz / (float) (2 * Math.PI * deviation);
        this.cwStep = 2.0 * Math.PI * 700.0 / sampleRateHz;
        this.audioChain = buildAudioChain(modulation, sampleRateHz);
        this.deemphasis = modulation == Modulation.NARROW_FM || modulation == Modulation.WIDE_FM;
    }

    /**
     * Demodulate one chunk into floating point PCM in [-1, 1].
     * Used by the client-side preview path and by the test
     * harnesses. The server-side audio packet path uses
     * {@link #demodulateToPcm16} which folds the int16 quantisation
     * into the same loop.
     *
     * <p>
     * The squelch ramp is applied here as well so behaviour is
     * identical across both demodulation entry points; otherwise
     * the diagnostic float path would have a different envelope
     * profile than what the player actually hears.
     */
    public int demodulate(SpectrumChunk chunk, float[] out) {
        int n = chunk.sampleCount();
        float[] iq = chunk.samples();
        if (out.length < n) throw new IllegalArgumentException("out too small");
        float target = computeSquelchTarget(iq, n);
        runDemod(iq, out, n);
        applySquelch(out, n, target);
        feedStationDecoder(out, n);
        applyReceiveChain(out, n);
        return n;
    }

    /**
     * Demodulate one chunk directly into 16-bit signed PCM. This
     * is the network path: the server calls this, copies the
     * resulting bytes into the packet payload, and the client
     * pushes the same bytes into its AL buffer queue without
     * any further demodulation work.
     *
     * <p>
     * Going straight to int16 saves a redundant float allocation and
     * a second pass over the buffer. Channel noise arrives with the IQ
     * from the physical mixer floor, so this path and {@link #demodulate}
     * see the same noise with no extra audio-domain mixing.
     */
    public int demodulateToPcm16(SpectrumChunk chunk, short[] out) {
        int n = chunk.sampleCount();
        float[] iq = chunk.samples();
        if (out.length < n) throw new IllegalArgumentException("out too small");
        float target = computeSquelchTarget(iq, n);
        float[] tmp = new float[n];
        runDemod(iq, tmp, n);
        applySquelch(tmp, n, target);
        feedStationDecoder(tmp, n);
        applyReceiveChain(tmp, n);
        for (int k = 0; k < n; k++) {
            float v = tmp[k] * 32767f;
            if (v > 32767f) v = 32767f;
            else if (v < -32768f) v = -32768f;
            out[k] = (short) v;
        }
        return n;
    }

    /**
     * Compute the target squelch gain for the current chunk based
     * on envelope coefficient of variation. Returns 1.0 verbatim
     * for non-FM modes so the caller can apply the smoothing loop
     * unconditionally without special casing.
     *
     * <p>
     * The math is a single linear pass over the chunk: sum of
     * magnitudes, sum of squared magnitudes, then closed form mean
     * and variance. Total cost per chunk is one sqrt per sample
     * plus a handful of multiplies, which is negligible compared
     * to the demod itself.
     */
    private float computeSquelchTarget(float[] iq, int n) {
        if (modulation != Modulation.NARROW_FM && modulation != Modulation.WIDE_FM) {
            return 1.0f;
        }
        if (n < 8) {
            // Too few samples to derive useful statistics; hold the
            // existing gain so we do not pump on tiny chunks.
            return squelchGain;
        }
        double sumMag = 0.0;
        double sumMagSq = 0.0;
        for (int k = 0; k < n; k++) {
            float i = iq[2 * k];
            float q = iq[2 * k + 1];
            double mag = Math.sqrt(i * i + q * q);
            sumMag += mag;
            sumMagSq += mag * mag;
        }
        double mean = sumMag / n;
        double variance = sumMagSq / n - mean * mean;
        if (mean < 1.0e-6) {
            // Effectively zero envelope: nothing to demodulate.
            return 0.0f;
        }
        double cv = Math.sqrt(Math.max(0.0, variance)) / mean;
        if (cv <= SQUELCH_CV_OPEN) return 1.0f;
        if (cv >= SQUELCH_CV_CLOSE) return 0.0f;
        return (float) ((SQUELCH_CV_CLOSE - cv) / (SQUELCH_CV_CLOSE - SQUELCH_CV_OPEN));
    }

    /**
     * Apply the squelch gain to the demodulated buffer with smooth
     * sample-by-sample transition. Skips entirely for non-FM modes,
     * which then stay allocation free and pass through unchanged.
     */
    private void applySquelch(float[] out, int n, float target) {
        if (modulation != Modulation.NARROW_FM && modulation != Modulation.WIDE_FM) {
            return;
        }
        for (int k = 0; k < n; k++) {
            squelchGain += (target - squelchGain) * SQUELCH_RATE;
            out[k] *= squelchGain;
        }
    }

    private void runDemod(float[] iq, float[] out, int n) {
        switch (modulation) {
            case AM -> {
                for (int k = 0; k < n; k++) {
                    float i = iq[2 * k], q = iq[2 * k + 1];
                    float env = (float) Math.sqrt(i * i + q * q);
                    float y = env - dcPrevX + DC_R * dcPrevY;
                    dcPrevX = env;
                    dcPrevY = y;
                    out[k] = clamp(y * AM_DEMOD_GAIN);
                }
            }
            case NARROW_FM, WIDE_FM -> {
                for (int k = 0; k < n; k++) {
                    float i = iq[2 * k], q = iq[2 * k + 1];
                    float dotI = i * prevI + q * prevQ;
                    float dotQ = q * prevI - i * prevQ;
                    out[k] = clamp((float) Math.atan2(dotQ, dotI) * fmGain);
                    prevI = i;
                    prevQ = q;
                }
            }
            case USB, LSB -> {
                for (int k = 0; k < n; k++) out[k] = clamp(iq[2 * k] * 4f);
            }
            case CW -> {
                for (int k = 0; k < n; k++) {
                    float i = iq[2 * k], q = iq[2 * k + 1];
                    float mag = (float) Math.sqrt(i * i + q * q);
                    float gated = mag > CW_THRESHOLD ? mag : 0f;
                    cwPhase += cwStep;
                    if (cwPhase > Math.PI) cwPhase -= 2 * Math.PI;
                    out[k] = clamp((float) (gated * Math.cos(cwPhase)));
                }
            }
            case NOISE -> {
                for (int k = 0; k < n; k++) out[k] = clamp(iq[2 * k] * 1.5f);
            }
        }
    }

    /*
     * private void mixSyntheticNoise(float[] out, int n) {
     * for (int k = 0; k < n; k++) {
     * out[k] = clamp(out[k] + (noiseRng.nextFloat() - 0.5f) * STATIC_NOISE_AMP);
     * }
     * }
     */

    /** Feed the raw post-squelch audio to the station ID decoder. */
    private void feedStationDecoder(float[] raw, int n) {
        if (stationTap.length < n) stationTap = new float[n];
        System.arraycopy(raw, 0, stationTap, 0, n);
        stationDecoder.feed(stationTap, n);
    }

    /** Most recently decoded station name, or empty past the freshness window. */
    public String lastStationName(long freshnessMs) {
        return stationDecoder.currentName(freshnessMs);
    }

    /** De-emphasis (FM) then the communications passband. */
    private void applyReceiveChain(float[] out, int n) {
        if (deemphasis) {
            for (int k = 0; k < n; k++) {
                deemphState += DEEMPH_ALPHA * (out[k] - deemphState);
                out[k] = deemphState;
            }
        }
        for (int k = 0; k < n; k++) {
            float s = out[k];
            for (Biquad b : audioChain) s = b.process(s);
            out[k] = clamp(s);
        }
    }

    private static Biquad[] buildAudioChain(Modulation m, float fs) {
        switch (m) {
            case WIDE_FM:
                return new Biquad[] { Biquad.highpass(fs, 50f, 0.707f), Biquad.lowpass(fs, 15_000f, 0.707f) };
            case CW:
                return new Biquad[] { Biquad.highpass(fs, 400f, 0.9f), Biquad.lowpass(fs, 1_000f, 0.9f) };
            case USB:
            case LSB:
                return new Biquad[] { Biquad.highpass(fs, 300f, 0.707f), Biquad.lowpass(fs, 2_700f, 0.707f),
                    Biquad.lowpass(fs, 2_700f, 0.707f) };
            default:
                return new Biquad[] { Biquad.highpass(fs, 300f, 0.707f), Biquad.lowpass(fs, 3_000f, 0.707f),
                    Biquad.lowpass(fs, 3_000f, 0.707f) };
        }
    }

    /**
     * Soft-clip drive per mode. Voice modes get a light drive for the
     * comms speech-processor grit; WideFM stays clean for broadcast
     * fidelity and CW is a pure tone that should not be clipped.
     */
    private static float clipDriveFor(Modulation m) {
        switch (m) {
            case WIDE_FM:
            case CW:
                return 0f;
            default:
                return 1.8f;
        }
    }

    /**
     * Transposed Direct Form II biquad, real input. Used for the audio
     * passband. Coefficients come from the RBJ Audio EQ Cookbook with a0
     * normalised to one. Mirrors the Rust dsp::iir::Biquad so the two
     * sides of the engine filter audio identically.
     */
    private static final class Biquad {

        private final float b0, b1, b2, a1, a2;
        private float z1, z2;

        private Biquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Biquad lowpass(float fs, float fc, float q) {
            double w0 = 2.0 * Math.PI * fc / fs;
            double cw = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            double b0 = (1.0 - cw) * 0.5;
            double b1 = 1.0 - cw;
            double b2 = (1.0 - cw) * 0.5;
            double a1 = -2.0 * cw;
            double a2 = 1.0 - alpha;
            return new Biquad(
                (float) (b0 / a0),
                (float) (b1 / a0),
                (float) (b2 / a0),
                (float) (a1 / a0),
                (float) (a2 / a0));
        }

        static Biquad highpass(float fs, float fc, float q) {
            double w0 = 2.0 * Math.PI * fc / fs;
            double cw = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            double b0 = (1.0 + cw) * 0.5;
            double b1 = -(1.0 + cw);
            double b2 = (1.0 + cw) * 0.5;
            double a1 = -2.0 * cw;
            double a2 = 1.0 - alpha;
            return new Biquad(
                (float) (b0 / a0),
                (float) (b1 / a0),
                (float) (b2 / a0),
                (float) (a1 / a0),
                (float) (a2 / a0));
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }

    private static float clamp(float x) {
        return x > 1f ? 1f : (x < -1f ? -1f : x);
    }

    public Modulation modulation() {
        return modulation;
    }

    public float sampleRateHz() {
        return sampleRateHz;
    }
}
