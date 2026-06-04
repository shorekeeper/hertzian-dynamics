package io.hertzian.dynamics.core;

/**
 * Mirror of {@code rf_core::types::modulation::Modulation}.
 * Discriminants must stay in sync with the Rust side; never reorder
 * existing entries.
 */
public enum Modulation {

    CW(0),
    AM(1),
    NARROW_FM(2),
    WIDE_FM(3),
    USB(4),
    LSB(5),
    NOISE(6);

    private final int code;

    Modulation(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Modulation fromCode(int code) {
        for (Modulation m : values()) {
            if (m.code == code) return m;
        }
        throw new IllegalArgumentException("unknown modulation code " + code);
    }
}
