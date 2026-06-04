package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileDtmfPad;
import io.netty.buffer.ByteBuf;

/** Server to client: DTMF pad live state. */
public final class PacketDtmfData implements IMessage {

    private int dim, x, y, z;
    private float snrDb;
    private boolean selcall;
    private char last;
    private String digits;

    public PacketDtmfData() {}

    public PacketDtmfData(int dim, int x, int y, int z, float snrDb, boolean selcall, char last, String digits) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.snrDb = snrDb;
        this.selcall = selcall;
        this.last = last;
        this.digits = digits == null ? "" : digits;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeFloat(snrDb);
        buf.writeBoolean(selcall);
        buf.writeChar(last);
        ByteBufUtils.writeUTF8String(buf, digits);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        snrDb = buf.readFloat();
        selcall = buf.readBoolean();
        last = buf.readChar();
        digits = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketDtmfData, IMessage> {

        @Override
        public IMessage onMessage(PacketDtmfData m, MessageContext ctx) {
            try {
                World w = Minecraft.getMinecraft().theWorld;
                if (w == null || w.provider.dimensionId != m.dim) return null;
                TileEntity te = w.getTileEntity(m.x, m.y, m.z);
                if (te instanceof TileDtmfPad) ((TileDtmfPad) te).setClientData(m.digits, m.last, m.snrDb, m.selcall);
            } catch (Throwable ignore) {}
            return null;
        }
    }
}
