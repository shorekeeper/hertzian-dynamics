package io.hertzian.dynamics.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.network.NetworkRegistry;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.ChunkDemodulator;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.MorseDecoder;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketAudioChunk;
import io.hertzian.dynamics.net.PacketTeletypeData;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Telegraph receiver: a CW-only radio that, instead of being something
 * you only listen to, runs a {@link MorseDecoder} on the server and turns
 * the Morse keying into readable text.
 *
 * <p>
 * The receive half mirrors {@link TileRadioReceiver}: the slot lives in
 * {@link WorldRfState} keyed by block position, the tile mixes one chunk
 * per tick, and a tuning change forces a retune. The modulation is pinned
 * to CW because the decoder works on the keying envelope; only frequency
 * and the CW filter bandwidth are user-tunable.
 *
 * <p>
 * Two outputs ride on every chunk. The chunk is demodulated to a CW
 * sidetone and broadcast as audio so the operator hears the dots and
 * dashes (the same {@link PacketAudioChunk} path the receiver uses), and
 * the chunk's raw I/Q plus its signal to noise ratio are fed to the
 * decoder. A few times a second the tile ships the decoded text tail, the
 * estimated WPM, the S/N and the current keying level to nearby clients
 * through {@link PacketTeletypeData} for the GUI.
 */
public final class TileTeletype extends TileEntity {

    private double tunedHz = 7_030_000.0; // 40 m CW calling area.
    private float bandwidthHz = 500.0f; // Narrow CW filter.
    private float antennaGain = 1.0f;
    private final Modulation modulation = Modulation.CW;

    private transient MorseDecoder decoder;
    private int sendCounter = 0;

    // Client-side mirrors, set by PacketTeletypeData.
    private String clientText = "";
    private float clientWpm = 0f;
    private float clientSnrDb = -30f;
    private float clientLevel = 0f;

    public double tunedHz() {
        return tunedHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public Modulation modulation() {
        return modulation;
    }

    public String clientText() {
        return clientText;
    }

    public float clientWpm() {
        return clientWpm;
    }

    public float clientSnrDb() {
        return clientSnrDb;
    }

    public float clientLevel() {
        return clientLevel;
    }

    public void setClientData(String text, float wpm, float snrDb, float level) {
        this.clientText = text == null ? "" : text;
        this.clientWpm = wpm;
        this.clientSnrDb = snrDb;
        this.clientLevel = level;
    }

    public void setTunedHz(double hz) {
        this.tunedHz = hz;
        markDirty();
        requestRetune();
    }

    public void setBandwidthHz(float hz) {
        this.bandwidthHz = hz;
        markDirty();
        requestRetune();
    }

    /** Wipe the decoded text on the server; the client clears its mirror. */
    public void clearText() {
        if (decoder != null) decoder.clear();
        clientText = "";
        markDirty();
    }

    private void requestRetune() {
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(worldObj);
        if (state != null) state.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        io.hertzian.dynamics.audio.ClientAudioBridge.unregisterByKey(
            io.hertzian.dynamics.audio.ClientAudioBridge
                .blockKey(worldObj.provider.dimensionId, xCoord, yCoord, zCoord));
    }

    public void tick(WorldRfState state, int sampleCount, long worldTick, float localHour) {
        if (worldObj == null || worldObj.isRemote) return;

        SpectrumManager.ReceiverParameters params = new SpectrumManager.ReceiverParameters(
            tunedHz,
            bandwidthHz,
            modulation,
            antennaGain,
            xCoord + 0.5f,
            yCoord + 0.5f,
            zCoord + 0.5f,
            0f,
            0f,
            0f);
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);

        SpectrumChunk chunk;
        int id;
        try {
            id = state.getOrRegisterReceiver(key, params);
            chunk = state.manager()
                .mix(state.grid(), state.materials(), state.ionosphere(), id, sampleCount, worldTick, localHour);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("teletype mix failed", t);
            return;
        }
        if (chunk == null) return;

        float snrDb = computeSnrDb(chunk);

        // Decode.
        if (decoder == null) decoder = new MorseDecoder(chunk.sampleRateHz());
        decoder.feedIq(chunk.samples(), chunk.sampleCount(), snrDb);

        // Audible CW sidetone.
        try {
            ChunkDemodulator demod = state.getOrCreateDemodulator(key, modulation, chunk.sampleRateHz());
            short[] pcm = new short[chunk.sampleCount()];
            demod.demodulateToPcm16(chunk, pcm);
            String voiceKey = io.hertzian.dynamics.audio.ClientAudioBridge
                .blockKey(worldObj.provider.dimensionId, xCoord, yCoord, zCoord);
            NetworkRegistry.TargetPoint audioTarget = new NetworkRegistry.TargetPoint(
                worldObj.provider.dimensionId,
                xCoord + 0.5,
                yCoord + 0.5,
                zCoord + 0.5,
                96.0);
            NetworkHandler.CHANNEL.sendToAllAround(
                new PacketAudioChunk(
                    voiceKey,
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    modulation,
                    (int) chunk.sampleRateHz(),
                    pcm),
                audioTarget);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("teletype audio failed", t);
        }

        // Push decoded state to nearby clients a few times a second.
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
                new PacketTeletypeData(
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    snrDb,
                    decoder.wpm(),
                    decoder.level(),
                    decoder.tail(512)),
                target);
        }
    }

    private static float computeSnrDb(SpectrumChunk chunk) {
        float sig = chunk.signalPowerWatts();
        float noise = chunk.noiseFloorWatts();
        if (noise <= 0f) noise = 1.0e-21f;
        if (sig <= 0f) return -30f;
        double snr = 10.0 * Math.log10(sig / (double) noise);
        if (snr < -30.0) snr = -30.0;
        if (snr > 80.0) snr = 80.0;
        return (float) snr;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("tunedHz", tunedHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
        tag.setFloat("antennaGain", antennaGain);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("tunedHz")) tunedHz = tag.getDouble("tunedHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
        if (tag.hasKey("antennaGain")) antennaGain = tag.getFloat("antennaGain");
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
