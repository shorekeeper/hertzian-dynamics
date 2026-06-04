package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileJammer;
import io.netty.buffer.ByteBuf;

public final class PacketJammerSettings implements IMessage {

    private int dim, x, y, z, profile;
    private double carrierHz;
    private float bandwidthHz;
    private boolean active;

    public PacketJammerSettings() {}

    public PacketJammerSettings(int dim, int x, int y, int z, boolean active, double carrierHz, float bandwidthHz,
        int profile) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.active = active;
        this.carrierHz = carrierHz;
        this.bandwidthHz = bandwidthHz;
        this.profile = profile;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(active);
        buf.writeDouble(carrierHz);
        buf.writeFloat(bandwidthHz);
        buf.writeInt(profile);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        active = buf.readBoolean();
        carrierHz = buf.readDouble();
        bandwidthHz = buf.readFloat();
        profile = buf.readInt();
    }

    public static final class Handler implements IMessageHandler<PacketJammerSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketJammerSettings msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileJammer) {
                TileJammer jm = (TileJammer) te;
                jm.setActive(msg.active);
                jm.setCarrierHz(msg.carrierHz);
                jm.setBandwidthHz(msg.bandwidthHz);
                jm.setJamProfile(msg.profile);
            }
            world.markBlockForUpdate(msg.x, msg.y, msg.z);
            return null;
        }
    }
}
