package io.hertzian.dynamics.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.core.SstvCodec;
import io.hertzian.dynamics.gui.GuiSstvStation;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: one decoded SSTV image line. Lines are sent
 * individually rather than as a whole picture so each packet stays small
 * and the monitor paints top to bottom as the frame arrives, which also
 * keeps the per-tick network load bounded.
 *
 * <p>
 * The {@code frameSeq} field is the decoder's running frame number. It
 * lets the client hold several received pictures at once and route each
 * line to the correct one, which is what makes the multi-frame receive
 * gallery possible: a back-to-back transmission of several frames produces
 * a rising sequence number, and the client files lines under it.
 */
public final class PacketSstvLine implements IMessage {

    private int dim;
    private int x;
    private int y;
    private int z;
    private int frameSeq;
    private int line;
    private byte[] rgb;

    public PacketSstvLine() {}

    public PacketSstvLine(int dim, int x, int y, int z, int frameSeq, int line, byte[] rgb) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.frameSeq = frameSeq;
        this.line = line;
        this.rgb = rgb;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(frameSeq);
        buf.writeInt(line);
        buf.writeInt(rgb.length);
        buf.writeBytes(rgb);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        frameSeq = buf.readInt();
        line = buf.readInt();
        int n = buf.readInt();
        if (n < 0 || n > SstvCodec.W * 3 * 4) {
            n = 0;
        }
        rgb = new byte[n];
        buf.readBytes(rgb);
    }

    public static final class Handler implements IMessageHandler<PacketSstvLine, IMessage> {

        @Override
        public IMessage onMessage(PacketSstvLine m, MessageContext ctx) {
            GuiSstvStation.acceptLine(m.dim, m.x, m.y, m.z, m.frameSeq, m.line, m.rgb);
            return null;
        }
    }
}
