package io.hertzian.dynamics.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.tile.TileRadioTransmitter;
import io.hertzian.dynamics.world.StationLibrary;
import io.netty.buffer.ByteBuf;

/**
 * Client to server station transport control: play, pause, stop, seek,
 * track select, loop toggle, the track-list request.
 * <p>
 * Playlist actions:
 * <ul>
 * <li>{@code PLAYLIST_ADD}: append the track named in the string.</li>
 * <li>{@code PLAYLIST_REMOVE}: drop the entry at the index in param.</li>
 * <li>{@code PLAYLIST_CLEAR}: empty the playlist.</li>
 * <li>{@code SHUFFLE}: param 0 for linear, non-zero for random.</li>
 * <li>{@code PLAYLIST_PLAY}: start playback from the playlist.</li>
 * </ul>
 * Each mutates the tile, which syncs the new playlist NBT back to the
 * client so its side panel reflects the authoritative state.
 */
public final class PacketStationControl implements IMessage {

    public static final int PLAY = 0, PAUSE = 1, STOP = 2, SEEK = 3, TRACK = 4, LOOP = 5, LIST = 6;
    public static final int PLAYLIST_ADD = 7, PLAYLIST_REMOVE = 8, PLAYLIST_CLEAR = 9, SHUFFLE = 10, PLAYLIST_PLAY = 11;

    private int dim, x, y, z, action;
    private double param;
    private String track;

    public PacketStationControl() {}

    public PacketStationControl(int dim, int x, int y, int z, int action, double param, String track) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.param = param;
        this.track = track == null ? "" : track;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(action);
        buf.writeDouble(param);
        ByteBufUtils.writeUTF8String(buf, track);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readInt();
        param = buf.readDouble();
        track = ByteBufUtils.readUTF8String(buf);
    }

    public static final class Handler implements IMessageHandler<PacketStationControl, IMessage> {

        @Override
        public IMessage onMessage(PacketStationControl msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            World world = player.worldObj;
            if (msg.action == LIST) {
                NetworkHandler.CHANNEL.sendTo(new PacketStationList(StationLibrary.list()), player);
                return null;
            }
            if (world.provider.dimensionId != msg.dim) return null;
            TileEntity te = world.getTileEntity(msg.x, msg.y, msg.z);
            if (!(te instanceof TileRadioTransmitter)) return null;
            TileRadioTransmitter tx = (TileRadioTransmitter) te;
            switch (msg.action) {
                case PLAY:
                    tx.setPlaying(true);
                    break;
                case PAUSE:
                    tx.setPlaying(false);
                    break;
                case STOP:
                    tx.setPlaying(false);
                    tx.seekSeconds(0);
                    break;
                case SEEK:
                    tx.seekSeconds(msg.param);
                    break;
                case TRACK:
                    tx.selectTrack(msg.track);
                    break;
                case LOOP:
                    tx.setLoopTrack(msg.param != 0);
                    break;
                case PLAYLIST_ADD:
                    tx.addToPlaylist(msg.track);
                    break;
                case PLAYLIST_REMOVE:
                    tx.removeFromPlaylist((int) msg.param);
                    break;
                case PLAYLIST_CLEAR:
                    tx.clearPlaylist();
                    break;
                case SHUFFLE:
                    tx.setShuffle(msg.param != 0);
                    break;
                case PLAYLIST_PLAY:
                    tx.startPlaylist();
                    break;
                default:
                    break;
            }
            return null;
        }
    }
}
