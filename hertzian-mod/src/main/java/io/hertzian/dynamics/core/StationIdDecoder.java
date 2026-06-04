package io.hertzian.dynamics.core;

/**
 * Two-tone FSK decoder for the station identifier, fed the raw
 * demodulated audio. It runs two bit-aligned Goertzel slicers offset by
 * half a bit, so a frame is recovered whichever sample phase the
 * transmitter's bit boundaries happen to fall on; the engine has no
 * clock drift between transmit and receive (both run at 48 kHz), only a
 * fixed unknown offset, which two phases plus the sync-word search fully
 * resolve.
 *
 * <p>
 * Each recovered bit feeds a rolling history; the history is scanned
 * for the preamble and sync word with a small mismatch tolerance, then
 * the length, name and CRC are read. Only a CRC-clean frame updates the
 * name, so noise produces no name rather than a corrupt one. A
 * freshness timeout lets the caller treat a name as lost once the
 * signal stops decoding.
 */
public final class StationIdDecoder {

    private static final int HIST = StationId.maxFrameBits() * 2;

    private final Goertzel[] mark = { new Goertzel(StationId.MARK_HZ), new Goertzel(StationId.MARK_HZ) };
    private final Goertzel[] space = { new Goertzel(StationId.SPACE_HZ), new Goertzel(StationId.SPACE_HZ) };
    private final int[] counter = new int[2];
    private final boolean[][] hist = new boolean[2][HIST];
    private final int[] histLen = new int[2];

    private String lastName = "";
    private long lastDecodeMs = Long.MIN_VALUE;

    public StationIdDecoder() {
        counter[0] = 0;
        counter[1] = StationId.SAMPLES_PER_BIT / 2;
    }

    public void feed(float[] audio, int n) {
        for (int i = 0; i < n; i++) {
            float x = audio[i];
            for (int p = 0; p < 2; p++) {
                mark[p].push(x);
                space[p].push(x);
                if (++counter[p] >= StationId.SAMPLES_PER_BIT) {
                    boolean bit = mark[p].magnitude() > space[p].magnitude();
                    mark[p].reset();
                    space[p].reset();
                    counter[p] = 0;
                    pushBit(p, bit);
                    tryDecode(p);
                }
            }
        }
    }

    private void pushBit(int p, boolean bit) {
        boolean[] h = hist[p];
        System.arraycopy(h, 1, h, 0, HIST - 1);
        h[HIST - 1] = bit;
        if (histLen[p] < HIST) histLen[p]++;
    }

    private void tryDecode(int p) {
        if (histLen[p] < StationId.maxFrameBits()) return;
        boolean[] h = hist[p];
        int prefixLen = StationId.PREAMBLE_BITS + StationId.SYNC_BITS;
        long prefix = ((long) StationId.PREAMBLE << StationId.SYNC_BITS) | (StationId.SYNC_WORD & 0xFFFFL);
        int searchEnd = HIST - StationId.maxFrameBits();
        for (int pos = 0; pos <= searchEnd; pos++) {
            int mism = 0;
            boolean ok = true;
            for (int b = 0; b < prefixLen; b++) {
                boolean want = ((prefix >> (prefixLen - 1 - b)) & 1L) != 0;
                if (h[pos + b] != want && ++mism > 2) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            int q = pos + prefixLen;
            int len = readBits(h, q, 8);
            q += 8;
            if (len <= 0 || len > StationId.MAX_NAME_BYTES) continue;
            if (q + len * 8 + 8 > HIST) continue;
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) {
                payload[i] = (byte) readBits(h, q, 8);
                q += 8;
            }
            int crc = readBits(h, q, 8);
            byte[] crcbuf = new byte[1 + len];
            crcbuf[0] = (byte) len;
            System.arraycopy(payload, 0, crcbuf, 1, len);
            if (StationId.crc8(crcbuf, crcbuf.length) != crc) continue;
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                int c = payload[i] & 0xFF;
                sb.append((c >= 32 && c < 127) ? (char) c : '?');
            }
            lastName = sb.toString();
            lastDecodeMs = System.currentTimeMillis();
            return;
        }
    }

    private static int readBits(boolean[] h, int pos, int count) {
        int v = 0;
        for (int i = 0; i < count; i++) v = (v << 1) | (h[pos + i] ? 1 : 0);
        return v;
    }

    /** Name decoded within freshnessMs, or empty if the signal lapsed. */
    public String currentName(long freshnessMs) {
        if (System.currentTimeMillis() - lastDecodeMs > freshnessMs) return "";
        return lastName;
    }

    /** Per-bit windowed Goertzel for one target tone. */
    private static final class Goertzel {

        private final double coeff;
        private double s1, s2;

        Goertzel(float freq) {
            coeff = 2.0 * Math.cos(2.0 * Math.PI * freq / StationId.SAMPLE_RATE);
        }

        void push(float x) {
            double s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        double magnitude() {
            return s1 * s1 + s2 * s2 - coeff * s1 * s2;
        }

        void reset() {
            s1 = 0;
            s2 = 0;
        }
    }
}
