package io.hertzian.dynamics.core;

/**
 * Mirror of the negative error codes returned by every rf-core C
 * function. Use {@link #throwIfError} at every call site so error
 * conditions surface as Java exceptions rather than propagating
 * silently through return values.
 */
public final class ErrorCode {

    public static final int OK = 0;
    public static final int NULL = -1;
    public static final int INVALID_ARG = -2;
    public static final int OUT_OF_RANGE = -3;
    public static final int NOT_FOUND = -4;
    public static final int VK_FAILED = -5;
    public static final int INVALID_SHADER = -6;
    public static final int INVALID_STATE = -7;
    public static final int PANIC = -100;
    public static final int UNKNOWN = -999;

    private ErrorCode() {}

    public static void throwIfError(int rc, String context) {
        if (rc == OK) return;
        throw new HertzianException(rc, describe(rc) + " in " + context);
    }

    public static String describe(int code) {
        return switch (code) {
            case OK -> "ok";
            case NULL -> "null handle";
            case INVALID_ARG -> "invalid argument";
            case OUT_OF_RANGE -> "value out of range";
            case NOT_FOUND -> "not found";
            case VK_FAILED -> "Vulkan failure";
            case INVALID_SHADER -> "invalid shader";
            case INVALID_STATE -> "invalid state";
            case PANIC -> "Rust panic caught at FFI boundary";
            default -> "unknown error code " + code;
        };
    }
}
