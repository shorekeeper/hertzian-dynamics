package io.hertzian.dynamics.audio;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import io.hertzian.dynamics.HertzianDynamics;

/**
 * One AL source per receiver tile. Form: accepts
 * already-demodulated int16 PCM. The demodulator moved to the
 * server side so the network path can ship PCM instead of IQ
 * (4x bandwidth reduction).
 */
public final class ReceiverVoice {

    private final int sourceId;
    private final BufferPool pool;
    private final int sampleRateHz;
    private final IntBuffer queueScratch = BufferUtils.createIntBuffer(1);
    private boolean playing = false;
    private boolean disposed = false;

    /**
     * One AL source per receiver tile. Accepts already-demodulated
     * int16 PCM; demodulation runs server side so the network path
     * ships PCM rather than IQ, a fourfold bandwidth reduction.
     */
    public static ReceiverVoice create(int sampleRateHz, float x, float y, float z) {
        AudioSubsystem sub = AudioSubsystem.get();
        if (sub == null || !sub.isAvailable()) return null;
        int source = AL10.alGenSources();
        // Check before doing anything else: a saturated AL source
        // pool returns zero plus AL_OUT_OF_MEMORY, and every later
        // call against the zero handle flips to AL_INVALID_NAME.
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR || source == 0) {
            HertzianDynamics.LOGGER.warn("alGenSources failed (err={}, id={}); voice not created", err, source);
            return null;
        }
        AL10.alSource3f(source, AL10.AL_POSITION, x, y, z);
        AL10.alSource3f(source, AL10.AL_VELOCITY, 0f, 0f, 0f);
        AL10.alSourcef(source, AL10.AL_GAIN, 1.0f);

        // Roll-off parameters for room-scale audibility under the
        // AL_INVERSE_DISTANCE_CLAMPED model, which computes gain as
        // ref / (ref + rolloff * (clampedDistance - ref)). With an
        // 8 m reference distance and unity rolloff factor, a source
        // within one room (3 to 8 m) reads at near unity gain and
        // falls off toward the 96 m maximum distance. A smaller
        // reference distance would drop a same-room source well below
        // unity.
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 8.0f);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 96.0f);
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AudioSubsystem.checkAlError("source setup");
        return new ReceiverVoice(source, sub.bufferPool(), sampleRateHz);
    }

    private ReceiverVoice(int sourceId, BufferPool pool, int sampleRateHz) {
        this.sourceId = sourceId;
        this.pool = pool;
        this.sampleRateHz = sampleRateHz;
    }

    public void setPosition(float x, float y, float z) {
        if (disposed) return;
        AL10.alSource3f(sourceId, AL10.AL_POSITION, x, y, z);
    }

    /**
     * Maximum unprocessed buffers tolerated in the AL queue.
     * Exceeding this triggers a flush so the listener catches up to
     * fresh audio instead of slogging through stale ones. Three
     * buffers at 2400 samples and 48 kHz cover 150 ms of audio
     * worth of jitter absorption, which sits comfortably above the
     * tick period (50 ms) and below the threshold of perceptible
     * conversational lag (~200 ms).
     */
    private static final int MAX_QUEUE_DEPTH = 3;

    /**
     * Queue a PCM frame. The buffer is copied into a fresh AL
     * buffer; the caller may reuse {@code pcm} after this returns.
     *
     * <p>
     * Before queueing, the source's current backlog is checked.
     * If it exceeds {@link #MAX_QUEUE_DEPTH} unprocessed buffers,
     * the source is stopped, every buffer is unqueued back into
     * the pool, and playback restarts on this fresh frame. The
     * listener hears a brief glitch but never accumulates more
     * than {@code MAX_QUEUE_DEPTH * chunkDurationMs} of latency.
     *
     * <p>
     * This is the standard catch-up policy for real-time audio
     * streams: drop old samples instead of stretching time. The
     * alternative (pitch-shift to drain faster) would be inaudible
     * on noise but audible on tone or music content.
     */
    public void pushPcm(short[] pcm) {
        if (disposed) return;

        // Catch-up check: if the source is dragging too far behind,
        // flush it before queueing the new frame.
        int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
        int unprocessed = queued - processed;
        if (unprocessed >= MAX_QUEUE_DEPTH) {
            HertzianDynamics.LOGGER
                .debug("Source {} queue depth {} exceeded cap {}; flushing", sourceId, unprocessed, MAX_QUEUE_DEPTH);
            AL10.alSourceStop(sourceId);
            // Drain every buffer back into the pool. Both
            // processed and pending counts are returned by a fresh
            // alSourceUnqueueBuffers loop.
            int total = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
            for (int i = 0; i < total; i++) {
                queueScratch.clear();
                queueScratch.limit(1);
                AL10.alSourceUnqueueBuffers(sourceId, queueScratch);
                pool.release(queueScratch.get(0));
            }
            playing = false;
        }

        int n = pcm.length;
        int buffer = pool.acquire();
        ShortBuffer sbuf = BufferUtils.createShortBuffer(n);
        sbuf.put(pcm, 0, n);
        sbuf.flip();
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, sbuf, sampleRateHz);
        AudioSubsystem.checkAlError("alBufferData");
        queueScratch.clear();
        queueScratch.put(0, buffer);
        queueScratch.position(0)
            .limit(1);
        AL10.alSourceQueueBuffers(sourceId, queueScratch);
        AudioSubsystem.checkAlError("alSourceQueueBuffers");

        if (!playing) {
            AL10.alSourcePlay(sourceId);
            playing = true;
        } else {
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                HertzianDynamics.LOGGER.debug("Source {} underran; restarting", sourceId);
                AL10.alSourcePlay(sourceId);
            }
        }
    }

    public void tick() {
        if (disposed) return;
        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);
        while (processed > 0) {
            queueScratch.clear();
            queueScratch.limit(1);
            AL10.alSourceUnqueueBuffers(sourceId, queueScratch);
            pool.release(queueScratch.get(0));
            processed--;
        }
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        AL10.alSourceStop(sourceId);
        int queued = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            queueScratch.clear();
            queueScratch.limit(1);
            AL10.alSourceUnqueueBuffers(sourceId, queueScratch);
            pool.release(queueScratch.get(0));
        }
        AL10.alDeleteSources(sourceId);
    }
}
