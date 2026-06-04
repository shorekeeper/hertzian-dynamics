package io.hertzian.dynamics.core;

import java.util.Arrays;

/**
 * Continuous-phase FSK encoder for the station identifier. Owned by a
 * transmitter; it mixes the repeating name frame onto each audio frame
 * just before the signal leaves for rf-core. Continuous phase keeps the
 * subcarrier spectrum tight so it does not splatter into the audio band.
 *
 * <p>
 * The bit and intra-bit cursors persist across calls, so the frame
 * streams seamlessly across the variable length audio buffers the
 * transmitter pushes each tick.
 */
public final class StationIdEncoder {

    private boolean[] bits = new boolean[0];
    private int bitCursor = 0;
    private int sampleInBit = 0;
    private double phase = 0.0;
    private byte[] currentName = new byte[0];

    /** Set the broadcast name. Empty disables the subcarrier entirely. */
    public synchronized void setName(String name) {
        byte[] n = StationId.sanitize(name);
        if (Arrays.equals(n, currentName)) return;
        currentName = n;
        rebuild();
    }

    public boolean active() {
        return bits.length > 0;
    }

    private void rebuild() {
        if (currentName.length == 0) {
            bits = new boolean[0];
            bitCursor = 0;
            sampleInBit = 0;
            return;
        }
        int payload = currentName.length;
        boolean[] b = new boolean[StationId.frameBits(payload)];
        int p = 0;
        p = writeBits(b, p, StationId.PREAMBLE, StationId.PREAMBLE_BITS);
        p = writeBits(b, p, StationId.SYNC_WORD, StationId.SYNC_BITS);
        p = writeBits(b, p, payload, StationId.LEN_BITS);
        for (int i = 0; i < payload; i++) p = writeBits(b, p, currentName[i] & 0xFF, 8);
        byte[] crcbuf = new byte[1 + payload];
        crcbuf[0] = (byte) payload;
        System.arraycopy(currentName, 0, crcbuf, 1, payload);
        writeBits(b, p, StationId.crc8(crcbuf, crcbuf.length), StationId.CRC_BITS);
        bits = b;
        bitCursor = 0;
        sampleInBit = 0;
    }

    private static int writeBits(boolean[] b, int pos, int value, int count) {
        for (int i = count - 1; i >= 0; i--) b[pos++] = ((value >> i) & 1) != 0;
        return pos;
    }

    /** Add the subcarrier onto out[0..n). No-op when no name is set. */
    public synchronized void mixInto(float[] out, int n) {
        if (bits.length == 0) return;
        double markStep = 2.0 * Math.PI * StationId.MARK_HZ / StationId.SAMPLE_RATE;
        double spaceStep = 2.0 * Math.PI * StationId.SPACE_HZ / StationId.SAMPLE_RATE;
        for (int i = 0; i < n; i++) {
            phase += bits[bitCursor] ? markStep : spaceStep;
            if (phase > Math.PI) phase -= 2.0 * Math.PI;
            out[i] += (float) (StationId.SUBCARRIER_LEVEL * Math.sin(phase));
            if (++sampleInBit >= StationId.SAMPLES_PER_BIT) {
                sampleInBit = 0;
                if (++bitCursor >= bits.length) bitCursor = 0;
            }
        }
    }
}
