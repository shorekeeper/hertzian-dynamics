package io.hertzian.dynamics.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.hertzian.dynamics.core.internal.Layouts;
import io.hertzian.dynamics.core.internal.Native;

/**
 * Top level handle. Owns the Vulkan instance, device, shared GPU
 * resources, and the optional compute backend. Build a
 * {@link SpectrumManager} through this handle to drive the radio ether,
 * call {@link #analyzerDft} for the spectrum analyzer DFT, and use
 * {@link #setComputePolicy} / {@link #computeStats} to configure and
 * observe the compute backend.
 *
 * <p>
 * Lifecycle: construct once per world load and close on world unload. The
 * class implements {@link AutoCloseable} so a try-with-resources block
 * tears the native handle down deterministically.
 */
public final class RfCore implements AutoCloseable {

    /** Flag bit mirroring HD_CORE_FLAG_ENABLE_GPU in the C ABI. */
    private static final int FLAG_ENABLE_GPU = 1;

    /** Workload id of the spectrum analyzer zoom DFT (HD_WORKLOAD_ZOOM_DFT). */
    public static final int WORKLOAD_ZOOM_DFT = 0;

    /** Workload id of the propagation raycast (HD_WORKLOAD_PROPAGATION). */
    public static final int WORKLOAD_PROPAGATION = 1;

    /** Policy: pick CPU or GPU automatically by work size (HD_COMPUTE_MODE_AUTO). */
    public static final int COMPUTE_MODE_AUTO = 0;
    /** Policy: always CPU (HD_COMPUTE_MODE_CPU). */
    public static final int COMPUTE_MODE_CPU = 1;
    /** Policy: GPU when available, else CPU (HD_COMPUTE_MODE_GPU). */
    public static final int COMPUTE_MODE_GPU = 2;

    private final MemorySegment handle;
    private boolean closed = false;

    private RfCore(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * Open the cdylib if needed and construct a fresh RfCore with the GPU
     * compute backend enabled. Equivalent to {@code create(true)}.
     */
    public static RfCore create() {
        return create(true);
    }

    /**
     * Open the cdylib if needed and construct a fresh RfCore. When
     * {@code enableGpu} is false, every accelerated workload runs on the
     * CPU. When true the GPU backend is built if the device and pipelines
     * come up; failure to build it is not fatal and falls back to CPU.
     *
     * <p>
     * Initialisation cost is dominated by Vulkan device selection and
     * pipeline cache load; expect tens of milliseconds on a cold cache.
     */
    public static RfCore create(boolean enableGpu) {
        Native.ensureLoaded(null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int flags = enableGpu ? FLAG_ENABLE_GPU : 0;
            int rc = (int) Native.HD_CORE_CREATE_EX.invokeExact(flags, out);
            ErrorCode.throwIfError(rc, "hd_core_create_ex");
            MemorySegment ptr = out.get(ValueLayout.ADDRESS, 0L);
            if (ptr.equals(MemorySegment.NULL)) {
                throw new HertzianException(ErrorCode.NULL, "rf-core returned null core handle");
            }
            return new RfCore(ptr);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "core create failed", t);
        }
    }

    /** Raw native pointer for use by {@link SpectrumManager}. Do not retain. */
    MemorySegment handle() {
        checkOpen();
        return handle;
    }

    /**
     * Set the backend policy for one workload. {@code workload} is a
     * {@code WORKLOAD_*} id, {@code mode} is a {@code COMPUTE_MODE_*} value,
     * and {@code minWork} is the Auto threshold in work units (ignored for
     * the forced modes). The choice is realism neutral: CPU and GPU produce
     * the same result, so this only trades CPU time for GPU time.
     */
    public void setComputePolicy(int workload, int mode, long minWork) {
        checkOpen();
        try {
            int rc = (int) Native.HD_CORE_SET_COMPUTE_POLICY.invokeExact(handle, workload, mode, minWork);
            ErrorCode.throwIfError(rc, "hd_core_set_compute_policy");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "set_compute_policy failed", t);
        }
    }

    /** Read the dispatch counters for one workload. */
    public ComputeStats computeStats(int workload) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(Layouts.COMPUTE_STATS);
            int rc = (int) Native.HD_CORE_COMPUTE_STATS.invokeExact(handle, workload, out);
            ErrorCode.throwIfError(rc, "hd_core_compute_stats");
            long cpu = out.get(ValueLayout.JAVA_LONG, statOff("cpu_calls"));
            long gpu = out.get(ValueLayout.JAVA_LONG, statOff("gpu_calls"));
            long fb = out.get(ValueLayout.JAVA_LONG, statOff("fallback_calls"));
            int last = out.get(ValueLayout.JAVA_INT, statOff("last_backend"));
            int avail = out.get(ValueLayout.JAVA_INT, statOff("gpu_available"));
            return new ComputeStats(cpu, gpu, fb, last != 0, avail != 0);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "compute_stats failed", t);
        }
    }

    private static long statOff(String name) {
        return Layouts.COMPUTE_STATS.byteOffset(MemoryLayout.PathElement.groupElement(name));
    }

    /**
     * Run the spectrum analyzer zoom DFT in rf-core. {@code iq} is the
     * receiver baseband chunk as interleaved I/Q floats (length at least
     * {@code 2 * n}); {@code outDb} receives {@code bins} per bin
     * magnitudes in dB. The native side windows the input, projects the
     * bins across {@code spanHz} centered on baseband DC, normalises, and
     * converts to dB. It selects the GPU or CPU backend per the configured
     * policy; the result is backend independent to f32 rounding.
     *
     * <p>
     * Buffers are allocated by explicit byte size through the
     * {@code allocate(long)} overload. The {@code allocate(MemoryLayout,
     * long)} overload is avoided here: on the Java 21 runtime this mod
     * targets it allocates a single element rather than an array of the
     * requested count, which silently under-sizes the segment.
     */
    public void analyzerDft(float[] iq, int n, float spanHz, float fsHz, int bins, float[] outDb) {
        checkOpen();
        if (n <= 0 || bins <= 0) return;
        if (iq.length < 2 * n || outDb.length < bins) {
            throw new HertzianException(ErrorCode.INVALID_ARG, "zoom dft buffer too small");
        }
        try (Arena arena = Arena.ofConfined()) {
            // Native side reads 2*n interleaved floats from `in`.
            long inBytes = (long) 2 * n * Float.BYTES;
            MemorySegment in = arena.allocate(inBytes);
            MemorySegment.copy(iq, 0, in, ValueLayout.JAVA_FLOAT, 0L, 2 * n);

            // Native side writes `bins` floats into `out`.
            long outBytes = (long) bins * Float.BYTES;
            MemorySegment out = arena.allocate(outBytes);

            int rc = (int) Native.HD_ZOOM_DFT.invokeExact(handle, in, n, spanHz, fsHz, bins, out);
            ErrorCode.throwIfError(rc, "hd_zoom_dft");
            MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0L, outDb, 0, bins);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "zoom_dft failed", t);
        }
    }

    /**
     * Run a batched voxel absorption raycast. {@code queries} holds
     * {@code count} packed rays, 8 floats each: origin xyz, one pad, target
     * xyz, one pad. {@code outDb} receives {@code count} absorption values
     * in dB. The native side selects the GPU or CPU backend per the
     * configured propagation policy; the result is backend independent
     * within the self-test tolerance.
     *
     * <p>
     * Buffers are allocated by explicit byte size; the
     * {@code allocate(MemoryLayout, long)} overload under-sizes the segment
     * on this runtime and is avoided.
     */
    public void raycastBatch(VoxelGrid grid, MaterialTable materials, float[] queries, int count, double frequencyHz,
                             float budgetDb, float[] outDb) {
        checkOpen();
        if (count <= 0) return;
        if (queries.length < count * 8 || outDb.length < count) {
            throw new HertzianException(ErrorCode.INVALID_ARG, "raycast buffer too small");
        }
        try (Arena arena = Arena.ofConfined()) {
            long inBytes = (long) count * 8 * Float.BYTES;
            MemorySegment in = arena.allocate(inBytes);
            MemorySegment.copy(queries, 0, in, ValueLayout.JAVA_FLOAT, 0L, count * 8);

            long outBytes = (long) count * Float.BYTES;
            MemorySegment out = arena.allocate(outBytes);

            int rc = (int) Native.HD_RAYCAST_BATCH.invokeExact(
                    handle,
                    grid.handle(),
                    materials.handle(),
                    in,
                    count,
                    frequencyHz,
                    budgetDb,
                    out);
            ErrorCode.throwIfError(rc, "hd_raycast_batch");
            MemorySegment.copy(out, ValueLayout.JAVA_FLOAT, 0L, outDb, 0, count);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "raycast_batch failed", t);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            int rc = (int) Native.HD_CORE_DESTROY.invokeExact(handle);
            ErrorCode.throwIfError(rc, "hd_core_destroy");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "core destroy failed", t);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("RfCore has been closed");
    }
}