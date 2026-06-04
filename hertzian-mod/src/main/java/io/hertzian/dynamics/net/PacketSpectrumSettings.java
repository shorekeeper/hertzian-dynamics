package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.tile.TileSpectrumAnalyzer;
import io.netty.buffer.ByteBuf;

/**
 * Client to server sync of the spectrum analyzer configuration.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int      dimensionId
 *   int      x, y, z
 *   double   centerHz
 *   double   spanHz
 *   boolean  agcBypass
 *   int      modulationCode
 * </pre>
 *
 * <p>
 * The packet carries the full analyzer state on every change
 * rather than a delta because the surface is tiny (40 bytes total)
 * and the change rate is low (one packet per button press, perhaps
 * a handful per second during a fast scroll-zoom). Carrying the
 * full state simplifies the handler: there is no question of
 * which field changed, just write everything.
 */
public final class PacketSpectrumSettings implements IMessage {

    private int dim, x, y, z;
    private double centerHz, spanHz;
    private boolean agcBypass;
    private int modCode;

    public PacketSpectrumSettings() {}

    public PacketSpectrumSettings(int dim, int x, int y, int z, double centerHz, double spanHz, boolean agcBypass,
        int modCode) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.centerHz = centerHz;
        this.spanHz = spanHz;
        this.agcBypass = agcBypass;
        this.modCode = modCode;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(centerHz);
        buf.writeDouble(spanHz);
        buf.writeBoolean(agcBypass);
        buf.writeInt(modCode);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        centerHz = buf.readDouble();
        spanHz = buf.readDouble();
        agcBypass = buf.readBoolean();
        modCode = buf.readInt();
    }

    public static final class Handler implements IMessageHandler<PacketSpectrumSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketSpectrumSettings msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileSpectrumAnalyzer) {
                TileSpectrumAnalyzer sa = (TileSpectrumAnalyzer) te;
                sa.setCenterHz(msg.centerHz);
                sa.setSpanHz(msg.spanHz);
                sa.setAgcBypass(msg.agcBypass);
                // Modulation code is validated by Modulation.fromCode;
                // an unknown value is silently ignored so a stray
                // packet from a future ABI does not crash the
                // current handler.
                try {
                    sa.setAnalyzerModulation(Modulation.fromCode(msg.modCode));
                } catch (IllegalArgumentException ignore) {}
            }
            return null;
        }
    }
}
