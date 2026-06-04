package io.hertzian.dynamics.net;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.gui.GuiIds;

/**
 * Client to server request to open the radio gear screen. The gear screen
 * is inventory backed, so it must be opened through the server: the server
 * builds the container, registers it, and sends the open packet back to the
 * client. Opening the screen directly on the client leaves the server with
 * no container, which makes every slot click reject and rubber-band the
 * item back into the inventory. The keybind sends this packet instead of
 * calling openGui locally.
 *
 * Carries no payload. The target player is taken from the server handler.
 */
public final class PacketOpenRadioGear implements IMessage {

    public PacketOpenRadioGear() {}

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {}

    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {}

    public static final class Handler implements IMessageHandler<PacketOpenRadioGear, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenRadioGear msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            p.openGui(HertzianDynamics.INSTANCE, GuiIds.RADIO_GEAR, p.worldObj, 0, 0, 0);
            return null;
        }
    }
}