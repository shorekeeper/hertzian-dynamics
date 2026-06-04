package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileSpectrumAnalyzer;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: a fresh spectrum frame for the analyzer at the given
 * block.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int      dimensionId
 *   int      x, y, z
 *   double   centerHz       centre the frame was computed at
 *   double   spanHz         span the frame was computed at
 *   int      binCount
 *   float[]  spectrum magnitudes, binCount of them
 *   UTF8     stationName    decoded from the centred signal, empty if none
 * </pre>
 *
 * <p>
 * Handler note
 * The handler calls {@link TileSpectrumAnalyzer#applySpectrumFrame},
 * which updates the magnitudes and the frame metadata but leaves the
 * live tuning ({@code centerHz}, {@code spanHz}) under client control.
 * The GUI owns the tuning while open so its axis and cursor follow
 * input instantly; the waterfall data trails by at most one frame.
 * Writing the frame's tuning onto the client tile would fight the
 * client's optimistic zoom and pan, because each frame is computed
 * one round trip behind and would pull the tuning back to the value
 * in flight, making the cursor flicker between the two.
 */
public final class PacketSpectrumData implements IMessage {

    private int dim, x, y, z;
    private double centerHz, spanHz;
    private float[] spectrum;
    private String stationName;

    public PacketSpectrumData() {}

    public PacketSpectrumData(int dim, int x, int y, int z, double centerHz, double spanHz, float[] spectrum,
        String stationName) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.centerHz = centerHz;
        this.spanHz = spanHz;
        this.spectrum = spectrum;
        this.stationName = stationName == null ? "" : stationName;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(centerHz);
        buf.writeDouble(spanHz);
        buf.writeInt(spectrum.length);
        for (float v : spectrum) buf.writeFloat(v);
        ByteBufUtils.writeUTF8String(buf, stationName == null ? "" : stationName);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        centerHz = buf.readDouble();
        spanHz = buf.readDouble();
        int n = buf.readInt();
        // Allow up to 4096 bins. The current analyzer ships 192 per
        // packet, the cap leaves headroom for future high-resolution
        // modes without changing the packet schema. A malicious packet
        // with a huge bin count is still bounded by the cap so the
        // receiving client cannot be forced to allocate gigabyte buffers.
        if (n < 0 || n > 4096) throw new IllegalArgumentException("PacketSpectrumData bins " + n);
        spectrum = new float[n];
        for (int i = 0; i < n; i++) spectrum[i] = buf.readFloat();
        stationName = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketSpectrumData, IMessage> {

        @Override
        public IMessage onMessage(PacketSpectrumData msg, MessageContext ctx) {
            try {
                // The integrated server can dispatch the packet before the
                // client world has the TE loaded yet; a null TE just means
                // the data is dropped.
                World world = Minecraft.getMinecraft().theWorld;
                if (world == null || world.provider.dimensionId != msg.dim) return null;
                TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
                if (te instanceof TileSpectrumAnalyzer) {
                    // Plain field updates from the netty thread are safe
                    // here: the GUI reads them under the client tick and the
                    // worst case is a torn read of one float or a stale
                    // string reference. The live tuning is deliberately not
                    // touched, see the class note above.
                    ((TileSpectrumAnalyzer) te)
                        .applySpectrumFrame(msg.spectrum, msg.centerHz, msg.spanHz, msg.stationName);
                }
            } catch (Throwable ignore) {}
            return null;
        }
    }
}
