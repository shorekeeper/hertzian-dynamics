package io.hertzian.dynamics.core;

/**
 * Mirror of {@code rf_core::spectrum::noise_floor::NoiseEnvironment}.
 * Selects the man-made noise constants the rf-core mixer uses to size
 * the external part of the receiver noise floor. Discriminants must
 * stay in sync with the Rust side; never reorder existing entries.
 */
public enum NoiseEnvironment {

    QUIET_RURAL(0),
    RURAL(1),
    RESIDENTIAL(2),
    CITY(3);

    private final int code;

    NoiseEnvironment(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static NoiseEnvironment fromCode(int code) {
        for (NoiseEnvironment e : values()) {
            if (e.code == code) return e;
        }
        throw new IllegalArgumentException("unknown noise environment code " + code);
    }
}
