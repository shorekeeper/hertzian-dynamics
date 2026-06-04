package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileRttyTerminal;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: RTTY terminal control. One packet covers tuning and
 * the three transmit actions (send text, clear the send queue, clear the
 * received buffer), because they all mutate the same tile and the set is
 * small. {@code action} selects the operation; {@code text} carries the
 * message for a send.
 */
public final class PacketRttyControl implements IMessage {

    public static final int TUNE = 0, SEND = 1, CLEAR_TX = 2, CLEAR_RX = 3;
    private int dim, x, y, z, action;
    private double tunedHz;
    private float bandwidthHz;
    private String text;

    public PacketRttyControl() {}

    public PacketRttyControl(int dim, int x, int y, int z, int action, double tunedHz, float bandwidthHz, String text) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.tunedHz = tunedHz;
        this.bandwidthHz = bandwidthHz;
        this.text = text == null ? "" : text;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(action);
        buf.writeDouble(tunedHz);
        buf.writeFloat(bandwidthHz);
        ByteBufUtils.writeUTF8String(buf, text);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readInt();
        tunedHz = buf.readDouble();
        bandwidthHz = buf.readFloat();
        text = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketRttyControl, IMessage> {

        @Override
        public IMessage onMessage(PacketRttyControl m, MessageContext ctx) {
            World w = ctx.getServerHandler().playerEntity.worldObj;
            if (w.provider.dimensionId != m.dim) return null;
            TileEntity te = w.getTileEntity(m.x, m.y, m.z);
            if (!(te instanceof TileRttyTerminal)) return null;
            TileRttyTerminal t = (TileRttyTerminal) te;
            switch (m.action) {
                case TUNE:
                    t.setTunedHz(m.tunedHz);
                    t.setBandwidthHz(m.bandwidthHz);
                    w.markBlockForUpdate(m.x, m.y, m.z);
                    break;
                case SEND:
                    t.queueText(m.text);
                    break;
                case CLEAR_TX:
                    t.clearTx();
                    break;
                case CLEAR_RX:
                    t.clearRx();
                    break;
                default:
                    break;
            }
            return null;
        }
    }
}
