package io.hertzian.dynamics.core;

/**
 * Immutable snapshot of the per-workload compute dispatch counters read
 * from the native compute backend. Used for diagnostics. The values are
 * monotonic counters since core creation; the result of any single call
 * is identical regardless of which backend ran it.
 *
 * @param cpuCalls       calls served by the CPU path (includes fallbacks)
 * @param gpuCalls       calls served by the GPU path
 * @param fallbackCalls  calls where the GPU was attempted but rejected the
 *                       request and the CPU ran instead
 * @param lastBackendGpu true if the most recent call ran on the GPU
 * @param gpuAvailable   true if a GPU path exists for this workload
 */
public record ComputeStats(long cpuCalls, long gpuCalls, long fallbackCalls, boolean lastBackendGpu,
                           boolean gpuAvailable) {}