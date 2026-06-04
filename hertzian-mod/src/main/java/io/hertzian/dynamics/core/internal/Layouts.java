package io.hertzian.dynamics.core.internal;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * Centralised FFM memory layouts mirroring the Rust C ABI declared
 * in {@code rf-jni/include/rf_core.h}. Every struct that crosses
 * the FFI boundary lives here. The layouts must match the Rust
 * {@code #[repr(C)]} declarations exactly; mismatches are caught
 * by the static {@code byteSize} assertions performed at class
 * loading time.
 *
 * <p>
 * Layout choices follow the C side: every numeric field has its
 * platform-canonical alignment, structs declare their fields in
 * source order, and trailing padding is added explicitly when the
 * C declaration does so.
 */
public final class Layouts {

    private Layouts() {}

    /** Iq sample: two contiguous floats. 8 bytes total. */
    public static final StructLayout IQ = MemoryLayout
        .structLayout(ValueLayout.JAVA_FLOAT.withName("i"), ValueLayout.JAVA_FLOAT.withName("q"))
        .withName("hd_iq_t");

    /** Spectrum chunk header. 40 bytes. */
    public static final StructLayout CHUNK_HEADER = MemoryLayout
        .structLayout(
            ValueLayout.JAVA_DOUBLE.withName("center_hz"),
            ValueLayout.JAVA_FLOAT.withName("sample_rate_hz"),
            ValueLayout.JAVA_FLOAT.withName("bandwidth_hz"),
            ValueLayout.JAVA_INT.withName("sample_count"),
            ValueLayout.JAVA_INT.withName("sequence"),
            ValueLayout.JAVA_LONG.withName("server_tick"),
            ValueLayout.JAVA_FLOAT.withName("noise_floor_w"),
            ValueLayout.JAVA_FLOAT.withName("signal_power_w"))
        .withName("hd_chunk_header_t");

    /**
     * Emission descriptor. 64 bytes, alignment 8.
     *
     * <p>
     * The field order matches the Rust {@code HdEmissionDesc}
     * after reordering for tight C layout: {@code carrier_hz}
     * leads so its 8 byte alignment is satisfied at offset 0,
     * then every remaining field is 4 bytes wide. There are no
     * internal padding holes and no trailing padding.
     */
    public static final StructLayout EMISSION_DESC = MemoryLayout
        .structLayout(
            ValueLayout.JAVA_DOUBLE.withName("carrier_hz"),
            ValueLayout.JAVA_INT.withName("modulation"),
            ValueLayout.JAVA_FLOAT.withName("bandwidth_hz"),
            ValueLayout.JAVA_FLOAT.withName("pos_x"),
            ValueLayout.JAVA_FLOAT.withName("pos_y"),
            ValueLayout.JAVA_FLOAT.withName("pos_z"),
            ValueLayout.JAVA_FLOAT.withName("vel_x"),
            ValueLayout.JAVA_FLOAT.withName("vel_y"),
            ValueLayout.JAVA_FLOAT.withName("vel_z"),
            ValueLayout.JAVA_FLOAT.withName("tx_power_w"),
            ValueLayout.JAVA_FLOAT.withName("antenna_gain"),
            ValueLayout.JAVA_INT.withName("pcm_capacity"),
            ValueLayout.JAVA_INT.withName("jam_profile"),
            ValueLayout.JAVA_FLOAT.withName("jam_rate_hz"),
            ValueLayout.JAVA_FLOAT.withName("jam_sigma"))
        .withName("hd_emission_desc_t");

    /** Receiver config. 72 bytes. */
    public static final StructLayout RECEIVER_CONFIG = MemoryLayout
        .structLayout(
            ValueLayout.JAVA_DOUBLE.withName("tuned_hz"),
            ValueLayout.JAVA_FLOAT.withName("bandwidth_hz"),
            ValueLayout.JAVA_INT.withName("modulation"),
            ValueLayout.JAVA_FLOAT.withName("antenna_gain"),
            ValueLayout.JAVA_FLOAT.withName("pos_x"),
            ValueLayout.JAVA_FLOAT.withName("pos_y"),
            ValueLayout.JAVA_FLOAT.withName("pos_z"),
            ValueLayout.JAVA_FLOAT.withName("vel_x"),
            ValueLayout.JAVA_FLOAT.withName("vel_y"),
            ValueLayout.JAVA_FLOAT.withName("vel_z"),
            ValueLayout.JAVA_FLOAT.withName("agc_target"),
            ValueLayout.JAVA_FLOAT.withName("agc_attack_seconds"),
            ValueLayout.JAVA_FLOAT.withName("agc_release_seconds"),
            ValueLayout.JAVA_FLOAT.withName("agc_max_gain"),
            ValueLayout.JAVA_FLOAT.withName("agc_min_gain"),
            ValueLayout.JAVA_FLOAT.withName("noise_figure_db"),
            ValueLayout.JAVA_INT.withName("noise_environment"))
        .withName("hd_receiver_config_t");

    /** Compute dispatch counters. 32 bytes, alignment 8. */
    public static final StructLayout COMPUTE_STATS = MemoryLayout
        .structLayout(
            ValueLayout.JAVA_LONG.withName("cpu_calls"),
            ValueLayout.JAVA_LONG.withName("gpu_calls"),
            ValueLayout.JAVA_LONG.withName("fallback_calls"),
            ValueLayout.JAVA_INT.withName("last_backend"),
            ValueLayout.JAVA_INT.withName("gpu_available"))
        .withName("hd_compute_stats_t");

    static {
        // Compile-time sanity checks; mismatches with the Rust
        // const _: () asserts indicate a layout drift between the
        // FFM bindings and the C ABI declared in rf_core.h.
        if (IQ.byteSize() != 8) throw new AssertionError("IQ layout size != 8");
        if (CHUNK_HEADER.byteSize() != 40) throw new AssertionError("CHUNK_HEADER size != 40");
        if (EMISSION_DESC.byteSize() != 64) throw new AssertionError("EMISSION_DESC size != 64");
        if (RECEIVER_CONFIG.byteSize() != 72) throw new AssertionError("RECEIVER_CONFIG size != 72");
        if (COMPUTE_STATS.byteSize() != 32) throw new AssertionError("COMPUTE_STATS size != 32");
    }
}
