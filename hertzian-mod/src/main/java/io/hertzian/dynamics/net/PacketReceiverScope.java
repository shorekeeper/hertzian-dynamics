package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileRadioReceiver;
import io.netty.buffer.ByteBuf;

/**
 * Server to client live status feed for a receiver, the data behind the
 * GUI S-meter, the scrolling signal graph and the station name line.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int   dimensionId
 *   int   x, y, z
 *   float snrDb           signal to noise ratio of the last chunk, dB
 *   UTF8  stationName     name decoded from the signal, empty if none
 * </pre>
 *
 * <p>
 * The receiver's mixed chunk, with its pre-AGC signal power and noise
 * floor, lives only on the server, so the client cannot compute the
 * signal to noise ratio itself; the post-AGC samples it does receive
 * have been normalised to a fixed target and carry no strength
 * information. This packet ships the measured ratio a few times a second
 * to nearby clients so the meter has real data instead of the flat zero
 * the earlier client side estimate produced.
 *
 * <p>
 * The receiver demodulates the embedded station identifier subcarrier from
 * the raw signal; the recovered name (empty when no station is decoding
 * or the signal is too weak for the CRC to pass) travels alongside the
 * S-meter value so the GUI can show what is being received without a
 * second packet type.
 */
public final class PacketReceiverScope implements IMessage {

    private int dim, x, y, z;
    private float snrDb;
    private String stationName;

    public PacketReceiverScope() {}

    public PacketReceiverScope(int dim, int x, int y, int z, float snrDb, String stationName) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.snrDb = snrDb;
        this.stationName = stationName == null ? "" : stationName;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeFloat(snrDb);
        ByteBufUtils.writeUTF8String(buf, stationName == null ? "" : stationName);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        snrDb = buf.readFloat();
        stationName = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketReceiverScope, IMessage> {

        @Override
        public IMessage onMessage(PacketReceiverScope msg, MessageContext ctx) {
            try {
                World world = Minecraft.getMinecraft().theWorld;
                if (world == null || world.provider.dimensionId != msg.dim) return null;
                TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
                if (te instanceof TileRadioReceiver) {
                    TileRadioReceiver rx = (TileRadioReceiver) te;
                    rx.setClientSnrDb(msg.snrDb);
                    rx.setClientStationName(msg.stationName);
                }
            } catch (Throwable ignore) {}
            return null;
        }
    }
}
