package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileSstvStation;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: SSTV station status, carrying everything the
 * GUI needs that is not an image line. Beyond the signal meter and the
 * transmit progress, it now ships the send-queue contents and the frame
 * indices so the GUI can render the queue panel and label the received
 * frames without any extra packet.
 *
 * <p>
 * Wire layout
 * 
 * <pre>
 *   int     dimensionId
 *   int     x, y, z
 *   float   snrDb
 *   float   txProgress       progress of the frame currently transmitting
 *   boolean receiving        decoder holds a lock
 *   int     queueSize        number of queued frames (at most five)
 *   byte[]  queueKinds       one kind byte per queued frame
 *   int     txFrameIndex     index of the frame being transmitted, or -1
 *   int     rxFrameSeq       decoder's current frame number
 * </pre>
 *
 * <p>
 * The queue is described only by its kind bytes, never its pixels, so
 * even the status stays tiny: a pattern slot is one byte and a map slot is
 * one byte, the client redraws pattern thumbnails locally and shows a
 * placeholder for map slots.
 */
public final class PacketSstvStatus implements IMessage {

    private int dim;
    private int x;
    private int y;
    private int z;
    private float snrDb;
    private float txProgress;
    private boolean receiving;
    private byte[] queueKinds;
    private int txFrameIndex;
    private int rxFrameSeq;

    public PacketSstvStatus() {}

    public PacketSstvStatus(int dim, int x, int y, int z, float snrDb, float txProgress, boolean receiving,
        byte[] queueKinds, int txFrameIndex, int rxFrameSeq) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.snrDb = snrDb;
        this.txProgress = txProgress;
        this.receiving = receiving;
        this.queueKinds = queueKinds == null ? new byte[0] : queueKinds;
        this.txFrameIndex = txFrameIndex;
        this.rxFrameSeq = rxFrameSeq;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeFloat(snrDb);
        buf.writeFloat(txProgress);
        buf.writeBoolean(receiving);
        buf.writeInt(queueKinds.length);
        buf.writeBytes(queueKinds);
        buf.writeInt(txFrameIndex);
        buf.writeInt(rxFrameSeq);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        snrDb = buf.readFloat();
        txProgress = buf.readFloat();
        receiving = buf.readBoolean();
        int n = buf.readInt();
        if (n < 0 || n > 16) {
            n = 0;
        }
        queueKinds = new byte[n];
        buf.readBytes(queueKinds);
        txFrameIndex = buf.readInt();
        rxFrameSeq = buf.readInt();
    }

    public static final class Handler implements IMessageHandler<PacketSstvStatus, IMessage> {

        @Override
        public IMessage onMessage(PacketSstvStatus m, MessageContext ctx) {
            try {
                World world = Minecraft.getMinecraft().theWorld;
                if (world == null || world.provider.dimensionId != m.dim) {
                    return null;
                }
                TileEntity te = world.getTileEntity(m.x, m.y, m.z);
                if (te instanceof TileSstvStation) {
                    ((TileSstvStation) te).setClientStatus(
                        m.snrDb,
                        m.txProgress,
                        m.receiving,
                        m.queueKinds,
                        m.txFrameIndex,
                        m.rxFrameSeq);
                }
            } catch (Throwable ignore) {}
            return null;
        }
    }
}
