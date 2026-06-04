package io.hertzian.dynamics.net;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.world.HandheldRadioRegistry;
import io.netty.buffer.ByteBuf;

/**
 * Client-to-server voice samples from microphone capture. Sent
 * while the PTT key is held and a powered Handheld Radio is in
 * the player's hand. The handler routes the PCM into the
 * server-side handheld radio entity for that player, which feeds
 * it into the player's transmitter slot.
 */
public final class PacketVoiceUplink implements IMessage {

    private float[] pcm;

    public PacketVoiceUplink() {}

    public PacketVoiceUplink(float[] pcm) {
        this.pcm = pcm;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(pcm.length);
        for (float v : pcm) buf.writeShort(quantise(v));
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        if (n < 0 || n > 65_536) throw new IllegalArgumentException("PacketVoiceUplink length " + n);
        pcm = new float[n];
        for (int i = 0; i < n; i++) pcm[i] = buf.readShort() / 32768f;
    }

    private static short quantise(float v) {
        if (v > 1f) v = 1f;
        else if (v < -1f) v = -1f;
        return (short) (v * 32767f);
    }

    public static final class Handler implements IMessageHandler<PacketVoiceUplink, IMessage> {

        @Override
        public IMessage onMessage(PacketVoiceUplink msg, MessageContext ctx) {
            try {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                HandheldRadioRegistry.deliverVoice(player, msg.pcm);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.error("voice uplink handler failed", t);
            }
            return null;
        }
    }
}
