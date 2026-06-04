package io.hertzian.dynamics.tile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.network.NetworkRegistry;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.core.SstvCodec;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketSstvLine;
import io.hertzian.dynamics.net.PacketSstvStatus;
import io.hertzian.dynamics.world.MapImageReader;
import io.hertzian.dynamics.world.TerrainImager;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * SSTV station: a slow-scan television transceiver with a multi-frame send
 * queue. The operator builds a queue of up to five picture frames and
 * transmits them as one batch; the station also receives, rebuilding
 * incoming pictures line by line and presenting up to five of them to the
 * monitor GUI.
 *
 * <p>
 * Server-safety design (the central concern of this rework)
 * The transmit path is built so a five-frame batch cannot overload the
 * server:
 * <ul>
 * <li><b>No image upload.</b> Frame sources are all server-resolvable:
 * a {@link SstvCodec.PatternKind} ordinal for a procedural picture,
 * or a snapshot of a player's held filled map read by
 * {@link MapImageReader} from data the server already holds. A queued
 * frame therefore costs one enum byte or a server-side map read, not
 * a pixel transfer across the network.</li>
 * <li><b>Sequential transmission.</b> The queue is played one frame at a
 * time. Each frame is roughly seven seconds of audio produced one
 * 50 ms chunk per tick, with a silent gap between frames, so the
 * per-tick work is one chunk of oscillator math regardless of how
 * many frames are queued. The whole batch never runs at once.</li>
 * <li><b>Transient queue.</b> The queue lives in server memory only, not
 * in NBT, so a batch of full-resolution frames never bloats the
 * saved chunk. A reload clears the send buffer, which is the right
 * behaviour for a "now sending" list.</li>
 * <li><b>Bounded receive.</b> Line packets are throttled per tick and
 * the receive work is gated to nearby observers, so an idle monitor
 * costs nothing and a busy one cannot flood the client.</li>
 * </ul>
 *
 * <p>
 * Frame numbering
 * Each decoded frame carries the decoder's {@link SstvCodec.Decoder#frameSeq()},
 * which the line packets forward so the client can hold several received
 * pictures at once and page through them.
 */
public final class TileSstvStation extends TileEntity {

    /** Hard cap on queued frames, the "package" size the rework targets. */
    public static final int MAX_QUEUE = 5;

    /** Kind byte for a frame sourced from a held map. */
    public static final int KIND_MAP = 4;

    /** Kind byte for a frame sourced from a top-down terrain snapshot. */
    public static final int KIND_TERRAIN = 5;

    /** Silent ticks inserted between transmitted frames so receivers re-sync. */
    private static final int GAP_TICKS = 30;

    private double tunedHz = 14_230_000.0; // 20 m SSTV calling frequency.
    private float bandwidthHz = 3_000.0f;

    private final SstvCodec.Encoder encoder = new SstvCodec.Encoder();
    private transient SstvCodec.Decoder decoder;
    private final byte[] lineScratch = new byte[SstvCodec.W * 3];
    private int statusCounter = 0;

    // Transmit queue (server-side, transient). Pixels are resolved at add
    // time; kinds are kept in parallel only for the client display label.
    private final List<int[][]> queueFrames = new ArrayList<>();
    private final List<Byte> queueKinds = new ArrayList<>();

    // Sequential send state machine.
    private boolean playing = false;
    private int playIndex = -1;
    private int gapCounter = 0;

    // Client-side mirrors, set by PacketSstvStatus.
    private float clientSnrDb = -30f;
    private float clientTxProgress = 0f;
    private boolean clientReceiving = false;
    private int clientQueueSize = 0;
    private byte[] clientQueueKinds = new byte[0];
    private int clientTxFrameIndex = -1;
    private int clientRxFrameSeq = 0;

    public double tunedHz() {
        return tunedHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public boolean sending() {
        return playing;
    }

    public float clientSnrDb() {
        return clientSnrDb;
    }

    public float clientTxProgress() {
        return clientTxProgress;
    }

    public boolean clientReceiving() {
        return clientReceiving;
    }

    public int clientQueueSize() {
        return clientQueueSize;
    }

    public byte[] clientQueueKinds() {
        return clientQueueKinds;
    }

    public int clientTxFrameIndex() {
        return clientTxFrameIndex;
    }

    public int clientRxFrameSeq() {
        return clientRxFrameSeq;
    }

    /** Apply the status block from the server to the client mirror. */
    public void setClientStatus(float snr, float txProgress, boolean receiving, byte[] kinds, int txFrameIndex,
        int rxFrameSeq) {
        this.clientSnrDb = snr;
        this.clientTxProgress = txProgress;
        this.clientReceiving = receiving;
        this.clientQueueKinds = kinds == null ? new byte[0] : kinds;
        this.clientQueueSize = this.clientQueueKinds.length;
        this.clientTxFrameIndex = txFrameIndex;
        this.clientRxFrameSeq = rxFrameSeq;
    }

    public void setTunedHz(double hz) {
        this.tunedHz = hz;
        markDirty();
        retune();
    }

    public void setBandwidthHz(float hz) {
        this.bandwidthHz = hz;
        markDirty();
        retune();
    }

    /**
     * Queue a procedural pattern frame. The pattern is resolved to pixels
     * immediately; the call costs no network and bounded server work. No
     * effect once the queue is full.
     */
    public void addPatternFrame(int patternOrdinal) {
        if (queueFrames.size() >= MAX_QUEUE) {
            return;
        }
        SstvCodec.PatternKind[] kinds = SstvCodec.PatternKind.values();
        if (patternOrdinal < 0 || patternOrdinal >= kinds.length) {
            patternOrdinal = 0;
        }
        queueFrames.add(kinds[patternOrdinal].generate());
        queueKinds.add((byte) patternOrdinal);
    }

    /**
     * Queue a snapshot of the player's held filled map. Pixels are read
     * here, server-side, from existing map data, so nothing is uploaded.
     * Returns false (and adds no frame) if the queue is full or the player
     * is not holding a readable filled map, so the caller can tell the
     * player why nothing happened.
     */
    public boolean addMapFrame(EntityPlayer player) {
        if (queueFrames.size() >= MAX_QUEUE) {
            return false;
        }
        int[][] pixels = MapImageReader.fromHeldMap(player);
        if (pixels == null) {
            return false;
        }
        queueFrames.add(pixels);
        queueKinds.add((byte) KIND_MAP);
        return true;
    }

    /**
     * Queue a top-down terrain snapshot of the area around this station.
     * The image is rendered server-side from block data, so it costs no
     * network and bounded one-time work. Returns false only when the queue
     * is full; the render itself always produces an image.
     */
    public boolean addTerrainFrame() {
        if (queueFrames.size() >= MAX_QUEUE) {
            return false;
        }
        int[][] pixels = TerrainImager.fromArea(worldObj, xCoord, zCoord);
        queueFrames.add(pixels);
        queueKinds.add((byte) KIND_TERRAIN);
        return true;
    }

    /** Remove one queued frame. Stops playback if the playing frame is dropped. */
    public void removeFrame(int index) {
        if (index < 0 || index >= queueFrames.size()) {
            return;
        }
        queueFrames.remove(index);
        queueKinds.remove(index);
        if (playing) {
            stop();
        }
    }

    public void clearQueue() {
        queueFrames.clear();
        queueKinds.clear();
        stop();
    }

    /** Begin transmitting the whole queue, frame by frame, from the start. */
    public void startQueue() {
        if (queueFrames.isEmpty()) {
            return;
        }
        playing = true;
        playIndex = 0;
        gapCounter = 0;
        encoder.start(queueFrames.get(0));
    }

    /** Stop transmitting at once and idle the encoder. */
    public void stop() {
        playing = false;
        playIndex = -1;
        gapCounter = 0;
        encoder.clear();
    }

    public void clearRx() {
        if (decoder != null) {
            decoder.clearLock();
        }
    }

    private void retune() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        WorldRfState s = WorldRfState.forWorld(worldObj);
        if (s != null) {
            s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        }
    }

    /**
     * Advance the send state machine by one tick of audio. While a frame
     * is producing audio it is fed to the transmitter; between frames a
     * silent gap runs, then the next frame is started, until the queue is
     * exhausted.
     */
    private void driveTransmit(List<TileRadioTransmitter> txs, int samples) {
        if (encoder.active()) {
            TileRadioTransmitter best = DataModeSupport.findNearest(txs, worldObj, xCoord, yCoord, zCoord);
            if (best != null) {
                if (best.modulation() != Modulation.USB) {
                    best.setModulation(Modulation.USB);
                }
                float[] frame = new float[samples];
                encoder.fill(frame);
                best.enqueueAudio(frame);
            }
            return;
        }
        if (!playing) {
            return;
        }
        // Current frame finished. Hold a silent gap, then start the next
        // queued frame, or stop when the queue is done.
        if (gapCounter > 0) {
            gapCounter--;
            return;
        }
        playIndex++;
        if (playIndex >= queueFrames.size()) {
            stop();
            return;
        }
        encoder.start(queueFrames.get(playIndex));
        gapCounter = GAP_TICKS;
    }

    public void serverTick(WorldRfState state, List<TileRadioTransmitter> txs, int samples, long worldTick,
        float localHour) {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }

        driveTransmit(txs, samples);

        // Receive is gated to nearby observers: an unwatched monitor adds
        // no server-tick cost.
        if (!DataModeSupport.hasObserver(worldObj, xCoord, yCoord, zCoord)) {
            return;
        }

        SpectrumManager.ReceiverParameters params = new SpectrumManager.ReceiverParameters(
            tunedHz,
            bandwidthHz,
            Modulation.USB,
            1.0f,
            xCoord + 0.5f,
            yCoord + 0.5f,
            zCoord + 0.5f,
            0f,
            0f,
            0f);
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);
        SpectrumChunk chunk;
        try {
            int id = state.getOrRegisterReceiver(key, params);
            chunk = state.manager()
                .mix(state.grid(), state.materials(), state.ionosphere(), id, samples, worldTick, localHour);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("sstv mix failed", t);
            return;
        }
        if (chunk == null) {
            return;
        }

        float snr = DataModeSupport.snrDb(chunk);
        float[] audio = DataModeSupport.audioFromChunk(chunk);
        if (decoder == null) {
            decoder = new SstvCodec.Decoder();
        }
        decoder.feed(audio, chunk.sampleCount(), snr);

        // Ship completed lines, capped per tick so a burst cannot flood the
        // client. Each carries the decoder frame number so the client can
        // route it to the correct received picture.
        NetworkRegistry.TargetPoint target = new NetworkRegistry.TargetPoint(
            worldObj.provider.dimensionId,
            xCoord + 0.5,
            yCoord + 0.5,
            zCoord + 0.5,
            64.0);
        int guard = 0;
        int[] li;
        while ((li = decoder.pollLine(lineScratch)) != null && guard++ < 8) {
            byte[] copy = new byte[lineScratch.length];
            System.arraycopy(lineScratch, 0, copy, 0, copy.length);
            NetworkHandler.CHANNEL.sendToAllAround(
                new PacketSstvLine(
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    decoder.frameSeq(),
                    li[0],
                    copy),
                target);
        }

        statusCounter++;
        if (statusCounter >= 4) {
            statusCounter = 0;
            byte[] kinds = new byte[queueKinds.size()];
            for (int i = 0; i < kinds.length; i++) {
                kinds[i] = queueKinds.get(i);
            }
            NetworkHandler.CHANNEL.sendToAllAround(
                new PacketSstvStatus(
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    snr,
                    encoder.progress(),
                    decoder.locked(),
                    kinds,
                    playing ? playIndex : -1,
                    decoder.frameSeq()),
                target);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("tunedHz", tunedHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
        // The send queue is deliberately not persisted: it is a transient
        // send buffer, and persisting full-resolution frames would bloat
        // the chunk save.
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("tunedHz")) {
            tunedHz = tag.getDouble("tunedHz");
        }
        if (tag.hasKey("bandwidthHz")) {
            bandwidthHz = tag.getFloat("bandwidthHz");
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());
    }
}
