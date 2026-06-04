package io.hertzian.dynamics.net;

import java.nio.charset.StandardCharsets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.audio.ClientAudioBridge;
import io.hertzian.dynamics.audio.ReceiverVoice;
import io.hertzian.dynamics.core.Modulation;
import io.netty.buffer.ByteBuf;

/**
 * Server-to-client audio chunk.
 * <p>
 * Wire layout:
 *
 * <pre>
 *   int      voiceKey byte length (<= 64)
 *   bytes    voiceKey, UTF-8 encoded
 *   int      dimensionId
 *   int      x
 *   int      y
 *   int      z
 *   int      modulationCode
 *   int      sampleRateHz
 *   int      sampleCount
 *   short[N] pcm samples (16-bit signed mono)
 * </pre>
 *
 * <p>
 * The {@code voiceKey} field carries the stable client-side voice
 * identifier the server assigns. Using the {@code (dim, x, y, z)}
 * tuple as the identifier directly is exact for a stationary block
 * receiver but leaks AL sources for a handheld radio: the packet
 * coordinate triple is the player position, so each block of
 * movement produces a new key, registers a fresh
 * {@link ReceiverVoice}, and leaves the prior voice for the same
 * handheld unreachable and never disposed.
 *
 * <p>
 * Mainstream Windows AL drivers clamp at roughly 32 sources per
 * context. Crossing 32 unique positions saturates the pool, after
 * which {@code alGenSources} returns {@code AL_OUT_OF_MEMORY} and
 * every AL call against the zero-handle id it leaves behind reports
 * {@code AL_INVALID_NAME}. The stable server-assigned key avoids
 * this:
 * <ul>
 * <li>Block receivers use {@code "b:" + dim + ":" + x + ":" + y + ":" + z}.</li>
 * <li>Handheld receivers use {@code "h:" + uuid.toString()}.</li>
 * <li>Any future identity (spectator preview, mob-mounted radio)
 * picks its own prefix and stays decoupled from world
 * coordinates.</li>
 * </ul>
 * The {@code (x, y, z)} fields stay in the packet because the
 * client needs them for the 3D AL listener model: the source has
 * to know where to position itself in world space, separately from
 * the identity used to look it up in {@link ClientAudioBridge}.
 */
public final class PacketAudioChunk implements IMessage {

    /**
     * Upper bound on the encoded voice key length, in bytes. The
     * field exists so a malformed or hostile packet cannot force
     * the client to allocate a multi-megabyte byte buffer just to
     * read the string; 64 bytes is enough for any identity scheme
     * we plan to use (UUIDs encode to 36 ASCII chars, block coords
     * encode to under 30 chars in the worst case).
     */
    private static final int MAX_VOICE_KEY_BYTES = 64;

    private String voiceKey;
    private int dimensionId;
    private int x, y, z;
    private int modulationCode;
    private int sampleRateHz;
    private short[] pcm;

    /** Mandatory no-arg constructor for SimpleNetworkWrapper. */
    public PacketAudioChunk() {}

    public PacketAudioChunk(String voiceKey, int dimensionId, int x, int y, int z, Modulation modulation,
        int sampleRateHz, short[] pcm) {
        this.voiceKey = voiceKey;
        this.dimensionId = dimensionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.modulationCode = modulation.code();
        this.sampleRateHz = sampleRateHz;
        this.pcm = pcm;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // Voice key encoded UTF-8 with an explicit byte length so
        // the reader can validate against MAX_VOICE_KEY_BYTES
        // before allocating.
        byte[] keyBytes = (voiceKey == null ? "" : voiceKey).getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length > MAX_VOICE_KEY_BYTES) {
            throw new IllegalArgumentException("voiceKey exceeds " + MAX_VOICE_KEY_BYTES + " bytes");
        }
        buf.writeInt(keyBytes.length);
        buf.writeBytes(keyBytes);
        buf.writeInt(dimensionId);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(modulationCode);
        buf.writeInt(sampleRateHz);
        buf.writeInt(pcm.length);
        // Write the PCM payload one short at a time. ByteBuf's
        // writeShortLE would be marginally faster but not all
        // 1.7.10 Netty versions expose it; the per-short loop is
        // portable.
        for (int i = 0; i < pcm.length; i++) {
            buf.writeShort(pcm[i]);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int keyLen = buf.readInt();
        if (keyLen < 0 || keyLen > MAX_VOICE_KEY_BYTES) {
            throw new IllegalArgumentException("PacketAudioChunk: invalid voiceKey length " + keyLen);
        }
        byte[] keyBytes = new byte[keyLen];
        buf.readBytes(keyBytes);
        voiceKey = new String(keyBytes, StandardCharsets.UTF_8);

        dimensionId = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        modulationCode = buf.readInt();
        sampleRateHz = buf.readInt();
        int n = buf.readInt();
        // Sanity cap to refuse a malformed or hostile packet. Real
        // chunks top out around 4096 samples; 64 K leaves headroom
        // for future wideband variants without trusting an
        // attacker-supplied number.
        if (n < 0 || n > 65_536) {
            throw new IllegalArgumentException("PacketAudioChunk: invalid sample count " + n);
        }
        pcm = new short[n];
        for (int i = 0; i < n; i++) {
            pcm[i] = buf.readShort();
        }
    }

    /**
     * Handler executed on the client. Routes the packet content
     * into {@link ClientAudioBridge}; the actual AL work runs on
     * the next client tick.
     */
    public static final class Handler implements IMessageHandler<PacketAudioChunk, IMessage> {

        @Override
        public IMessage onMessage(PacketAudioChunk msg, MessageContext ctx) {
            try {
                Modulation modulation = Modulation.fromCode(msg.modulationCode);
                ClientAudioBridge.routePcm(
                    msg.voiceKey,
                    msg.dimensionId,
                    msg.x,
                    msg.y,
                    msg.z,
                    modulation,
                    msg.sampleRateHz,
                    msg.pcm);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.error("PacketAudioChunk handler failed", t);
            }
            return null;
        }
    }
}
