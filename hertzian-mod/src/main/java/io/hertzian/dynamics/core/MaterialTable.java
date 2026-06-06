package io.hertzian.dynamics.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.hertzian.dynamics.core.internal.Native;

/**
 * Material attenuation table. Pre-populated with air, stone, water, wood,
 * glass, dirt, leaves and iron, each described by frequency dependent
 * electrical properties. Callers may register custom entries with
 * {@link #register}.
 *
 * <p>
 * A material is defined by the ITU-R P.2040 power law form: the real
 * relative permittivity is {@code epsilon_r = epsA * f_GHz^epsB} and the
 * conductivity is {@code sigma = sigmaC * f_GHz^sigmaD} in siemens per
 * metre, with frequency in gigahertz. The native core derives the complex
 * permittivity, the per metre absorption and the Fresnel reflection
 * coefficients from these four numbers, so the same pair of curves drives
 * both penetration loss and reflection consistently.
 */
public final class MaterialTable implements AutoCloseable {

    private final MemorySegment handle;
    private boolean closed = false;

    private MaterialTable(MemorySegment handle) {
        this.handle = handle;
    }

    /** Build a table with the default Rust-side material set. */
    public static MaterialTable createDefaults() {
        Native.ensureLoaded(null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) Native.HD_MATERIALS_CREATE_DEFAULTS.invokeExact(out);
            ErrorCode.throwIfError(rc, "hd_materials_create_defaults");
            return new MaterialTable(out.get(ValueLayout.ADDRESS, 0L));
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "materials create failed", t);
        }
    }

    /**
     * Register or overwrite a material slot from its electrical
     * properties. {@code epsA} and {@code epsB} parameterise the
     * permittivity power law, {@code sigmaC} and {@code sigmaD} the
     * conductivity power law in siemens per metre, and
     * {@code pivotFrequencyHz} clamps the property evaluation frequency
     * from below.
     */
    public void register(int materialId, float epsA, float epsB, float sigmaC, float sigmaD,
                         float pivotFrequencyHz) {
        checkOpen();
        if ((materialId & 0xFFFF0000) != 0) {
            throw new IllegalArgumentException("material id out of u16 range: " + materialId);
        }
        try {
            int rc = (int) Native.HD_MATERIALS_REGISTER
                    .invokeExact(handle, (short) materialId, epsA, epsB, sigmaC, sigmaD, pivotFrequencyHz);
            ErrorCode.throwIfError(rc, "hd_materials_register");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "materials register failed", t);
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
            int rc = (int) Native.HD_MATERIALS_DESTROY.invokeExact(handle);
            ErrorCode.throwIfError(rc, "hd_materials_destroy");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "materials destroy failed", t);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("MaterialTable has been closed");
    }
}