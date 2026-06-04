package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileTeletype;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: telegraph receiver tuning change, or a request to
 * clear the decoded text.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int     dimensionId
 *   int     x, y, z
 *   double  tunedHz
 *   float   bandwidthHz
 *   boolean clear        true to wipe the decoded buffer
 * </pre>
 *
 * <p>
 * One packet covers both because the GUI mutates tuning and clears
 * text through the same button bank, and folding the clear flag in keeps
 * the handler a single straight-line write. When {@code clear} is set the
 * tuning fields are still applied first, so a clear never silently drops a
 * pending retune.
 */
public final class PacketTeletypeSettings implements IMessage {

    private int dim, x, y, z;
    private double tunedHz;
    private float bandwidthHz;
    private boolean clear;

    public PacketTeletypeSettings() {}

    public PacketTeletypeSettings(int dim, int x, int y, int z, double tunedHz, float bandwidthHz, boolean clear) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tunedHz = tunedHz;
        this.bandwidthHz = bandwidthHz;
        this.clear = clear;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(tunedHz);
        buf.writeFloat(bandwidthHz);
        buf.writeBoolean(clear);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        tunedHz = buf.readDouble();
        bandwidthHz = buf.readFloat();
        clear = buf.readBoolean();
    }

    public static final class Handler implements IMessageHandler<PacketTeletypeSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketTeletypeSettings msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileTeletype) {
                TileTeletype tt = (TileTeletype) te;
                tt.setTunedHz(msg.tunedHz);
                tt.setBandwidthHz(msg.bandwidthHz);
                if (msg.clear) tt.clearText();
                world.markBlockForUpdate(msg.x, msg.y, msg.z);
            }
            return null;
        }
    }
}
