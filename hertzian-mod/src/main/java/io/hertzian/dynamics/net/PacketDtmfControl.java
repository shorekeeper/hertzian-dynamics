package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileDtmfPad;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: DTMF pad control. {@code action} chooses tuning, a
 * single key press, sending a whole sequence, setting the selcall code,
 * or clearing the received digits. {@code text} carries the key, sequence
 * or code as appropriate.
 */
public final class PacketDtmfControl implements IMessage {

    public static final int TUNE = 0, KEY = 1, SEQ = 2, SET_CODE = 3, CLEAR_RX = 4;
    private int dim, x, y, z, action;
    private double tunedHz;
    private float bandwidthHz;
    private String text;

    public PacketDtmfControl() {}

    public PacketDtmfControl(int dim, int x, int y, int z, int action, double tunedHz, float bandwidthHz, String text) {
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

    public static final class Handler implements IMessageHandler<PacketDtmfControl, IMessage> {

        @Override
        public IMessage onMessage(PacketDtmfControl m, MessageContext ctx) {
            World w = ctx.getServerHandler().playerEntity.worldObj;
            if (w.provider.dimensionId != m.dim) return null;
            TileEntity te = w.getTileEntity(m.x, m.y, m.z);
            if (!(te instanceof TileDtmfPad)) return null;
            TileDtmfPad t = (TileDtmfPad) te;
            switch (m.action) {
                case TUNE:
                    t.setTunedHz(m.tunedHz);
                    t.setBandwidthHz(m.bandwidthHz);
                    w.markBlockForUpdate(m.x, m.y, m.z);
                    break;
                case KEY:
                    if (!m.text.isEmpty()) t.queueDigit(m.text.charAt(0));
                    break;
                case SEQ:
                    t.queueSequence(m.text);
                    break;
                case SET_CODE:
                    t.setSelcallCode(m.text);
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
