package io.hertzian.dynamics.net;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.HertzianRefs;

/**
 * Network channel and packet registry.
 *
 * <p>
 * One {@link SimpleNetworkWrapper} channel per mod. Packet
 * discriminator ids are assigned in {@link #register} and must
 * never be reordered or reused; the server and client agree on the
 * id-to-class mapping by registration order. New packet types
 * append at the next free id.
 */
public final class NetworkHandler {

    public static SimpleNetworkWrapper CHANNEL;

    private NetworkHandler() {}

    public static void register() {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(HertzianRefs.MODID);
        int id = 0;

        CHANNEL.registerMessage(PacketAudioChunk.Handler.class, PacketAudioChunk.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketReceiverSettings>(new PacketReceiverSettings.Handler()) {},
                PacketReceiverSettings.class,
                id++);
        server(
                new DeferredServerHandler<PacketTransmitterSettings>(new PacketTransmitterSettings.Handler()) {},
                PacketTransmitterSettings.class,
                id++);
        server(
                new DeferredServerHandler<PacketJammerSettings>(new PacketJammerSettings.Handler()) {},
                PacketJammerSettings.class,
                id++);
        server(
                new DeferredServerHandler<PacketSpectrumSettings>(new PacketSpectrumSettings.Handler()) {},
                PacketSpectrumSettings.class,
                id++);

        CHANNEL.registerMessage(PacketSpectrumData.Handler.class, PacketSpectrumData.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketVoiceUplink>(new PacketVoiceUplink.Handler()) {},
                PacketVoiceUplink.class,
                id++);
        server(
                new DeferredServerHandler<PacketHandheldSettings>(new PacketHandheldSettings.Handler()) {},
                PacketHandheldSettings.class,
                id++);

        CHANNEL.registerMessage(PacketReceiverScope.Handler.class, PacketReceiverScope.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketRelaySettings>(new PacketRelaySettings.Handler()) {},
                PacketRelaySettings.class,
                id++);
        server(
                new DeferredServerHandler<PacketStationControl>(new PacketStationControl.Handler()) {},
                PacketStationControl.class,
                id++);

        CHANNEL.registerMessage(PacketStationList.Handler.class, PacketStationList.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketReceiverStorePreset>(new PacketReceiverStorePreset.Handler()) {},
                PacketReceiverStorePreset.class,
                id++);

        CHANNEL.registerMessage(PacketTeletypeData.Handler.class, PacketTeletypeData.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketTeletypeSettings>(new PacketTeletypeSettings.Handler()) {},
                PacketTeletypeSettings.class,
                id++);
        server(
                new DeferredServerHandler<PacketTelegraphKey>(new PacketTelegraphKey.Handler()) {},
                PacketTelegraphKey.class,
                id++);
        server(
                new DeferredServerHandler<PacketRttyControl>(new PacketRttyControl.Handler()) {},
                PacketRttyControl.class,
                id++);

        CHANNEL.registerMessage(PacketRttyData.Handler.class, PacketRttyData.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketDtmfControl>(new PacketDtmfControl.Handler()) {},
                PacketDtmfControl.class,
                id++);

        CHANNEL.registerMessage(PacketDtmfData.Handler.class, PacketDtmfData.class, id++, Side.CLIENT);

        server(
                new DeferredServerHandler<PacketSstvControl>(new PacketSstvControl.Handler()) {},
                PacketSstvControl.class,
                id++);

        CHANNEL.registerMessage(PacketSstvLine.Handler.class, PacketSstvLine.class, id++, Side.CLIENT);
        CHANNEL.registerMessage(PacketSstvStatus.Handler.class, PacketSstvStatus.class, id++, Side.CLIENT);

        // Radio gear sync. Server to client copy of the player's carry
        // slots, read by the push-to-talk handler.
        CHANNEL.registerMessage(PacketRadioGearSync.Handler.class, PacketRadioGearSync.class, id++, Side.CLIENT);

        // Radio gear screen open request. Routed through the server so the
        // gear container exists server side and slot clicks are accepted.
        server(
                new DeferredServerHandler<PacketOpenRadioGear>(new PacketOpenRadioGear.Handler()) {},
                PacketOpenRadioGear.class,
                id++);

        HertzianDynamics.LOGGER.info("Network channel '{}' registered with {} packet types", HertzianRefs.MODID, id);
    }

    /**
     * Register one server bound packet whose handler defers to the main
     * thread. The handler passed in must be a distinct (anonymous) wrapper
     * subclass per call site, because SimpleNetworkWrapper names the netty
     * pipeline entry by the handler's concrete class name and rejects
     * duplicates.
     */
    private static <REQ extends IMessage> void server(IMessageHandler<REQ, IMessage> handler, Class<REQ> type, int id) {
        CHANNEL.registerMessage(handler, type, id, Side.SERVER);
    }
}