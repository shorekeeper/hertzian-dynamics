package io.hertzian.dynamics.core;

/**
 * Slow-scan television (SSTV) codec: it sends a still picture as sound and
 * recovers it on the far end, the way amateur operators trade images over
 * a voice channel. A subcarrier sweeps in frequency to paint each line:
 * low for dark, high for bright, with a sync pulse at the start of every
 * line and the three colour scans sent one after another.
 *
 * <p>
 * Compact custom mode (read this)
 * This is not bit-compatible with a real SSTV mode such as Martin or
 * Scottie. Those run one to two minutes per frame, far too long for a
 * block GUI, so this codec uses a compact 96 by 96 sequential-RGB mode
 * with its own timing, about seven seconds a frame. The technique is the
 * real one (a sync pulse, then frequency-scanned colour lines, 1500 Hz
 * black to 2300 Hz white) so it behaves like SSTV: it locks on the sync,
 * paints top to bottom, and tears or speckles as the signal to noise
 * ratio falls. The encoder and decoder share the timing constants below,
 * so they only interoperate with each other.
 *
 * <p>
 * No-drift advantage
 * The engine runs transmit and receive at the same 48 kHz with no clock
 * drift, only an unknown start offset, so the decoder locks once on the
 * first sync pulse and then free-runs the whole frame by counting
 * samples. There is no per-line clock recovery to jitter, which is what
 * lets a single-shot decoder produce a clean picture on a good link.
 *
 * <p>
 * Frequency recovery
 * The decoder demodulates the subcarrier with a quadrature discriminator:
 * it mixes the audio against a local oscillator at the band centre, low
 * passes, and reads the instantaneous frequency from the phase change
 * between samples. Because the reading is a phase derivative it is
 * independent of the audio amplitude the AGC settled on, which matters
 * because the scan must map frequency, not level, to brightness.
 */
public final class SstvCodec {

    public static final int SAMPLE_RATE = 48_000;
    public static final int W = 96;
    public static final int H = 96;

    public static final float BLACK_HZ = 1500f;
    public static final float WHITE_HZ = 2300f;
    public static final float SYNC_HZ = 1200f;
    public static final float PORCH_HZ = 1500f;
    public static final float LEADER_HZ = 1900f;

    static final int LEADER_S = 9600; // 0.2 s leader
    static final int SYNC_S = 300; // 6.25 ms sync pulse
    static final int PORCH_S = 48; // 1 ms separator
    static final int PIX_S = 10; // samples per pixel per colour
    static final int COLOR_S = PORCH_S + W * PIX_S; // 1008
    static final int LINE_S = SYNC_S + 3 * COLOR_S; // 3324
    public static final int FRAME_S = LEADER_S + H * LINE_S;

    private SstvCodec() {}

    /**
     * Built-in procedural picture kinds the SSTV station can transmit
     * without any image being uploaded from the client. Each kind
     * generates its 96 by 96 RGB pixels deterministically on demand, so
     * the server resolves a queued pattern frame to pixels with a single
     * cheap call and the client can render an identical thumbnail locally
     * for the queue preview. This is the core of the no-upload design:
     * a pattern frame travels as one enum ordinal, never as pixel data.
     */
    public enum PatternKind {

        BARS,
        GRADIENT,
        CHECKER,
        CROSSHATCH;

        /** Generate the H by W RGB image for this kind, as 0xRRGGBB. */
        public int[][] generate() {
            switch (this) {
                case GRADIENT:
                    return gradient();
                case CHECKER:
                    return checker();
                case CROSSHATCH:
                    return crosshatch();
                case BARS:
                default:
                    return bars();
            }
        }

        private static int[][] bars() {
            int[][] img = new int[H][W];
            int[] cols = { 0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000 };
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (y < H * 2 / 3) {
                        img[y][x] = cols[x * cols.length / W];
                    } else {
                        int g = x * 255 / (W - 1);
                        img[y][x] = (g << 16) | (g << 8) | g;
                    }
                }
            }
            return img;
        }

        private static int[][] gradient() {
            int[][] img = new int[H][W];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int r = x * 255 / (W - 1);
                    int g = y * 255 / (H - 1);
                    int b = 255 - r;
                    img[y][x] = (r << 16) | (g << 8) | b;
                }
            }
            return img;
        }

        private static int[][] checker() {
            int[][] img = new int[H][W];
            int size = 12;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    boolean on = (((x / size) + (y / size)) & 1) == 0;
                    img[y][x] = on ? 0xF0F0F0 : 0x202020;
                }
            }
            return img;
        }

        private static int[][] crosshatch() {
            int[][] img = new int[H][W];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int base = 0x004858;
                    if (x % 8 == 0 || y % 8 == 0) {
                        base = 0xC8FFC8;
                    }
                    if (x == W / 2 || y == H / 2) {
                        base = 0xFF4040;
                    }
                    img[y][x] = base;
                }
            }
            return img;
        }
    }

    /**
     * Procedural test pattern, kept for callers that still ask for "the"
     * test image. Equivalent to {@code PatternKind.BARS.generate()}.
     */
    public static int[][] testPattern() {
        return PatternKind.BARS.generate();
    }

    /** Streaming encoder. Walks a 96x96 RGB image into subcarrier audio. */
    public static final class Encoder {

        private int[][] image;
        private long pos = -1; // -1 idle
        private double phase = 0.0;

        public boolean active() {
            return pos >= 0;
        }

        public long position() {
            return Math.max(0, pos);
        }

        public float progress() {
            return pos < 0 ? 0f : Math.min(1f, (float) pos / FRAME_S);
        }

        public synchronized void start(int[][] rgb) {
            this.image = rgb;
            this.pos = 0;
        }

        public synchronized void clear() {
            pos = -1;
            image = null;
        }

        public synchronized void fill(float[] frame) {
            for (int i = 0; i < frame.length; i++) {
                if (pos < 0 || image == null) {
                    frame[i] = 0f;
                    continue;
                }
                float f = freqAt(pos);
                if (f < 0) {
                    pos = -1;
                    frame[i] = 0f;
                    continue;
                }
                phase += 2.0 * Math.PI * f / SAMPLE_RATE;
                if (phase > Math.PI) phase -= 2.0 * Math.PI;
                frame[i] = (float) (0.6 * Math.sin(phase));
                pos++;
            }
        }

        private float freqAt(long p) {
            if (p < LEADER_S) return LEADER_HZ;
            long q = p - LEADER_S;
            int line = (int) (q / LINE_S);
            if (line >= H) return -1f;
            int o = (int) (q % LINE_S);
            if (o < SYNC_S) return SYNC_HZ;
            o -= SYNC_S;
            int color = o / COLOR_S; // 0 R, 1 G, 2 B
            int co = o % COLOR_S;
            if (co < PORCH_S) return PORCH_HZ;
            int pixel = (co - PORCH_S) / PIX_S;
            int rgb = image[line][Math.min(W - 1, pixel)];
            int v = (rgb >> (16 - color * 8)) & 0xFF;
            return BLACK_HZ + v / 255f * (WHITE_HZ - BLACK_HZ);
        }
    }

    /**
     * Streaming decoder. Locks on the first sync pulse then free-runs the
     * whole frame by counting samples, relying on the no-drift engine
     * clock so there is no per-line clock recovery to jitter.
     *
     * <p>
     * Discriminator
     * The subcarrier is demodulated by a quadrature discriminator: the
     * audio is mixed against a local oscillator at the band centre, low
     * passed, and the instantaneous frequency is read from the phase
     * change between consecutive samples. The lowpass is the part that
     * matters most. Mixing a tone at frequency {@code f} against the
     * centre {@code FC} produces a difference component at {@code f - FC}
     * (the wanted signal, at most about +/-550 Hz across the 1200..2300 Hz
     * SSTV band) and a sum image at {@code f + FC} (around 3 to 4 kHz,
     * unwanted). An earlier version used a single one-pole lowpass with a
     * coefficient of 0.3, whose cutoff sat near 2.3 kHz and left the sum
     * image only 3 dB down; that image rode straight into the phase
     * derivative and corrupted the frequency reading badly enough that the
     * 1200 Hz sync pulse never registered as a clean low-frequency run, so
     * the decoder never locked. The lowpass is now two cascaded one-poles
     * at roughly 760 Hz, which passes the +/-550 Hz tone band while
     * pushing the sum image more than 20 dB down, so the frequency reading
     * is clean enough to find the sync.
     *
     * <p>
     * Gate and lock
     * The carrier-present gate is driven by the chunk signal to noise
     * ratio with a low threshold, because a swept SSTV signal spends time
     * across the whole band and a strict gate would chatter. The sync lock
     * looks for a sustained run of sync-low frequency and tolerates a
     * handful of stray high samples inside the run so filter settling at
     * the leader-to-sync transition does not break it.
     */
    public static final class Decoder {

        private static final float FC = 1750f;

        // Two cascaded one-pole lowpasses at about 760 Hz. The single
        // pole the earlier version used was far too wide and let the sum
        // image corrupt the discriminator; see the class note.
        private static final float LP = 0.10f;

        private double phLo = 0.0;
        private float i1 = 0, q1 = 0, i2 = 0, q2 = 0;
        private float prevI = 0, prevQ = 0;

        private boolean locked = false;
        private long basePos = 0; // absolute index of the locked line-0 sync start
        private long absIndex = 0;
        private int lowRun = 0; // run length of sync-low frequency
        private int highStreak = 0; // stray high samples tolerated inside a run
        private boolean gate = false;

        // Current line accumulation.
        private final float[][] sum = new float[3][W];
        private final int[][] cnt = new int[3][W];
        private int currentLine = -1;

        // Output: latest completed line index and its RGB bytes.
        private int newLineIndex = -1;
        private byte[] newLineRgb = null;

        /**
         * Running count of frames the decoder has locked onto. Incremented
         * once each time a fresh sync lock is acquired, so consecutive
         * frames in a multi-frame transmission get distinct numbers and the
         * line packets can route each line to the right received-frame
         * buffer on the client. Not reset by {@link #reset()}, which fires
         * between frames, so the numbering is monotonic across the session.
         */
        private int frameSeq = 0;

        public void feed(float[] audio, int n, float snrDb) {
            // Low thresholds: a swept tone signal does not hold a steady
            // level, so a strict gate would flap on and off mid-frame.
            if (gate) {
                if (snrDb < 1f) {
                    gate = false;
                    reset();
                }
            } else {
                if (snrDb > 4f) {
                    gate = true;
                }
            }

            double loStep = 2.0 * Math.PI * FC / SAMPLE_RATE;
            for (int k = 0; k < n; k++) {
                float a = gate ? audio[k] : 0f;
                float c = (float) Math.cos(phLo);
                float s = (float) Math.sin(phLo);
                phLo += loStep;
                if (phLo > Math.PI) {
                    phLo -= 2.0 * Math.PI;
                }

                // Mix to baseband, then two cascaded one-pole lowpasses.
                float mi = a * c;
                float mq = -a * s;
                i1 += (mi - i1) * LP;
                q1 += (mq - q1) * LP;
                i2 += (i1 - i2) * LP;
                q2 += (q1 - q2) * LP;

                // Phase derivative of the baseband, amplitude independent.
                float cross = prevI * q2 - prevQ * i2;
                float dot = prevI * i2 + prevQ * q2;
                float dphi = (float) Math.atan2(cross, dot);
                prevI = i2;
                prevQ = q2;
                float freq = FC + dphi * SAMPLE_RATE / (2f * (float) Math.PI);

                if (gate) {
                    sample(freq);
                }
                absIndex++;
            }
        }

        private void sample(float freq) {
            if (!locked) {
                // Look for a sustained sync-low run. A few stray high
                // samples inside the run are tolerated so filter settling
                // at the 1900 to 1200 Hz step does not reset the count.
                if (freq < 1380f) {
                    if (lowRun == 0) {
                        basePos = absIndex;
                    }
                    lowRun++;
                    highStreak = 0;
                    if (lowRun >= SYNC_S - 60) {
                        locked = true;
                        currentLine = -1;
                        if (lowRun >= SYNC_S - 60) {
                            locked = true;
                            currentLine = -1;
                            frameSeq++; // new frame number on every fresh lock
                        }
                    }
                } else {
                    if (lowRun > 0 && ++highStreak <= 8) {
                        lowRun++;
                    } else {
                        lowRun = 0;
                        highStreak = 0;
                    }
                }
                return;
            }

            long p = absIndex - basePos;
            if (p < 0) {
                return;
            }
            int line = (int) (p / LINE_S);
            if (line >= H) {
                reset();
                return;
            }
            int o = (int) (p % LINE_S);

            if (line != currentLine) {
                if (currentLine >= 0) {
                    finishLine(currentLine);
                }
                currentLine = line;
            }
            if (o < SYNC_S) {
                return;
            }
            o -= SYNC_S;
            int color = o / COLOR_S;
            int co = o % COLOR_S;
            if (co < PORCH_S) {
                return;
            }
            int pixel = (co - PORCH_S) / PIX_S;
            if (pixel < 0 || pixel >= W || color < 0 || color > 2) {
                return;
            }
            // Average the middle of each pixel window, skipping the edges
            // where the discriminator is still settling on the new tone.
            int within = (co - PORCH_S) % PIX_S;
            if (within >= 2 && within <= PIX_S - 2) {
                sum[color][pixel] += freq;
                cnt[color][pixel]++;
            }
        }

        private void finishLine(int line) {
            byte[] rgb = new byte[W * 3];
            for (int x = 0; x < W; x++) {
                for (int c = 0; c < 3; c++) {
                    float f = cnt[c][x] > 0 ? sum[c][x] / cnt[c][x] : BLACK_HZ;
                    int v = Math.round((f - BLACK_HZ) / (WHITE_HZ - BLACK_HZ) * 255f);
                    if (v < 0) {
                        v = 0;
                    } else if (v > 255) {
                        v = 255;
                    }
                    rgb[x * 3 + c] = (byte) v;
                    sum[c][x] = 0;
                    cnt[c][x] = 0;
                }
            }
            newLineIndex = line;
            newLineRgb = rgb;
        }

        private void reset() {
            locked = false;
            lowRun = 0;
            highStreak = 0;
            currentLine = -1;
            i1 = q1 = i2 = q2 = 0;
            prevI = prevQ = 0;
            for (int c = 0; c < 3; c++) {
                for (int x = 0; x < W; x++) {
                    sum[c][x] = 0;
                    cnt[c][x] = 0;
                }
            }
        }

        /** Poll a freshly completed line, or null. Cleared after the call. */
        public synchronized int[] pollLine(byte[] out) {
            if (newLineRgb == null) {
                return null;
            }
            System.arraycopy(newLineRgb, 0, out, 0, Math.min(out.length, newLineRgb.length));
            int[] result = { newLineIndex };
            newLineRgb = null;
            newLineIndex = -1;
            return result;
        }

        public boolean locked() {
            return locked;
        }

        public void clearLock() {
            reset();
        }

        /** Frame number of the frame currently being decoded. */
        public int frameSeq() {
            return frameSeq;
        }
    }
}
