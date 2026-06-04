package io.hertzian.dynamics.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.hertzian.dynamics.core.internal.Native;

/**
 * Ionospheric LUT. Three pre-baked solar activity buckets; the Java
 * side picks one at construction. The Rust side keeps the foF2
 * table and computes MUF on demand.
 */
public final class Ionosphere implements AutoCloseable {

    public enum SolarActivity {

        LOW(0),
        MEDIUM(1),
        HIGH(2);

        private final int code;

        SolarActivity(int c) {
            this.code = c;
        }

        public int code() {
            return code;
        }
    }

    private final MemorySegment handle;
    private boolean closed = false;

    private Ionosphere(MemorySegment handle) {
        this.handle = handle;
    }

    public static Ionosphere create(SolarActivity activity) {
        Native.ensureLoaded(null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) Native.HD_IONO_CREATE.invokeExact(activity.code(), out);
            ErrorCode.throwIfError(rc, "hd_iono_create");
            return new Ionosphere(out.get(ValueLayout.ADDRESS, 0L));
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "iono create failed", t);
        }
    }

    MemorySegment handle() {
        checkOpen();
        return handle;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            int rc = (int) Native.HD_IONO_DESTROY.invokeExact(handle);
            ErrorCode.throwIfError(rc, "hd_iono_destroy");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "iono destroy failed", t);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Ionosphere has been closed");
    }
}
