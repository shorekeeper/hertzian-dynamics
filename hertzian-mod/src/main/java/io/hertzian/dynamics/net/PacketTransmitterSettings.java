package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.tile.TileRadioTransmitter;
import io.netty.buffer.ByteBuf;

/**
 * Client to server sync of the transmitter's carrier-side settings.
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int      dimensionId
 *   int      x, y, z
 *   double   carrierHz
 *   int      modulationCode
 *   float    txPowerW
 *   boolean  broadcasting
 *   int      modeCode          (0 NORMAL, 1 STATION)
 *   UTF8     stationName       (embedded in the signal, both modes)
 * </pre>
 *
 * <p>
 * The packet carries the full settings block on every change rather
 * than a delta. The block is small and the change rate is a handful of
 * button presses per second, so shipping everything keeps the handler
 * trivial: there is no question of which field changed, just write them
 * all. Station transport actions (play, pause, seek, track select) do
 * not travel here; they fire on their own cadence through
 * {@link PacketStationControl}, leaving this packet for the settings a
 * GUI button mutates.
 *
 * <p>
 * The {@code broadcasting} flag force loads the transmitter's chunk
 * so it keeps ticking and transmitting while no player is nearby, which
 * is what makes a distant link or a station reachable after the player
 * walks away. The {@code modeCode} and {@code stationName} fields were
 * present in code tho: switching into STATION mode turns on broadcast
 * automatically server side, and the name is encoded onto the signal as
 * an inaudible subcarrier by the transmitter regardless of mode.
 */
public final class PacketTransmitterSettings implements IMessage {

    private int dim, x, y, z, modCode;
    private double carrierHz;
    private float power;
    private boolean broadcasting;
    private int modeCode;
    private String stationName;

    public PacketTransmitterSettings() {}

    public PacketTransmitterSettings(int dim, int x, int y, int z, double carrierHz, int modCode, float power,
        boolean broadcasting, int modeCode, String stationName) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.carrierHz = carrierHz;
        this.modCode = modCode;
        this.power = power;
        this.broadcasting = broadcasting;
        this.modeCode = modeCode;
        this.stationName = stationName == null ? "" : stationName;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(carrierHz);
        buf.writeInt(modCode);
        buf.writeFloat(power);
        buf.writeBoolean(broadcasting);
        buf.writeInt(modeCode);
        ByteBufUtils.writeUTF8String(buf, stationName == null ? "" : stationName);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        carrierHz = buf.readDouble();
        modCode = buf.readInt();
        power = buf.readFloat();
        broadcasting = buf.readBoolean();
        modeCode = buf.readInt();
        stationName = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketTransmitterSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketTransmitterSettings msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileRadioTransmitter) {
                TileRadioTransmitter tx = (TileRadioTransmitter) te;
                tx.setCarrierHz(msg.carrierHz);
                tx.setTxPowerW(msg.power);
                tx.setBroadcasting(msg.broadcasting);
                tx.setMode(msg.modeCode == 1 ? TileRadioTransmitter.Mode.STATION : TileRadioTransmitter.Mode.NORMAL);
                tx.setStationName(msg.stationName);
                try {
                    tx.setModulation(Modulation.fromCode(msg.modCode));
                } catch (IllegalArgumentException ignore) {}
                world.markBlockForUpdate(msg.x, msg.y, msg.z);
            }
            return null;
        }
    }
}
