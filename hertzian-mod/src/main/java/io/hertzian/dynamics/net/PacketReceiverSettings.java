package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.tile.TileRadioReceiver;
import io.netty.buffer.ByteBuf;

/**
 * Client-to-server packet: receiver tuning and conditioning change. Sent
 * when the player presses a step button, cycles modulation, switches
 * bandwidth, steps the noise-reduction control, or moves the volume
 * slider in {@link io.hertzian.dynamics.gui.GuiRadioReceiver}.
 *
 * <p>
 * Wire layout (volume revision):
 * 
 * <pre>
 *   int     dimensionId
 *   int     x, y, z
 *   double  tunedHz
 *   float   bandwidthHz
 *   int     modulationCode
 *   float   nrStrength      wideband noise reduction, 0 off to 1 max
 *   float   volume          output level, 0 mute to 1 full
 * </pre>
 *
 * <p>
 * The {@code nrStrength} field drives the receiver-side noise
 * reduction, an SNR-keyed downward gain that softens loud static on a
 * weak channel while leaving a strong signal untouched.
 *
 * <p>
 * The {@code volume} field was added with the GUI rebuild. It scales
 * the demodulated PCM on the server before broadcast, so a block speaker
 * plays at one level for every listener around it. Both controls ride in
 * this packet with the tuning because the GUI mutates them through the
 * same widget bank and shipping the whole settings block keeps the
 * handler a single straight-line write with no per-field branching.
 */
public final class PacketReceiverSettings implements IMessage {

    private int dim, x, y, z, modCode;
    private double tunedHz;
    private float bandwidthHz;
    private float nrStrength;
    private float volume;

    public PacketReceiverSettings() {}

    public PacketReceiverSettings(int dim, int x, int y, int z, double tunedHz, float bandwidthHz, int modCode,
        float nrStrength, float volume) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tunedHz = tunedHz;
        this.bandwidthHz = bandwidthHz;
        this.modCode = modCode;
        this.nrStrength = nrStrength;
        this.volume = volume;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(tunedHz);
        buf.writeFloat(bandwidthHz);
        buf.writeInt(modCode);
        buf.writeFloat(nrStrength);
        buf.writeFloat(volume);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        tunedHz = buf.readDouble();
        bandwidthHz = buf.readFloat();
        modCode = buf.readInt();
        nrStrength = buf.readFloat();
        volume = buf.readFloat();
    }

    public static final class Handler implements IMessageHandler<PacketReceiverSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketReceiverSettings msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileRadioReceiver) {
                TileRadioReceiver rx = (TileRadioReceiver) te;
                rx.setTunedHz(msg.tunedHz);
                rx.setBandwidthHz(msg.bandwidthHz);
                rx.setNoiseReduction(msg.nrStrength);
                rx.setVolume(msg.volume);
                try {
                    rx.setModulation(Modulation.fromCode(msg.modCode));
                } catch (IllegalArgumentException ignore) {}
                world.markBlockForUpdate(msg.x, msg.y, msg.z);
            }
            return null;
        }
    }
}
