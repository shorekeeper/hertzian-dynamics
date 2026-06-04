package io.hertzian.dynamics.core;

/**
 * Snapshot of a mixed chunk returned by {@link SpectrumManager#mix}.
 * {@code samples} is an interleaved I/Q float array of length
 * {@code 2 * sampleCount}; index {@code 2k} holds the I component
 * and {@code 2k + 1} the Q component of sample {@code k}.
 *
 * <p>
 * {@code signalPowerWatts} is the pre-AGC, pre-noise received signal
 * power; {@code noiseFloorWatts} is the injected noise power. Their
 * ratio is the signal to noise ratio the receiver reports to the client
 * S-meter, which is why both travel in the chunk rather than being
 * recomputed from the post-AGC samples (the AGC would have flattened
 * them).
 */
public record SpectrumChunk(double centerHz, float sampleRateHz, float bandwidthHz, int sampleCount, int sequence,
    long serverTick, float noiseFloorWatts, float signalPowerWatts, float[] samples) {}
