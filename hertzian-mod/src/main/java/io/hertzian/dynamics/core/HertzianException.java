package io.hertzian.dynamics.core;

/**
 * Thrown when an rf-core call returns a non-zero error code or when
 * the FFM binding layer detects an internal inconsistency (ABI
 * mismatch, missing symbol, layout drift).
 */
public class HertzianException extends RuntimeException {

    private final int errorCode;

    public HertzianException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public HertzianException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }
}
