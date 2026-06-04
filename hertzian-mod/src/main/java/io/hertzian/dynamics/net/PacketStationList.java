package io.hertzian.dynamics.net;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.gui.GuiRadioTransmitter;
import io.netty.buffer.ByteBuf;

/**
 * Server to client list of available .qoa tracks, sent in reply to a
 * PacketStationControl LIST request when the station GUI opens. The GUI
 * reads it from a static holder; the list is small (the contents of one
 * folder) so it travels whole.
 */
public final class PacketStationList implements IMessage {

    private List<String> tracks = new ArrayList<>();

    public PacketStationList() {}

    public PacketStationList(List<String> tracks) {
        this.tracks = tracks;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(tracks.size());
        for (String t : tracks) ByteBufUtils.writeUTF8String(buf, t);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        if (n < 0 || n > 4096) n = 0;
        tracks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) tracks.add(ByteBufUtils.readUTF8String(buf));
    }

    public static final class Handler implements IMessageHandler<PacketStationList, IMessage> {

        @Override
        public IMessage onMessage(PacketStationList msg, MessageContext ctx) {
            GuiRadioTransmitter.setAvailableTracks(msg.tracks);
            return null;
        }
    }
}
