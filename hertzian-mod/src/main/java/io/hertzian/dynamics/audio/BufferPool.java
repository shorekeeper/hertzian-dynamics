package io.hertzian.dynamics.audio;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.lwjgl.openal.AL10;

/**
 * Recycles AL buffer ids so the streaming path does not pay
 * {@code alGenBuffers}/{@code alDeleteBuffers} on every chunk.
 *
 * <p>
 * The pool keeps a free list of buffer ids that have been
 * unqueued from a source. {@link ReceiverVoice} acquires a buffer
 * via {@link #acquire}, fills it with PCM data, queues it onto its
 * source, and after the source has processed the buffer returns it
 * here via {@link #release}.
 *
 * <p>
 * {@code outstanding} tracks ids handed out so {@link #shutdown}
 * can delete every buffer the pool ever produced. Without this set
 * a long-running session would slowly leak buffer ids equal to the
 * number of simultaneously playing receivers.
 */
final class BufferPool {

    private final Deque<Integer> free = new ArrayDeque<>();
    private final Set<Integer> outstanding = new HashSet<>();

    int acquire() {
        Integer id = free.poll();
        if (id == null) {
            id = AL10.alGenBuffers();
            AudioSubsystem.checkAlError("alGenBuffers");
            outstanding.add(id);
        }
        return id;
    }

    void release(int id) {
        if (outstanding.contains(id)) {
            free.push(id);
        }
    }

    void shutdown() {
        for (int id : outstanding) {
            AL10.alDeleteBuffers(id);
        }
        free.clear();
        outstanding.clear();
        AudioSubsystem.checkAlError("alDeleteBuffers (pool shutdown)");
    }
}
