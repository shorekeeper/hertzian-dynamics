package io.hertzian.dynamics.core;

import java.nio.charset.StandardCharsets;

/**
 * Shared constants and helpers for the in-signal station identifier.
 *
 * <p>
 * The station name rides on the transmitted audio as a continuous
 * two-tone FSK subcarrier, an RDS-style data channel. Both tones sit
 * above 15 kHz, higher than every receiver listening filter in this
 * mod (voice 3 kHz, broadcast 15 kHz), so the data is inaudible: it is
 * filtered out of what the player hears yet survives in the raw
 * demodulated audio where the decoder taps it. Because the data
 * physically modulates the signal it degrades with the channel, so at
 * low signal to noise the name cannot be recovered, exactly as a real
 * RDS decoder loses the program service name in the noise.
 *
 * <p>
 * Frame, sent repeatedly and MSB first: an 8 bit alternating
 * preamble, a 16 bit sync word, an 8 bit length, the ASCII name bytes,
 * and an 8 bit CRC over the length plus name. The CRC is what makes a
 * noisy decode fail cleanly rather than print garbage.
 */
public final class StationId {

    private StationId() {}

    public static final int MAX_NAME_BYTES = 12;
    public static final int SAMPLE_RATE = 48_000;
    public static final int BAUD = 100;
    public static final int SAMPLES_PER_BIT = SAMPLE_RATE / BAUD; // 480

    /** Bit 0 tone. 16 kHz lands on an exact Goertzel bin at 480/bit. */
    public static final float SPACE_HZ = 16_000f;
    /** Bit 1 tone. 18 kHz, also an exact bin, below the 24 kHz Nyquist. */
    public static final float MARK_HZ = 18_000f;
    /** Subcarrier amplitude added on top of the audio. */
    public static final float SUBCARRIER_LEVEL = 0.16f;

    public static final int PREAMBLE_BITS = 8;
    public static final int PREAMBLE = 0xAA;
    public static final int SYNC_BITS = 16;
    public static final int SYNC_WORD = 0x7E3C;
    public static final int LEN_BITS = 8;
    public static final int CRC_BITS = 8;

    /** Total bit count of a frame carrying the given payload length. */
    public static int frameBits(int payloadBytes) {
        return PREAMBLE_BITS + SYNC_BITS + LEN_BITS + payloadBytes * 8 + CRC_BITS;
    }

    public static int maxFrameBits() {
        return frameBits(MAX_NAME_BYTES);
    }

    /** CRC-8, polynomial 0x07, the standard SMBus/ATM variant. */
    public static int crc8(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc & 0xFF;
    }

    /** Clamp a name to printable ASCII and the byte cap. */
    public static byte[] sanitize(String name) {
        if (name == null) return new byte[0];
        byte[] raw = name.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(raw.length, MAX_NAME_BYTES);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int c = raw[i] & 0xFF;
            out[i] = (c >= 32 && c < 127) ? (byte) c : (byte) '?';
        }
        return out;
    }
}
