package io.hertzian.dynamics.net;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.player.HertzianPlayerRadio;
import io.netty.buffer.ByteBuf;

/**
 * Server to client sync of a player's radio gear. The client copy of the
 * {@link HertzianPlayerRadio} property is used by the push-to-talk handler
 * to decide whether a powered radio is carried and whether a headset is
 * present, so the copy must track the authoritative server state. The sync
 * fires on login, on respawn, on dimension change, and when the gear
 * screen closes after edits.
 */
public final class PacketRadioGearSync implements IMessage {

    private NBTTagCompound data;

    public PacketRadioGearSync() {}

    public PacketRadioGearSync(NBTTagCompound data) {
        this.data = data;
    }

    /** Build the packet from the player's current gear and send it. */
    public static void sendTo(EntityPlayerMP player) {
        HertzianPlayerRadio gear = HertzianPlayerRadio.get(player);
        if (gear == null) return;
        NetworkHandler.CHANNEL.sendTo(new PacketRadioGearSync(gear.writeSyncNBT()), player);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeTag(buf, data == null ? new NBTTagCompound() : data);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        data = ByteBufUtils.readTag(buf);
    }

    public static final class Handler implements IMessageHandler<PacketRadioGearSync, IMessage> {

        @Override
        public IMessage onMessage(PacketRadioGearSync msg, MessageContext ctx) {
            try {
                EntityPlayer player = Minecraft.getMinecraft().thePlayer;
                if (player == null) return null;
                HertzianPlayerRadio gear = HertzianPlayerRadio.get(player);
                if (gear != null && msg.data != null) {
                    gear.readSyncNBT(msg.data);
                }
            } catch (Throwable ignore) {}
            return null;
        }
    }
}