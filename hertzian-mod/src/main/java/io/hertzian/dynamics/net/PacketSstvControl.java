package io.hertzian.dynamics.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileSstvStation;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: SSTV station control. Every action this carries is
 * tiny by design, which is the whole point of the no-upload model: queuing
 * a frame sends an enum ordinal or a "use my map" intent, never pixels, so
 * building a five-frame batch costs a handful of bytes.
 *
 * <p>
 * Actions
 * <ul>
 * <li>{@code TUNE}: apply the tuning fields.</li>
 * <li>{@code ADD_PATTERN}: queue a procedural frame; {@code param} is the
 * {@link io.hertzian.dynamics.core.SstvCodec.PatternKind} ordinal.</li>
 * <li>{@code ADD_MAP}: queue a snapshot of the player's held map, read
 * server-side from existing map data.</li>
 * <li>{@code REMOVE}: drop the queued frame at index {@code param}.</li>
 * <li>{@code CLEAR_QUEUE}: empty the send queue.</li>
 * <li>{@code START}: transmit the queue frame by frame.</li>
 * <li>{@code STOP}: stop transmitting.</li>
 * <li>{@code CLEAR_RX}: drop the receive lock.</li>
 * </ul>
 */
public final class PacketSstvControl implements IMessage {

    public static final int TUNE = 0;
    public static final int ADD_PATTERN = 1;
    public static final int ADD_MAP = 2;
    public static final int REMOVE = 3;
    public static final int CLEAR_QUEUE = 4;
    public static final int START = 5;
    public static final int STOP = 6;
    public static final int CLEAR_RX = 7;
    public static final int ADD_TERRAIN = 8;

    private int dim;
    private int x;
    private int y;
    private int z;
    private int action;
    private int param;
    private double tunedHz;
    private float bandwidthHz;

    public PacketSstvControl() {}

    public PacketSstvControl(int dim, int x, int y, int z, int action, int param, double tunedHz, float bandwidthHz) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.param = param;
        this.tunedHz = tunedHz;
        this.bandwidthHz = bandwidthHz;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(action);
        buf.writeInt(param);
        buf.writeDouble(tunedHz);
        buf.writeFloat(bandwidthHz);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readInt();
        param = buf.readInt();
        tunedHz = buf.readDouble();
        bandwidthHz = buf.readFloat();
    }

    public static final class Handler implements IMessageHandler<PacketSstvControl, IMessage> {

        @Override
        public IMessage onMessage(PacketSstvControl m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            World world = player.worldObj;
            if (world.provider.dimensionId != m.dim) {
                return null;
            }
            TileEntity te = world.getTileEntity(m.x, m.y, m.z);
            if (!(te instanceof TileSstvStation)) {
                return null;
            }
            TileSstvStation t = (TileSstvStation) te;
            switch (m.action) {
                case TUNE:
                    t.setTunedHz(m.tunedHz);
                    t.setBandwidthHz(m.bandwidthHz);
                    world.markBlockForUpdate(m.x, m.y, m.z);
                    break;
                case ADD_PATTERN:
                    t.addPatternFrame(m.param);
                    break;
                case ADD_MAP:
                    if (!t.addMapFrame(player)) {
                        player.addChatMessage(
                            new net.minecraft.util.ChatComponentText(
                                "[SSTV] Hold a filled map (not empty) to add it, or the queue is full."));
                    }
                    break;
                case ADD_TERRAIN:
                    if (!t.addTerrainFrame()) {
                        player.addChatMessage(
                            new net.minecraft.util.ChatComponentText(
                                "[SSTV] Queue is full (max " + io.hertzian.dynamics.tile.TileSstvStation.MAX_QUEUE
                                    + ")."));
                    }
                    break;
                case REMOVE:
                    t.removeFrame(m.param);
                    break;
                case CLEAR_QUEUE:
                    t.clearQueue();
                    break;
                case START:
                    t.startQueue();
                    break;
                case STOP:
                    t.stop();
                    break;
                case CLEAR_RX:
                    t.clearRx();
                    break;
                default:
                    break;
            }
            return null;
        }
    }
}
