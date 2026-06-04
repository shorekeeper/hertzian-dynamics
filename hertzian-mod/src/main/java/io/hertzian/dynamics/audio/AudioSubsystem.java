package io.hertzian.dynamics.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

import io.hertzian.dynamics.HertzianDynamics;

/**
 * Client-side audio subsystem. Bridges rf-core spectrum chunks to
 * OpenAL playback.
 *
 * <p>
 * Architecture note on LWJGL versions
 * ------------------------------------
 * The mod is compiled against the LWJGL2 API surface that ships
 * with patched Minecraft 1.7.10 sources, but at runtime the
 * {@code lwjgl3ify} mod's {@code LwjglRedirectTransformer} rewrites
 * every {@code org/lwjgl/*} class reference into {@code org/lwjglx/*},
 * where a shim proxies the LWJGL2-shaped calls into the real LWJGL3
 * native bindings. As a result every OpenAL call here uses the
 * LWJGL2 forms: {@link AL10} with {@link java.nio.ShortBuffer} /
 * {@link java.nio.FloatBuffer} parameters, {@link AL#isCreated()}
 * and {@link AL#getDevice()} for capability probing, and so on.
 * Writing the code against LWJGL3 APIs directly would not compile
 * because those classes are absent from the dev classpath.
 *
 * <p>
 * Lifecycle: initialised in {@link io.hertzian.dynamics.proxy.ClientProxy}
 * during postInit, after Paul's Sound System has set up the global
 * OpenAL context. The subsystem deliberately does not create its
 * own ALC device or context; doing so would either fail because the
 * default device is already opened by Minecraft, or create a
 * second context that competes with vanilla audio.
 *
 * <p>
 * Capability probing: a single {@link AL#isCreated()} call. If
 * the AL context is missing (dedicated server, broken client audio
 * setup) the subsystem enters a disabled state and every public
 * method becomes a safe no-op.
 *
 * <p>
 * Threading: every AL call must run on the client main thread
 * because the AL context is thread-bound. The
 * {@link io.hertzian.dynamics.client.ClientAudioTickHandler} dispatches
 * tick events on the right thread. AL is not thread safe for
 * concurrent source mutation, so the subsystem never spawns its
 * own worker threads.
 */
public final class AudioSubsystem {

    private static AudioSubsystem instance;

    private final boolean available;
    private final BufferPool bufferPool;
    private final ListenerSync listenerSync;

    private AudioSubsystem(boolean available, BufferPool bufferPool, ListenerSync listenerSync) {
        this.available = available;
        this.bufferPool = bufferPool;
        this.listenerSync = listenerSync;
    }

    // AL.isCreated() returns true once Paul's Sound System
    // has finished bringing the OpenAL context up. AL.create()
    // is not called here; piggybacking on the existing context
    // is required to coexist with vanilla sound effects.
    public static synchronized AudioSubsystem initOnce() {
        if (instance != null) return instance;

        boolean available;
        try {
            // AL.isCreated() returns true once Paul's Sound System
            // has finished bringing the OpenAL context up. We do
            // not call AL.create() ourselves; piggybacking on the
            // existing context is required to coexist with vanilla
            // sound effects.
            available = AL.isCreated();
            if (available) {
                HertzianDynamics.LOGGER.info("Audio subsystem online: AL context detected, device={}", AL.getDevice());
            } else {
                HertzianDynamics.LOGGER
                    .warn("AL.isCreated() returned false; audio disabled. " + "This is normal on a dedicated server.");
            }
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("AL probe threw; audio disabled. This is normal on a dedicated server.", t);
            available = false;
        }

        BufferPool pool = available ? new BufferPool() : null;
        ListenerSync sync = available ? new ListenerSync() : null;
        instance = new AudioSubsystem(available, pool, sync);
        return instance;
    }

    /** Returns the singleton or null if {@link #initOnce} has not run. */
    public static AudioSubsystem get() {
        return instance;
    }

    /** True if the subsystem found a working AL context at init. */
    public boolean isAvailable() {
        return available;
    }

    BufferPool bufferPool() {
        return bufferPool;
    }

    ListenerSync listenerSync() {
        return listenerSync;
    }

    /**
     * Per client tick callback dispatched from
     * {@link io.hertzian.dynamics.client.ClientAudioTickHandler}. Updates
     * the AL listener and lets each active voice pump its buffer
     * queue.
     */
    public void tick() {
        if (!available) return;
        listenerSync.update();
        ClientAudioBridge.tickAllVoices();
    }

    /**
     * Shut the subsystem down. Safe from any thread because the
     * actual AL deletes happen on the next client tick. The
     * subsystem itself stays usable until {@link #shutdownNow}
     * is called from the client thread.
     */
    public void shutdown() {
        if (!available) return;
        ClientAudioBridge.shutdown();
        HertzianDynamics.LOGGER.info("Audio subsystem shutdown flagged");
    }

    /**
     * Client-thread synchronous teardown. Disposes every live voice
     * immediately. Must run on the client thread because of the AL
     * context binding.
     */
    public void shutdownNow() {
        if (!available) return;
        ClientAudioBridge.shutdownNow();
        bufferPool.shutdown();
        HertzianDynamics.LOGGER.info("Audio subsystem shut down");
    }

    /** Convenience: log a non-fatal AL error code if AL_NO_ERROR is not set. */
    static void checkAlError(String label) {
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR) {
            HertzianDynamics.LOGGER.warn("AL error after {}: code {} ({})", label, err, errorName(err));
        }
    }

    private static String errorName(int err) {
        return switch (err) {
            case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
            default -> "unknown";
        };
    }
}
