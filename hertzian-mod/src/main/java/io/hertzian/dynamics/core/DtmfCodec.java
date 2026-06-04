package io.hertzian.dynamics.core;

/**
 * Dual-tone multi-frequency (DTMF) codec, plus a sequential tone selcall
 * built on it. DTMF is the telephone keypad: each key sends a sum of one
 * row tone and one column tone, so a four by four grid of digits and
 * letters rides over an ordinary voice channel. Selcall (selective call)
 * uses the same tones as an address: a receiver stays quiet until it
 * hears its own digit string, the way a paging or fleet radio only wakes
 * for its number.
 *
 * <p>
 * Why it lives in {@code core}
 * Pure DSP, server-side, unit testable, the same as the other codecs. The
 * {@link Encoder} produces the keypad audio a transmitter modulates; the
 * {@link Decoder} recovers the pressed digits from demodulated audio with
 * a bank of eight Goertzel filters, two of eight tones present at a time.
 *
 * <p>
 * Detection
 * The decoder evaluates fixed-length blocks. In each block it runs the
 * four row and four column Goertzel bins, takes the strongest of each
 * group, and accepts a digit only when both clearly dominate the block
 * energy, which rejects noise and single-tone interference. A digit is
 * registered once on its rising edge and not repeated until a silent
 * block has passed, so a key held down keys one digit rather than a run.
 */
public final class DtmfCodec {

    public static final int SAMPLE_RATE = 48_000;
    public static final float[] ROW_HZ = { 697f, 770f, 852f, 941f };
    public static final float[] COL_HZ = { 1209f, 1336f, 1477f, 1633f };
    public static final char[][] KEYS = { { '1', '2', '3', 'A' }, { '4', '5', '6', 'B' }, { '7', '8', '9', 'C' },
        { '*', '0', '#', 'D' }, };

    private DtmfCodec() {}

    private static int[] keyToRowCol(char c) {
        for (int r = 0; r < 4; r++)
            for (int col = 0; col < 4; col++) if (KEYS[r][col] == c) return new int[] { r, col };
        return null;
    }

    /** Streaming encoder: each digit is a tone burst then a gap. */
    public static final class Encoder {

        private static final int TONE_SAMPLES = 4800; // 100 ms
        private static final int GAP_SAMPLES = 2400; // 50 ms
        private final java.util.ArrayDeque<Character> queue = new java.util.ArrayDeque<>();
        private char current = 0;
        private int remaining = 0;
        private boolean inGap = false;
        private double phR = 0.0, phC = 0.0;

        public boolean active() {
            return current != 0 || !queue.isEmpty();
        }

        public synchronized void queue(String digits) {
            if (digits == null) return;
            for (int i = 0; i < digits.length(); i++) {
                char c = Character.toUpperCase(digits.charAt(i));
                if (keyToRowCol(c) != null) queue.add(c);
            }
        }

        public synchronized void queueDigit(char c) {
            queue("" + c);
        }

        public synchronized void clear() {
            queue.clear();
            current = 0;
            remaining = 0;
            inGap = false;
        }

        public synchronized void fill(float[] frame) {
            for (int i = 0; i < frame.length; i++) {
                if (current == 0 && remaining == 0) {
                    Character c = queue.poll();
                    if (c == null) {
                        frame[i] = 0f;
                        continue;
                    }
                    current = c;
                    remaining = TONE_SAMPLES;
                    inGap = false;
                }
                if (current != 0) {
                    int[] rc = keyToRowCol(current);
                    double rStep = 2.0 * Math.PI * ROW_HZ[rc[0]] / SAMPLE_RATE;
                    double cStep = 2.0 * Math.PI * COL_HZ[rc[1]] / SAMPLE_RATE;
                    phR += rStep;
                    if (phR > Math.PI) phR -= 2 * Math.PI;
                    phC += cStep;
                    if (phC > Math.PI) phC -= 2 * Math.PI;
                    frame[i] = (float) (0.4 * (Math.sin(phR) + Math.sin(phC)));
                    if (--remaining <= 0) {
                        remaining = GAP_SAMPLES;
                        inGap = true;
                        current = 0;
                    }
                } else { // gap
                    frame[i] = 0f;
                    if (--remaining <= 0) remaining = 0;
                }
            }
        }
    }

    /** Block Goertzel decoder. */
    public static final class Decoder {

        private static final int BLOCK = 1024;
        private final float[] buf = new float[BLOCK];
        private int filled = 0;
        private char held = 0; // currently sounding digit
        private boolean gapSeen = true; // require a gap before a new digit
        private final StringBuilder digits = new StringBuilder();
        private char lastChar = 0;
        private boolean gate = false;

        public void feed(float[] audio, int n, float snrDb) {
            if (gate) {
                if (snrDb < 2f) gate = false;
            } else {
                if (snrDb > 6f) gate = true;
            }
            for (int k = 0; k < n; k++) {
                buf[filled++] = audio[k];
                if (filled >= BLOCK) {
                    process();
                    filled = 0;
                }
            }
        }

        private void process() {
            if (!gate) {
                held = 0;
                gapSeen = true;
                return;
            }
            float energy = 1e-9f;
            for (int i = 0; i < BLOCK; i++) energy += buf[i] * buf[i];

            int bestRow = strongest(ROW_HZ);
            int bestCol = strongest(COL_HZ);
            float rowP = goertzel(ROW_HZ[bestRow]);
            float colP = goertzel(COL_HZ[bestCol]);

            // Both tones must carry a clear share of the block energy.
            boolean present = rowP > 0.10f * energy && colP > 0.10f * energy;
            if (present) {
                char c = KEYS[bestRow][bestCol];
                if (gapSeen && c != 0) {
                    held = c;
                    gapSeen = false;
                    lastChar = c;
                    digits.append(c);
                    if (digits.length() > 64) digits.delete(0, digits.length() - 64);
                }
            } else {
                held = 0;
                gapSeen = true;
            }
        }

        private int strongest(float[] group) {
            int best = 0;
            float bestP = -1f;
            for (int i = 0; i < group.length; i++) {
                float p = goertzel(group[i]);
                if (p > bestP) {
                    bestP = p;
                    best = i;
                }
            }
            return best;
        }

        private float goertzel(float freq) {
            double coeff = 2.0 * Math.cos(2.0 * Math.PI * freq / SAMPLE_RATE);
            double s0, s1 = 0, s2 = 0;
            for (int i = 0; i < BLOCK; i++) {
                s0 = buf[i] + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            return (float) (s1 * s1 + s2 * s2 - coeff * s1 * s2);
        }

        public String digits() {
            return digits.toString();
        }

        public char lastChar() {
            return lastChar;
        }

        public char held() {
            return held;
        }

        public void clear() {
            digits.setLength(0);
            held = 0;
            lastChar = 0;
        }
    }
}
