package io.hertzian.dynamics.tile;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.network.NetworkRegistry;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.RttyCodec;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketRttyData;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * RTTY terminal: an interactive teleprinter transceiver. Typed text is
 * encoded to audio FSK and fed to the nearest transmitter, while the tile
 * runs its own USB receiver and decodes incoming RTTY into a scrolling
 * text buffer. It is the data-mode analogue of the telegraph pair folded
 * into one block, because radioteletype is a two-way chat where the same
 * operator reads and writes.
 *
 * <p>
 * Both halves follow the established patterns: the transmit side pumps
 * a frame of tone audio into the nearest transmitter the way the
 * microphone and telegraph key do, switching it to USB only when its
 * modulation differs so the change does not force a retune every tick;
 * the receive side owns a slot in {@link WorldRfState} keyed by position,
 * mixes one chunk per tick, and decodes it. Receive work is skipped
 * unless a player is in range so an idle terminal is free.
 */
public final class TileRttyTerminal extends TileEntity {

    private double tunedHz = 14_080_000.0; // 20 m RTTY area.
    private float bandwidthHz = 500.0f;

    private final RttyCodec.Encoder encoder = new RttyCodec.Encoder();
    private transient RttyCodec.Decoder decoder;
    private int sendCounter = 0;

    private String clientText = "";
    private float clientSnrDb = -30f;
    private float clientLevel = 0f;
    private boolean clientSending = false;

    public double tunedHz() {
        return tunedHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public String clientText() {
        return clientText;
    }

    public float clientSnrDb() {
        return clientSnrDb;
    }

    public float clientLevel() {
        return clientLevel;
    }

    public boolean clientSending() {
        return clientSending;
    }

    public void setClientData(String text, float snr, float level, boolean sending) {
        this.clientText = text == null ? "" : text;
        this.clientSnrDb = snr;
        this.clientLevel = level;
        this.clientSending = sending;
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

    public void queueText(String text) {
        encoder.queueText(text);
    }

    public void clearTx() {
        encoder.clear();
    }

    public void clearRx() {
        if (decoder != null) decoder.clear();
        clientText = "";
    }

    private void retune() {
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState s = WorldRfState.forWorld(worldObj);
        if (s != null) s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
    }

    public void serverTick(WorldRfState state, List<TileRadioTransmitter> txs, int samples, long worldTick,
        float localHour) {
        if (worldObj == null || worldObj.isRemote) return;

        // Transmit.
        if (encoder.active()) {
            TileRadioTransmitter best = DataModeSupport.findNearest(txs, worldObj, xCoord, yCoord, zCoord);
            if (best != null) {
                if (best.modulation() != Modulation.USB) best.setModulation(Modulation.USB);
                float[] frame = new float[samples];
                encoder.fill(frame);
                best.enqueueAudio(frame);
            }
        }

        // Receive, gated to nearby observers.
        if (!DataModeSupport.hasObserver(worldObj, xCoord, yCoord, zCoord)) return;
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
            HertzianDynamics.LOGGER.error("rtty mix failed", t);
            return;
        }
        if (chunk == null) return;

        float snr = DataModeSupport.snrDb(chunk);
        float[] audio = DataModeSupport.audioFromChunk(chunk);
        if (decoder == null) decoder = new RttyCodec.Decoder();
        decoder.feed(audio, chunk.sampleCount(), snr);

        sendCounter++;
        if (sendCounter >= 3) {
            sendCounter = 0;
            NetworkRegistry.TargetPoint target = new NetworkRegistry.TargetPoint(
                worldObj.provider.dimensionId,
                xCoord + 0.5,
                yCoord + 0.5,
                zCoord + 0.5,
                64.0);
            NetworkHandler.CHANNEL.sendToAllAround(
                new PacketRttyData(
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    snr,
                    decoder.level(),
                    encoder.active(),
                    decoder.tail(512)),
                target);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("tunedHz", tunedHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("tunedHz")) tunedHz = tag.getDouble("tunedHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
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
