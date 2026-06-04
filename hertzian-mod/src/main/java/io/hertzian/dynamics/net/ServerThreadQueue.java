package io.hertzian.dynamics.net;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bridges server bound packet handlers from the netty thread to the
 * server main thread.
 *
 * <p>
 * In Minecraft 1.7.10 SimpleNetworkWrapper dispatches a server bound
 * IMessageHandler on the network thread, not the main server thread.
 * The rf-core SpectrumManager is single threaded by contract, mixed
 * once per tick on the main thread, so a handler that mutates tile
 * state or calls into the native manager must not run concurrently from
 * netty. There is no IThreadListener in 1.7.10, so the deferring wrapper
 * enqueues each handler body here and the server tick drains the queue
 * at the start of the next tick, serialising every deferred task against
 * the mix loop.
 */
public final class ServerThreadQueue {

    private static final ConcurrentLinkedQueue<Runnable> TASKS = new ConcurrentLinkedQueue<>();

    private ServerThreadQueue() {}

    public static void enqueue(Runnable task) {
        if (task != null) TASKS.add(task);
    }

    /**
     * Run every queued task. Called once per server tick on the main
     * thread before the radio devices are ticked, so settings changes
     * land before the mix that consumes them. Each task is guarded so a
     * single failing handler does not drop the rest of the batch.
     */
    public static void drain() {
        Runnable task;
        while ((task = TASKS.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                io.hertzian.dynamics.HertzianDynamics.LOGGER.error("Deferred packet task failed", t);
            }
        }
    }
}
