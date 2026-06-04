package io.hertzian.dynamics.net;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileTelegraphKey;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: telegraph key control. One packet covers every key
 * action because they all mutate the same tile and the set is small.
 *
 * <p>
 * Actions
 * <ul>
 * <li>{@code SEND}: encode the string at the WPM in {@code param} and
 * queue it for automatic sending.</li>
 * <li>{@code CLEAR}: drop everything queued and stop at once.</li>
 * <li>{@code KEY_DOWN} / {@code KEY_UP}: straight key state, sent on
 * mouse press and release over the key pad. Held keying is
 * real-time, so the two events bracket the carrier the same way a
 * physical key would, give or take network latency.</li>
 * <li>{@code SET_WPM}: store the sending speed from {@code param}.</li>
 * </ul>
 *
 * <p>
 * Wire layout:
 * 
 * <pre>
 *   int   dimensionId
 *   int   x, y, z
 *   int   action
 *   int   param      WPM for SEND and SET_WPM, unused otherwise
 *   UTF8  text       message for SEND, empty otherwise
 * </pre>
 */
public final class PacketTelegraphKey implements IMessage {

    public static final int SEND = 0, CLEAR = 1, KEY_DOWN = 2, KEY_UP = 3, SET_WPM = 4;

    private int dim, x, y, z, action, param;
    private String text;

    public PacketTelegraphKey() {}

    public PacketTelegraphKey(int dim, int x, int y, int z, int action, int param, String text) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.param = param;
        this.text = text == null ? "" : text;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(action);
        buf.writeInt(param);
        ByteBufUtils.writeUTF8String(buf, text);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readInt();
        param = buf.readInt();
        text = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketTelegraphKey, IMessage> {

        @Override
        public IMessage onMessage(PacketTelegraphKey msg, MessageContext ctx) {
            World world = ctx.getServerHandler().playerEntity.worldObj;
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (!(te instanceof TileTelegraphKey)) return null;
            TileTelegraphKey key = (TileTelegraphKey) te;
            switch (msg.action) {
                case SEND:
                    if (msg.param > 0) key.setWpm(msg.param);
                    key.queueText(msg.text);
                    break;
                case CLEAR:
                    key.clearQueue();
                    break;
                case KEY_DOWN:
                    key.setStraightKey(true);
                    break;
                case KEY_UP:
                    key.setStraightKey(false);
                    break;
                case SET_WPM:
                    key.setWpm(msg.param);
                    break;
                default:
                    break;
            }
            return null;
        }
    }
}
