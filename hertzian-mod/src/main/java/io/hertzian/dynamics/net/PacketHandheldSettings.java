package io.hertzian.dynamics.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.netty.buffer.ByteBuf;

/**
 * Client-to-server sync of the handheld radio NBT. The server writes the
 * new values into the player-held stack so the
 * {@link io.hertzian.dynamics.world.HandheldRadioRegistry} reads the same
 * configuration on its next tick. The registry compares the values it
 * reads against the ones it last applied and force retunes its rf-core
 * slots when they differ, so a frequency, bandwidth or mode change on the
 * handheld actually reaches the engine rather than being ignored by the
 * cached slot.
 *
 * <p>
 * The packet carries the front panel controls of the portable radio:
 * speaker volume, squelch level, the monitor flag, the roger beep toggle,
 * and the self-monitoring (sidetone) flag. The sidetone is acted on
 * server side in the registry, which decides whether to feed the operator
 * their own audio while transmitting; it is synced here for persistence
 * and so a second client looking at the same stack sees the right state.
 *
 * <p>
 * The settings panel is opened on the held radio, so the handler writes to
 * the held stack. A radio carried in the gear slot is configured by taking
 * it into the hand first.
 */
public final class PacketHandheldSettings implements IMessage {

    private boolean powered;
    private double tunedHz;
    private float bandwidthHz;
    private float txPowerW;
    private int modCode;
    private float volume;
    private float squelch;
    private boolean monitor;
    private boolean rogerBeep;
    private boolean selfMonitor;

    public PacketHandheldSettings() {}

    public PacketHandheldSettings(boolean powered, double tunedHz, float bandwidthHz, float txPowerW, int modCode,
        float volume, float squelch, boolean monitor, boolean rogerBeep, boolean selfMonitor) {
        this.powered = powered;
        this.tunedHz = tunedHz;
        this.bandwidthHz = bandwidthHz;
        this.txPowerW = txPowerW;
        this.modCode = modCode;
        this.volume = volume;
        this.squelch = squelch;
        this.monitor = monitor;
        this.rogerBeep = rogerBeep;
        this.selfMonitor = selfMonitor;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(powered);
        buf.writeDouble(tunedHz);
        buf.writeFloat(bandwidthHz);
        buf.writeFloat(txPowerW);
        buf.writeInt(modCode);
        buf.writeFloat(volume);
        buf.writeFloat(squelch);
        buf.writeBoolean(monitor);
        buf.writeBoolean(rogerBeep);
        buf.writeBoolean(selfMonitor);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        powered = buf.readBoolean();
        tunedHz = buf.readDouble();
        bandwidthHz = buf.readFloat();
        txPowerW = buf.readFloat();
        modCode = buf.readInt();
        volume = buf.readFloat();
        squelch = buf.readFloat();
        monitor = buf.readBoolean();
        rogerBeep = buf.readBoolean();
        selfMonitor = buf.readBoolean();
    }

    public static final class Handler implements IMessageHandler<PacketHandheldSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketHandheldSettings msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ItemStack s = p.getHeldItem();
            if (s == null || !(s.getItem() instanceof ItemHandheldRadio)) return null;
            ItemHandheldRadio.setPowered(s, msg.powered);
            ItemHandheldRadio.setTunedHz(s, msg.tunedHz);
            ItemHandheldRadio.setBandwidthHz(s, msg.bandwidthHz);
            ItemHandheldRadio.setTxPowerW(s, msg.txPowerW);
            ItemHandheldRadio.setVolume(s, msg.volume);
            ItemHandheldRadio.setSquelch(s, msg.squelch);
            ItemHandheldRadio.setMonitor(s, msg.monitor);
            ItemHandheldRadio.setRogerBeep(s, msg.rogerBeep);
            ItemHandheldRadio.setSelfMonitor(s, msg.selfMonitor);
            try {
                ItemHandheldRadio.setModulation(s, Modulation.fromCode(msg.modCode));
            } catch (IllegalArgumentException ignore) {}
            return null;
        }
    }
}
