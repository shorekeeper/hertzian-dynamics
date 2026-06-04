package io.hertzian.dynamics.core;

/**
 * Radioteletype (RTTY) codec: continuous-phase audio FSK carrying the
 * Baudot (ITA2) 5-bit alphabet at the classic 45.45 baud, with the
 * standard 170 Hz mark/space shift. It is the data-mode cousin of the
 * CW telegraph: where Morse keys a single carrier on and off, RTTY
 * shifts a subcarrier between two tones, so a clean text channel runs
 * over a single-sideband voice link.
 *
 * <p>
 * Why it lives in {@code core}
 * Like {@link MorseDecoder} and {@link StationIdDecoder} it is pure DSP
 * with no Minecraft dependency, runs server-side (the mixed chunk lives
 * there), and is unit testable on its own. The {@link Encoder} fills the
 * tone audio a transmitter modulates; the {@link Decoder} recovers text
 * from the demodulated audio.
 *
 * <p>
 * Tones and timing
 * Mark is the higher 2125 Hz tone, space the lower 2295 Hz, the 170 Hz
 * shift sitting comfortably inside a 2.7 kHz voice passband. Each
 * character is one start bit (space), five data bits (least significant
 * first), and a mark stop, the asynchronous frame every mechanical
 * teleprinter used. ITA2 has only 32 codes, so letters and figures share
 * the table and two shift codes (LTRS, FIGS) switch between them; the
 * encoder inserts a shift only when the case actually changes, and the
 * decoder tracks the current shift the same way.
 *
 * <p>
 * Demodulation
 * The decoder runs two quadrature down-converters, one at each tone, each
 * followed by a one-pole magnitude estimate; whichever is larger is the
 * instantaneous bit. Because the comparison is between two magnitudes it
 * does not care about the absolute audio level the AGC settled on. A
 * UART-style clock then finds the start-bit edge and samples the five
 * data bits at their centres, which the no-drift engine clock makes exact
 * once the start edge is found.
 */
public final class RttyCodec {

    public static final int SAMPLE_RATE = 48_000;
    public static final float MARK_HZ = 2125f;
    public static final float SPACE_HZ = 2295f;
    public static final float BAUD = 45.45f;
    public static final int SAMPLES_PER_BIT = Math.round(SAMPLE_RATE / BAUD); // ~1056

    private RttyCodec() {}

    // ITA2 tables, value 0..31. Code 27 is FIGS shift, 31 is LTRS shift.
    private static final char[] LTRS = { 0, 'E', '\n', 'A', ' ', 'S', 'I', 'U', '\r', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
        'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q', 'O', 'B', 'G', 0 /* FIGS */, 'M', 'X', 'V', 0 /* LTRS */
    };
    private static final char[] FIGS = { 0, '3', '\n', '-', ' ', '\'', '8', '7', '\r', '$', '4', 7 /* BEL */, ',', '!',
        ':', '(', '5', '"', ')', '2', '#', '6', '0', '1', '9', '?', '&', 0 /* FIGS */, '.', '/', ';', 0 /* LTRS */
    };
    private static final int FIGS_CODE = 27;
    private static final int LTRS_CODE = 31;

    /** Lookup: char to {code, isFigs}, or null if unsupported. */
    private static int[] lookup(char c) {
        char up = Character.toUpperCase(c);
        for (int v = 0; v < 32; v++) {
            if (v == FIGS_CODE || v == LTRS_CODE) continue;
            if (LTRS[v] == up) return new int[] { v, 0 };
        }
        for (int v = 0; v < 32; v++) {
            if (v == FIGS_CODE || v == LTRS_CODE) continue;
            if (FIGS[v] == up) return new int[] { v, 1 };
        }
        return null;
    }

    /** Streaming FSK modulator. Emits tone audio while a message plays out. */
    public static final class Encoder {

        // Pending bits queued from text. Each entry is mark(true)/space(false).
        private final java.util.ArrayDeque<Boolean> bits = new java.util.ArrayDeque<>();
        private int sampleInBit = 0;
        private boolean currentBit = true;
        private boolean haveCurrent = false;
        private double phase = 0.0;
        private boolean figsShift = false;

        public boolean active() {
            return haveCurrent || !bits.isEmpty();
        }

        /** Encode text into the bit queue, appended after anything pending. */
        public synchronized void queueText(String text) {
            if (text == null) return;
            for (int i = 0; i < text.length(); i++) {
                int[] r = lookup(text.charAt(i));
                if (r == null) continue;
                boolean wantFigs = r[1] == 1;
                if (wantFigs != figsShift) {
                    pushChar(wantFigs ? FIGS_CODE : LTRS_CODE);
                    figsShift = wantFigs;
                }
                pushChar(r[0]);
            }
        }

        private void pushChar(int value) {
            bits.add(Boolean.FALSE); // start bit (space)
            for (int b = 0; b < 5; b++) {
                bits.add(((value >> b) & 1) != 0); // data, LSB first; 1 = mark
            }
            bits.add(Boolean.TRUE); // stop bit (mark)
            bits.add(Boolean.TRUE); // 1.5 stop, rounded to a full bit
        }

        public synchronized void clear() {
            bits.clear();
            haveCurrent = false;
            sampleInBit = 0;
        }

        /** Fill one frame with FSK tone audio; silence when idle so TX squelches. */
        public synchronized void fill(float[] frame) {
            double markStep = 2.0 * Math.PI * MARK_HZ / SAMPLE_RATE;
            double spaceStep = 2.0 * Math.PI * SPACE_HZ / SAMPLE_RATE;
            for (int i = 0; i < frame.length; i++) {
                if (!haveCurrent) {
                    Boolean b = bits.poll();
                    if (b == null) {
                        frame[i] = 0f;
                        continue;
                    }
                    currentBit = b;
                    haveCurrent = true;
                    sampleInBit = 0;
                }
                phase += currentBit ? markStep : spaceStep;
                if (phase > Math.PI) phase -= 2.0 * Math.PI;
                frame[i] = (float) (0.6 * Math.sin(phase));
                if (++sampleInBit >= SAMPLES_PER_BIT) haveCurrent = false;
            }
        }
    }

    /** Streaming FSK demodulator and UART. */
    public static final class Decoder {

        private double phMark = 0.0, phSpace = 0.0;
        private float mI = 0, mQ = 0, sI = 0, sQ = 0;
        private static final float LP = 0.002f;

        private boolean prevBit = true;
        private int state = 0; // 0 idle, 1 data, 2 stop
        private int timer = 0;
        private int idx = 0, value = 0;
        private boolean figsShift = false;

        private final StringBuilder text = new StringBuilder();
        private static final int MAX_TEXT = 4096;
        private float lastLevel = 0f;

        // Carrier-present gate from chunk SNR.
        private boolean gate = false;

        public void feed(float[] audio, int n, float snrDb) {
            if (gate) {
                if (snrDb < 2f) gate = false;
            } else {
                if (snrDb > 6f) gate = true;
            }

            double markStep = 2.0 * Math.PI * MARK_HZ / SAMPLE_RATE;
            double spaceStep = 2.0 * Math.PI * SPACE_HZ / SAMPLE_RATE;
            int markCount = 0;
            for (int k = 0; k < n; k++) {
                float a = audio[k];
                float mc = (float) Math.cos(phMark), ms = (float) Math.sin(phMark);
                float sc = (float) Math.cos(phSpace), ss = (float) Math.sin(phSpace);
                phMark += markStep;
                if (phMark > Math.PI) phMark -= 2 * Math.PI;
                phSpace += spaceStep;
                if (phSpace > Math.PI) phSpace -= 2 * Math.PI;

                mI += (a * mc - mI) * LP;
                mQ += (-a * ms - mQ) * LP;
                sI += (a * sc - sI) * LP;
                sQ += (-a * ss - sQ) * LP;
                float markMag = mI * mI + mQ * mQ;
                float spaceMag = sI * sI + sQ * sQ;

                boolean bit = gate ? (markMag >= spaceMag) : true;
                if (bit) markCount++;
                stepUart(bit);
                prevBit = bit;
            }
            lastLevel = n > 0 ? 1f - (float) markCount / n : 0f;
        }

        private void stepUart(boolean bit) {
            switch (state) {
                case 0:
                    if (prevBit && !bit) {
                        state = 1;
                        timer = Math.round(SAMPLES_PER_BIT * 1.5f);
                        idx = 0;
                        value = 0;
                    }
                    break;
                case 1:
                    if (--timer <= 0) {
                        if (bit) value |= (1 << idx);
                        idx++;
                        if (idx >= 5) {
                            state = 2;
                            timer = SAMPLES_PER_BIT;
                        } else timer = SAMPLES_PER_BIT;
                    }
                    break;
                case 2:
                    if (--timer <= 0) {
                        decode(value);
                        state = 0;
                    }
                    break;
                default:
                    state = 0;
            }
        }

        private void decode(int v) {
            v &= 31;
            if (v == LTRS_CODE) {
                figsShift = false;
                return;
            }
            if (v == FIGS_CODE) {
                figsShift = true;
                return;
            }
            char c = figsShift ? FIGS[v] : LTRS[v];
            if (c == 0 || c == 7) return; // null or BEL
            text.append(c);
            if (text.length() > MAX_TEXT) text.delete(0, text.length() - MAX_TEXT);
        }

        public String tail(int n) {
            int len = text.length();
            return len <= n ? text.toString() : text.substring(len - n);
        }

        public float level() {
            return lastLevel;
        }

        public void clear() {
            text.setLength(0);
            state = 0;
            figsShift = false;
        }
    }
}
