package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileRadioReceiver;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: write one receiver preset slot. Sent when the player
 * stores the current tuning into a memory button in the receiver
 * GUI. Recall does not need a packet (it is an ordinary tuning change
 * carried by {@link PacketReceiverSettings}); only the store writes
 * persistent server state, so it gets its own small packet.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int     dimensionId
 *   int     x, y, z
 *   int     index          preset slot, 0 to 5
 *   double  hz
 *   float   bandwidthHz
 *   int     modulationCode
 * </pre>
 */
public final class PacketReceiverStorePreset implements IMessage {

    private int dim, x, y, z, index, modCode;
    private double hz;
    private float bandwidthHz;

    public PacketReceiverStorePreset() {}

    public PacketReceiverStorePreset(int dim, int x, int y, int z, int index, double hz, float bandwidthHz,
        int modCode) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.index = index;
        this.hz = hz;
        this.bandwidthHz = bandwidthHz;
        this.modCode = modCode;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(index);
        buf.writeDouble(hz);
        buf.writeFloat(bandwidthHz);
        buf.writeInt(modCode);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        index = buf.readInt();
        hz = buf.readDouble();
        bandwidthHz = buf.readFloat();
        modCode = buf.readInt();
    }

    public static final class Handler implements IMessageHandler<PacketReceiverStorePreset, IMessage> {

        @Override
        public IMessage onMessage(PacketReceiverStorePreset msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileRadioReceiver) {
                ((TileRadioReceiver) te).setPreset(msg.index, msg.hz, msg.bandwidthHz, msg.modCode);
            }
            return null;
        }
    }
}
