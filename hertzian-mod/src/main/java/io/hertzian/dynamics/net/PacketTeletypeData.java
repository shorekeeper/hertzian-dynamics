package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileTeletype;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the live state of a telegraph receiver, the data
 * behind the decoder GUI.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int    dimensionId
 *   int    x, y, z
 *   float  snrDb
 *   float  wpm        estimated sending speed
 *   float  level      key-down fraction of the last chunk, 0..1
 *   UTF8   text       decoded text tail (capped server-side)
 * </pre>
 *
 * <p>
 * The decode runs only on the server (the mixed chunk lives there), so
 * the client cannot reproduce it; this packet carries the result. The
 * text is the tail rather than the whole buffer to keep the packet small
 * while still letting the GUI scroll a useful amount of recent copy.
 */
public final class PacketTeletypeData implements IMessage {

    private int dim, x, y, z;
    private float snrDb, wpm, level;
    private String text;

    public PacketTeletypeData() {}

    public PacketTeletypeData(int dim, int x, int y, int z, float snrDb, float wpm, float level, String text) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.snrDb = snrDb;
        this.wpm = wpm;
        this.level = level;
        this.text = text == null ? "" : text;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeFloat(snrDb);
        buf.writeFloat(wpm);
        buf.writeFloat(level);
        ByteBufUtils.writeUTF8String(buf, text);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        snrDb = buf.readFloat();
        wpm = buf.readFloat();
        level = buf.readFloat();
        text = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketTeletypeData, IMessage> {

        @Override
        public IMessage onMessage(PacketTeletypeData msg, MessageContext ctx) {
            try {
                World world = Minecraft.getMinecraft().theWorld;
                if (world == null || world.provider.dimensionId != msg.dim) return null;
                TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
                if (te instanceof TileTeletype) {
                    ((TileTeletype) te).setClientData(msg.text, msg.wpm, msg.snrDb, msg.level);
                }
            } catch (Throwable ignore) {}
            return null;
        }
    }
}
