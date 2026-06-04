package io.hertzian.dynamics.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streaming decoder and resampler for a Quite OK Audio (.qoa) file,
 * exposing a 48 kHz mono stream for a station transmitter to broadcast.
 *
 * <p>
 * QOA stores independent frames of up to 5120 samples per channel,
 * each carrying its own LMS predictor state, so the file can be decoded
 * forward frame by frame and seeked by walking frame headers. Channels
 * are downmixed to mono and the source rate is linearly resampled to
 * the engine's 48 kHz. The decode tables and the LMS predictor follow
 * the QOA reference specification exactly.
 *
 * <p>
 * The whole file is read into memory once (a few megabytes for a
 * song) but decoded lazily into a small rolling sample window, so a long
 * track does not hold its full PCM expansion in memory. The current
 * source position is tracked so the owning transmitter can persist it
 * and resume after a reload.
 *
 * <p>
 * Seek and buffer trim invariant
 * --------------------------------
 * {@link #ensureDecodedUpTo} both decodes forward and garbage collects
 * the decoded window behind the playback cursor {@link #srcPosWhole}.
 * The trim amount is the gap between that cursor and the start of the
 * decoded window, so the cursor must be the value that belongs to the
 * window being trimmed. {@link #seekSeconds} therefore sets the cursor
 * to its new target before it calls {@link #ensureDecodedUpTo}; an
 * earlier version set it afterwards, so a loop or a stop that seeked to
 * the start while the cursor still pointed near the end of the track
 * computed a trim larger than the buffer and allocated a negative sized
 * array. The trim is additionally clamped to the buffer length as a
 * backstop, so a stale or out of order cursor can never size a negative
 * array again.
 */
public final class QoaAudioSource {

    private static final int SLICE_LEN = 20;
    private static final int LMS_LEN = 4;
    private static final int[][] DEQUANT = { { 1, -1, 3, -3, 5, -5, 7, -7 }, { 5, -5, 18, -18, 32, -32, 49, -49 },
        { 16, -16, 53, -53, 95, -95, 147, -147 }, { 34, -34, 113, -113, 203, -203, 315, -315 },
        { 63, -63, 210, -210, 378, -378, 588, -588 }, { 104, -104, 345, -345, 621, -621, 966, -966 },
        { 158, -158, 528, -528, 950, -950, 1477, -1477 }, { 228, -228, 760, -760, 1368, -1368, 2128, -2128 },
        { 316, -316, 1053, -1053, 1895, -1895, 2947, -2947 }, { 422, -422, 1405, -1405, 2529, -2529, 3934, -3934 },
        { 548, -548, 1828, -1828, 3290, -3290, 5117, -5117 }, { 696, -696, 2320, -2320, 4176, -4176, 6496, -6496 },
        { 868, -868, 2893, -2893, 5207, -5207, 8099, -8099 }, { 1064, -1064, 3548, -3548, 6386, -6386, 9933, -9933 },
        { 1286, -1286, 4288, -4288, 7718, -7718, 12005, -12005 },
        { 1536, -1536, 5120, -5120, 9216, -9216, 14336, -14336 }, };

    private final byte[] data;
    private final int channels;
    private final int sampleRate;
    private final long totalSamples;
    private final double ratio;
    private static final int HEADER_SIZE = 8;

    private int frameByteOffset;
    private long decodedBaseSample;
    private float[] srcBuf = new float[0];
    private long srcPosWhole;
    private double srcPosFrac;

    public QoaAudioSource(Path path) throws IOException {
        this.data = Files.readAllBytes(path);
        if (data.length < 16 || readU32(0) != 0x716f6166L) throw new IOException("not a qoa file");
        this.totalSamples = readU32(4);
        this.channels = data[8] & 0xFF;
        this.sampleRate = (int) readU24(9);
        if (channels < 1 || sampleRate < 1) throw new IOException("bad qoa header");
        this.ratio = (double) sampleRate / 48_000.0;
        reset();
    }

    public void reset() {
        frameByteOffset = HEADER_SIZE;
        decodedBaseSample = 0;
        srcBuf = new float[0];
        srcPosWhole = 0;
        srcPosFrac = 0;
    }

    public double durationSeconds() {
        return (double) totalSamples / sampleRate;
    }

    public double positionSeconds() {
        return (double) srcPosWhole / sampleRate;
    }

    public boolean atEnd() {
        return srcPosWhole >= totalSamples - 1;
    }

    public void seekSeconds(double s) {
        long target = (long) (s * sampleRate);
        if (target < 0) target = 0;
        if (target >= totalSamples) target = Math.max(0, totalSamples - 1);
        frameByteOffset = HEADER_SIZE;
        srcBuf = new float[0];
        long consumed = 0;
        while (frameByteOffset + 8 <= data.length) {
            int fsamples = readU16(frameByteOffset + 4);
            int fsize = readU16(frameByteOffset + 6);
            if (consumed + fsamples > target || fsize <= 0) break;
            consumed += fsamples;
            frameByteOffset += fsize;
        }
        decodedBaseSample = consumed;
        // Set the playback cursor to the seek target before decoding and
        // trimming. ensureDecodedUpTo trims the decoded window behind this
        // cursor; if the cursor still held its pre-seek value (near the end
        // of the track on a loop or stop to the start) the trim would
        // exceed the freshly reset buffer and allocate a negative sized
        // array. With the cursor already at the target the trim is the
        // small in-frame offset and the buffer stays consistent.
        srcPosWhole = target;
        srcPosFrac = 0;
        ensureDecodedUpTo(target + 1);
    }

    /** Fill out[0..n) with 48 kHz mono in [-1, 1]; false at EOF. */
    public boolean read48kMono(float[] out, int n) {
        boolean full = true;
        for (int i = 0; i < n; i++) {
            long i0 = srcPosWhole, i1 = i0 + 1;
            if (i1 >= totalSamples) {
                out[i] = 0f;
                full = false;
                continue;
            }
            ensureDecodedUpTo(i1 + 1);
            float a = sampleAt(i0), b = sampleAt(i1);
            out[i] = (float) (a + (b - a) * srcPosFrac);
            srcPosFrac += ratio;
            long adv = (long) Math.floor(srcPosFrac);
            srcPosWhole += adv;
            srcPosFrac -= adv;
        }
        return full;
    }

    private float sampleAt(long idx) {
        int rel = (int) (idx - decodedBaseSample);
        return (rel >= 0 && rel < srcBuf.length) ? srcBuf[rel] : 0f;
    }

    private void ensureDecodedUpTo(long exclusive) {
        while (decodedBaseSample + srcBuf.length < exclusive && frameByteOffset + 8 <= data.length) {
            decodeFrame();
        }
        // Garbage collect samples behind the playback cursor. The drop is
        // the distance from the start of the decoded window to one sample
        // before the cursor, clamped to [0, srcBuf.length] so a negative
        // (cursor at or before the window start) or an oversized (stale
        // cursor) value can never size a negative array. The trim only
        // fires once the lead grows past one second so it is not run every
        // sample.
        long keepFrom = Math.max(0L, srcPosWhole - 1);
        long dropL = keepFrom - decodedBaseSample;
        int drop = (int) Math.max(0L, Math.min(dropL, (long) srcBuf.length));
        if (drop > 48_000) {
            float[] nb = new float[srcBuf.length - drop];
            System.arraycopy(srcBuf, drop, nb, 0, nb.length);
            srcBuf = nb;
            decodedBaseSample += drop;
        }
    }

    private void decodeFrame() {
        int off = frameByteOffset;
        int fchannels = data[off] & 0xFF;
        int fsamples = readU16(off + 4);
        int fsize = readU16(off + 6);
        if (fsize <= 0 || fchannels < 1) {
            frameByteOffset = data.length;
            return;
        }
        int p = off + 8;
        int[][] hist = new int[fchannels][LMS_LEN];
        int[][] wts = new int[fchannels][LMS_LEN];
        for (int c = 0; c < fchannels; c++) {
            for (int i = 0; i < LMS_LEN; i++) {
                hist[c][i] = readS16(p);
                p += 2;
            }
            for (int i = 0; i < LMS_LEN; i++) {
                wts[c][i] = readS16(p);
                p += 2;
            }
        }
        float[] mono = new float[fsamples];
        for (int si = 0; si < fsamples; si += SLICE_LEN) {
            for (int c = 0; c < fchannels; c++) {
                long slice = readU64(p);
                p += 8;
                int sf = (int) ((slice >>> 60) & 0xF);
                int count = Math.min(SLICE_LEN, fsamples - si);
                for (int i = 0; i < SLICE_LEN; i++) {
                    int r = (int) ((slice >>> (57 - 3 * i)) & 0x7);
                    int dq = DEQUANT[sf][r];
                    int pred = 0;
                    for (int k = 0; k < LMS_LEN; k++) pred += wts[c][k] * hist[c][k];
                    pred >>= 13;
                    int rec = clampS16(pred + dq);
                    int delta = dq >> 4;
                    for (int k = 0; k < LMS_LEN; k++) wts[c][k] += hist[c][k] < 0 ? -delta : delta;
                    for (int k = 0; k < LMS_LEN - 1; k++) hist[c][k] = hist[c][k + 1];
                    hist[c][LMS_LEN - 1] = rec;
                    if (i < count) {
                        float f = rec / 32768f;
                        if (c == 0) mono[si + i] = f;
                        else mono[si + i] += f;
                    }
                }
            }
        }
        if (fchannels > 1) {
            float inv = 1f / fchannels;
            for (int i = 0; i < fsamples; i++) mono[i] *= inv;
        }
        float[] nb = new float[srcBuf.length + fsamples];
        System.arraycopy(srcBuf, 0, nb, 0, srcBuf.length);
        System.arraycopy(mono, 0, nb, srcBuf.length, fsamples);
        srcBuf = nb;
        frameByteOffset = off + fsize;
    }

    private static int clampS16(int v) {
        return v < -32768 ? -32768 : (v > 32767 ? 32767 : v);
    }

    private long readU32(int o) {
        return ((long) (data[o] & 0xFF) << 24) | ((data[o + 1] & 0xFF) << 16)
            | ((data[o + 2] & 0xFF) << 8)
            | (data[o + 3] & 0xFF);
    }

    private long readU24(int o) {
        return ((long) (data[o] & 0xFF) << 16) | ((data[o + 1] & 0xFF) << 8) | (data[o + 2] & 0xFF);
    }

    private int readU16(int o) {
        return ((data[o] & 0xFF) << 8) | (data[o + 1] & 0xFF);
    }

    private int readS16(int o) {
        int v = readU16(o);
        return v >= 0x8000 ? v - 0x10000 : v;
    }

    private long readU64(int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (data[o + i] & 0xFF);
        return v;
    }
}
