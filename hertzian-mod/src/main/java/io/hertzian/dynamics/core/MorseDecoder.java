package io.hertzian.dynamics.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Adaptive Morse (CW) decoder. Consumes the complex baseband chunk a
 * receiver mixed for a CW signal and recovers plain text from the
 * on/off keying envelope.
 *
 * <p>
 * Why it lives in {@code core}
 * Like {@link ChunkDemodulator} and {@link StationIdDecoder}, the decode
 * runs on the server (the mixed chunk only exists there), so the class
 * carries no Minecraft dependency and can be unit tested in isolation.
 *
 * <p>
 * Slicer design against AGC overshoot
 * -------------------------------------
 * The receiver AGC sets a trap for any naive threshold. During a key-up
 * gap the AGC sees only the noise floor and ramps its gain toward
 * maximum. At the next key-down the carrier arrives while the gain is
 * still high, so the AGC output overshoots: a brief five to ten
 * millisecond spike, two to five times the steady carrier level, before
 * the AGC attack clamps it back. A threshold derived from an
 * instant-attack peak tracker latches that spike and becomes a fraction
 * of the spike rather than of the real carrier; with a slow release the
 * inflated threshold persists for a few hundred milliseconds, during
 * which the genuine elements no longer reach it and are dropped. The
 * failure mode is leading elements of characters eaten (a W keyed as
 * {@code .--} decoding as the bare E {@code .}) and, at higher speeds
 * where elements are shorter than the overshoot recovery, whole runs
 * decoding into noise.
 *
 * <p>
 * The slicer is built so the overshoot cannot poison the threshold:
 * <ul>
 * <li>The carrier reference {@code carrierRef} is tracked with a
 * <b>slow attack</b>, so a brief overshoot nudges it only slightly
 * and it converges to the sustained carrier present for the rest of
 * the element. Underestimating the carrier lowers the threshold,
 * which is safe (more sensitive); only overestimating eats elements,
 * and the slow attack prevents that.</li>
 * <li>{@code carrierRef} updates <b>only while the key is down</b> and
 * is held through key-up gaps, so it tracks the on-level rather
 * than sagging into the gaps.</li>
 * <li>It is seeded to the engine AGC target (0.5), the level a clean
 * carrier settles to post-AGC, so a valid threshold exists from the
 * very first element with no startup convergence delay.</li>
 * <li>The key state is sliced at a fraction of {@code carrierRef} with
 * hysteresis (down above 0.45, releases below 0.30).</li>
 * </ul>
 *
 * <p>
 * Silence gate
 * The carrier-present gate runs from the chunk's signal to noise ratio,
 * which is built from the mixer's pre-AGC physical signal power. On a CW
 * key-up the transmitter emits no carrier, so that power is essentially
 * zero and the ratio collapses regardless of how far the AGC has
 * amplified the noise; the gate therefore cleanly separates an element
 * from a gap and never mistakes AGC-lifted noise for a mark. A partially
 * keyed chunk still reads well above the gate, so the per-sample slicer
 * resolves the on/off edge inside it; this is what keeps the decode
 * working as the speed rises and an element shrinks toward one chunk.
 *
 * <p>
 * Timing state machine
 * Mark and space durations drive the classic Morse timing rules: a mark
 * is a dot below two dot units and a dash above; a space is an element
 * gap below two units, a letter gap from two to five, and a word gap
 * beyond five. The dot unit is estimated continuously from observed marks
 * so the decoder tracks the operator's speed (WPM) without being told it,
 * adapting faster over the first few marks so the opening word is read at
 * close to the right speed. Unknown symbols decode to '?' so a noisy copy
 * stays visible rather than silently vanishing.
 */
public final class MorseDecoder {

    private static final Map<String, Character> TABLE = new HashMap<>();
    static {
        put("A", ".-");
        put("B", "-...");
        put("C", "-.-.");
        put("D", "-..");
        put("E", ".");
        put("F", "..-.");
        put("G", "--.");
        put("H", "....");
        put("I", "..");
        put("J", ".---");
        put("K", "-.-");
        put("L", ".-..");
        put("M", "--");
        put("N", "-.");
        put("O", "---");
        put("P", ".--.");
        put("Q", "--.-");
        put("R", ".-.");
        put("S", "...");
        put("T", "-");
        put("U", "..-");
        put("V", "...-");
        put("W", ".--");
        put("X", "-..-");
        put("Y", "-.--");
        put("Z", "--..");
        put("0", "-----");
        put("1", ".----");
        put("2", "..---");
        put("3", "...--");
        put("4", "....-");
        put("5", ".....");
        put("6", "-....");
        put("7", "--...");
        put("8", "---..");
        put("9", "----.");
        put(".", ".-.-.-");
        put(",", "--..--");
        put("?", "..--..");
        put("/", "-..-.");
        put("=", "-...-");
        put("+", ".-.-.");
        put("-", "-....-");
        put(":", "---...");
        put("@", ".--.-.");
    }

    private static void put(String ch, String code) {
        TABLE.put(code, ch.charAt(0));
    }

    /** Maximum characters kept in the rolling output buffer. */
    private static final int MAX_TEXT = 4096;

    private final float sampleRateHz;

    // Envelope slicing.
    private float env = 0f;
    private float carrierRef = REF_SEED;

    /** Light denoise on the magnitude, tau about 0.25 ms at 48 kHz. */
    private static final float ENV_COEFF = 0.08f;

    /** Engine AGC target; a clean carrier settles here post-AGC. */
    private static final float REF_SEED = 0.5f;
    private static final float REF_MIN = 0.06f;
    private static final float REF_MAX = 2.0f;

    /**
     * Reference attack while keyed and env above the reference. Slow on
     * purpose: a brief AGC overshoot moves it only slightly, so it
     * converges to the sustained carrier, not the spike. tau about 40 ms.
     */
    private static final float REF_ATTACK = 0.0006f;
    /** Reference settle while keyed and env below the reference. */
    private static final float REF_ON_DECAY = 0.0010f;
    /**
     * Reference drift while unkeyed, essentially a hold. A tiny non-zero
     * value lets a long-vanished signal stop pinning a stale reference;
     * during true silence the gate forces the key up anyway, so this
     * drift cannot create marks.
     */
    private static final float REF_OFF_DRIFT = 0.000008f;

    /** Hysteresis fractions of the tracked reference. */
    private static final float ON_FRAC = 0.45f;
    private static final float OFF_FRAC = 0.30f;

    // SNR gate with hysteresis (carrier present detection).
    private boolean gateOpen = false;
    private static final float GATE_ON_DB = 6f;
    private static final float GATE_OFF_DB = 2f;

    // Keying state machine.
    private boolean keyDown = false;
    private long markSamples = 0;
    private long spaceSamples = 0;
    private boolean letterFlushed = false;
    private boolean wordFlushed = false;

    // Adaptive dot length, in samples, clamped to a sane WPM range.
    private double dotSamples;
    private final double minDotSamples;
    private final double maxDotSamples;
    private int markCount = 0;
    private static final double MIN_ELEMENT_FRAC = 0.012; // 12 ms debounce.

    private final StringBuilder symbol = new StringBuilder(8);
    private final StringBuilder text = new StringBuilder();

    private float lastLevel = 0f;

    public MorseDecoder(float sampleRateHz) {
        this.sampleRateHz = sampleRateHz;
        // Default 18 WPM dot, bounds 5..40 WPM.
        this.dotSamples = sampleRateHz * (1200.0 / 18.0) / 1000.0;
        this.minDotSamples = sampleRateHz * (1200.0 / 40.0) / 1000.0;
        this.maxDotSamples = sampleRateHz * (1200.0 / 5.0) / 1000.0;
    }

    /**
     * Feed one mixed chunk. {@code iq} is interleaved I/Q of length
     * {@code 2 * sampleCount}; {@code snrDb} is the chunk's pre-AGC signal
     * to noise ratio, used for the carrier-present gate.
     */
    public void feedIq(float[] iq, int sampleCount, float snrDb) {
        if (gateOpen) {
            if (snrDb < GATE_OFF_DB) gateOpen = false;
        } else {
            if (snrDb > GATE_ON_DB) gateOpen = true;
        }

        long downCount = 0;
        for (int k = 0; k < sampleCount; k++) {
            float i = iq[2 * k];
            float q = iq[2 * k + 1];
            float m = (float) Math.sqrt(i * i + q * q);

            // Light smoothing: takes per-sample jitter off the envelope
            // without blurring the keying edges of a 30 ms-plus element.
            env += (m - env) * ENV_COEFF;

            boolean level;
            if (!gateOpen) {
                level = false;
            } else {
                float onThr = carrierRef * ON_FRAC;
                float offThr = carrierRef * OFF_FRAC;
                level = keyDown ? (env > offThr) : (env > onThr);
            }

            // Update the carrier reference. While keyed it converges to
            // the sustained carrier: slow attack upward so an AGC
            // overshoot does not latch, a slightly faster settle downward
            // so the reference relaxes off the overshoot. While unkeyed it
            // is held (only a tiny drift), so the threshold from the
            // previous element survives the gap intact.
            if (level) {
                if (env > carrierRef) carrierRef += (env - carrierRef) * REF_ATTACK;
                else carrierRef += (env - carrierRef) * REF_ON_DECAY;
            } else {
                carrierRef += (env - carrierRef) * REF_OFF_DRIFT;
            }
            if (carrierRef < REF_MIN) carrierRef = REF_MIN;
            if (carrierRef > REF_MAX) carrierRef = REF_MAX;

            if (level) downCount++;
            processLevel(level);
        }
        lastLevel = sampleCount > 0 ? (float) downCount / sampleCount : 0f;
    }

    private void processLevel(boolean down) {
        if (down == keyDown) {
            if (down) {
                markSamples++;
            } else {
                spaceSamples++;
                checkSpaceGaps();
            }
            return;
        }
        // Transition.
        if (keyDown) {
            // Mark ended: classify it unless it is a debounce-length glitch.
            double minElement = MIN_ELEMENT_FRAC * sampleRateHz;
            if (markSamples >= minElement) endMark(markSamples);
            spaceSamples = 0;
            letterFlushed = false;
            wordFlushed = false;
        } else {
            // Space ended, a new mark begins.
            markSamples = 0;
        }
        keyDown = down;
        if (down) markSamples = 1;
        else spaceSamples = 1;
    }

    /** Append a dot or dash to the current symbol and adapt the dot unit. */
    private void endMark(long lengthSamples) {
        double dashBoundary = 2.0 * dotSamples;
        // Adapt fast over the first few marks so the opening word is read
        // near the true speed, then settle to a gentle rate that tracks
        // operator drift without being thrown by a single odd element.
        float rate = markCount < 4 ? 0.40f : 0.12f;
        markCount++;
        if (lengthSamples < dashBoundary) {
            symbol.append('.');
            dotSamples += (lengthSamples - dotSamples) * rate;
        } else {
            symbol.append('-');
            dotSamples += (lengthSamples / 3.0 - dotSamples) * rate;
        }
        if (dotSamples < minDotSamples) dotSamples = minDotSamples;
        if (dotSamples > maxDotSamples) dotSamples = maxDotSamples;
    }

    /** While in a space, flush a letter and then a word once each. */
    private void checkSpaceGaps() {
        double letterGap = 2.0 * dotSamples;
        double wordGap = 5.0 * dotSamples;
        if (!letterFlushed && symbol.length() > 0 && spaceSamples >= letterGap) {
            flushSymbol();
            letterFlushed = true;
        }
        if (!wordFlushed && spaceSamples >= wordGap) {
            appendChar(' ');
            wordFlushed = true;
        }
    }

    private void flushSymbol() {
        if (symbol.length() == 0) return;
        Character c = TABLE.get(symbol.toString());
        appendChar(c != null ? c : '?');
        symbol.setLength(0);
    }

    private void appendChar(char c) {
        text.append(c);
        if (text.length() > MAX_TEXT) {
            text.delete(0, text.length() - MAX_TEXT);
        }
    }

    /** Decoded text accumulated so far, capped. */
    public String text() {
        return text.toString();
    }

    /** Tail of the decoded text, at most {@code n} characters. */
    public String tail(int n) {
        int len = text.length();
        return len <= n ? text.toString() : text.substring(len - n);
    }

    /** Estimated sending speed in words per minute. */
    public float wpm() {
        double dotMs = dotSamples / sampleRateHz * 1000.0;
        if (dotMs <= 0) return 0f;
        return (float) (1200.0 / dotMs);
    }

    /** Fraction of the last chunk the key was down, 0..1, for the scope. */
    public float level() {
        return lastLevel;
    }

    public boolean keyDown() {
        return keyDown;
    }

    /** Clear the decoded text and reset the slicer and timing state. */
    public void clear() {
        text.setLength(0);
        symbol.setLength(0);
        keyDown = false;
        markSamples = 0;
        spaceSamples = 0;
        env = 0f;
        carrierRef = REF_SEED;
        markCount = 0;
        letterFlushed = false;
        wordFlushed = false;
    }
}
