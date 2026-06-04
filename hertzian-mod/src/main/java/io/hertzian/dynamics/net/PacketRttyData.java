package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileRttyTerminal;
import io.netty.buffer.ByteBuf;

/** Server to client: RTTY terminal live state for the GUI. */
public final class PacketRttyData implements IMessage {

    private int dim, x, y, z;
    private float snrDb, level;
    private boolean sending;
    private String text;

    public PacketRttyData() {}

    public PacketRttyData(int dim, int x, int y, int z, float snrDb, float level, boolean sending, String text) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.snrDb = snrDb;
        this.level = level;
        this.sending = sending;
        this.text = text == null ? "" : text;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeFloat(snrDb);
        buf.writeFloat(level);
        buf.writeBoolean(sending);
        ByteBufUtils.writeUTF8String(buf, text);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        snrDb = buf.readFloat();
        level = buf.readFloat();
        sending = buf.readBoolean();
        text = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketRttyData, IMessage> {

        @Override
        public IMessage onMessage(PacketRttyData m, MessageContext ctx) {
            try {
                World w = Minecraft.getMinecraft().theWorld;
                if (w == null || w.provider.dimensionId != m.dim) return null;
                TileEntity te = w.getTileEntity(m.x, m.y, m.z);
                if (te instanceof TileRttyTerminal)
                    ((TileRttyTerminal) te).setClientData(m.text, m.snrDb, m.level, m.sending);
            } catch (Throwable ignore) {}
            return null;
        }
    }
}
