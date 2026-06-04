package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.tile.TileRelay;
import io.netty.buffer.ByteBuf;

public final class PacketRelaySettings implements IMessage {

    private int dim, x, y, z, modCode;
    private double inputHz, outputHz;
    private float power;
    private boolean active;

    public PacketRelaySettings() {}

    public PacketRelaySettings(int dim, int x, int y, int z, double inputHz, double outputHz, int modCode, float power,
        boolean active) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.inputHz = inputHz;
        this.outputHz = outputHz;
        this.modCode = modCode;
        this.power = power;
        this.active = active;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(inputHz);
        buf.writeDouble(outputHz);
        buf.writeInt(modCode);
        buf.writeFloat(power);
        buf.writeBoolean(active);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        inputHz = buf.readDouble();
        outputHz = buf.readDouble();
        modCode = buf.readInt();
        power = buf.readFloat();
        active = buf.readBoolean();
    }

    public static final class Handler implements IMessageHandler<PacketRelaySettings, IMessage> {

        @Override
        public IMessage onMessage(PacketRelaySettings msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (te instanceof TileRelay) {
                TileRelay r = (TileRelay) te;
                r.setInputHz(msg.inputHz);
                r.setOutputHz(msg.outputHz);
                r.setTxPowerW(msg.power);
                r.setActive(msg.active);
                try {
                    r.setModulation(Modulation.fromCode(msg.modCode));
                } catch (IllegalArgumentException ignore) {}
                world.markBlockForUpdate(msg.x, msg.y, msg.z);
            }
            return null;
        }
    }
}
