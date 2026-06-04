package io.hertzian.dynamics.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.hertzian.dynamics.audio.AudioSubsystem;

/**
 * Drives {@link AudioSubsystem#tick} from the client tick event.
 *
 * <p>
 * One tick per client frame is sufficient for streaming audio
 * because the buffer queue absorbs jitter up to one chunk's worth
 * of latency (~50 ms at the default 2400-sample chunk size).
 *
 * <p>
 * Listening to {@link TickEvent.ClientTickEvent#phase}=END
 * means we run after the renderer has updated the camera, so the
 * listener sync sees the freshest position. {@link TickEvent.Phase#START}
 * would sync to last frame's position and produce a one-frame
 * lag in the AL listener.
 */
public final class ClientAudioTickHandler {

    private static final ClientAudioTickHandler INSTANCE = new ClientAudioTickHandler();
    private boolean registered = false;

    private ClientAudioTickHandler() {}

    public static void register() {
        if (!INSTANCE.registered) {
            FMLCommonHandler.instance()
                .bus()
                .register(INSTANCE);
            INSTANCE.registered = true;
        }
    }

    public static void unregister() {
        if (INSTANCE.registered) {
            FMLCommonHandler.instance()
                .bus()
                .unregister(INSTANCE);
            INSTANCE.registered = false;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        AudioSubsystem sub = AudioSubsystem.get();
        if (sub != null) sub.tick();
    }
}
