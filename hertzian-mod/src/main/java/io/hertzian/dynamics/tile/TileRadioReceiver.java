package io.hertzian.dynamics.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Tile entity for the radio receiver block.
 *
 * <p>
 * The slot lifetime is owned by {@link WorldRfState}, keyed by
 * block position. The TE only carries user-tunable parameters and
 * passes them to the world state on every pullChunk. Any setter
 * that changes a parameter rf-core needs to see triggers an
 * explicit retune so the world state drops the old slot and the
 * next pullChunk re-registers with the new value.
 *
 * <p>
 * Output volume
 * The receiver carries a {@link #volume} scalar set from the GUI
 * slider, applied to the demodulated PCM here on the server before the
 * audio chunk is broadcast, so a block speaker plays at one level for
 * every listener within range rather than per-client.
 *
 * <p>
 * Preset memory bank
 * The receiver stores six car-radio style presets, each a frequency
 * plus its bandwidth and modulation. They live in NBT and ride the tile
 * description packet so the client GUI can show and recall them. A slot
 * with {@code presetHz <= 0} is empty. Recall is a plain tuning change
 * the client applies optimistically and ships through
 * {@link io.hertzian.dynamics.net.PacketReceiverSettings}; storing a
 * slot is the only action that needs its own packet,
 * {@link io.hertzian.dynamics.net.PacketReceiverStorePreset}, because it
 * writes persistent server state.
 */
public final class TileRadioReceiver extends TileEntity {

    private static final int PRESET_COUNT = 6;

    private double tunedHz = 145_000_000.0;
    private float bandwidthHz = 15_000.0f;
    private float antennaGain = 1.0f;
    private Modulation modulation = Modulation.NARROW_FM;

    private SpectrumChunk lastChunk = null;

    public double tunedHz() {
        return tunedHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public Modulation modulation() {
        return modulation;
    }

    public SpectrumChunk lastChunk() {
        return lastChunk;
    }

    /** Client-side mirror of the latest S/N ratio, dB. Set by packet. */
    private float clientSnrDb = -30f;

    public float clientSnrDb() {
        return clientSnrDb;
    }

    public void setClientSnrDb(float v) {
        this.clientSnrDb = v;
    }

    private int scopeCounter = 0;

    /** Wideband noise reduction strength, 0 off to 1 max. Receiver setting. */
    private float noiseReduction = 0f;
    private float nrGainSmoothed = 1f;

    public float noiseReduction() {
        return noiseReduction;
    }

    public void setNoiseReduction(float v) {
        this.noiseReduction = Math.max(0f, Math.min(1f, v));
        markDirty();
    }

    /** Output volume, 0 mute to 1 full. Applied to PCM server-side. */
    private float volume = 1.0f;

    public float volume() {
        return volume;
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        markDirty();
    }

    /** Preset bank: frequency, bandwidth and modulation per slot. */
    private final double[] presetHz = new double[PRESET_COUNT];
    private final float[] presetBw = new float[PRESET_COUNT];
    private final int[] presetMod = new int[PRESET_COUNT];

    public int presetCount() {
        return PRESET_COUNT;
    }

    public boolean hasPreset(int i) {
        return i >= 0 && i < PRESET_COUNT && presetHz[i] > 0.0;
    }

    public double presetHz(int i) {
        return (i >= 0 && i < PRESET_COUNT) ? presetHz[i] : 0.0;
    }

    public float presetBw(int i) {
        return (i >= 0 && i < PRESET_COUNT) ? presetBw[i] : 15_000f;
    }

    public int presetMod(int i) {
        return (i >= 0 && i < PRESET_COUNT) ? presetMod[i] : Modulation.NARROW_FM.code();
    }

    public void setPreset(int i, double hz, float bw, int mod) {
        if (i < 0 || i >= PRESET_COUNT) return;
        presetHz[i] = hz;
        presetBw[i] = bw;
        presetMod[i] = mod;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** Client-side decoded station name, set by PacketReceiverScope. */
    private String clientStationName = "";

    public String clientStationName() {
        return clientStationName;
    }

    public void setClientStationName(String s) {
        this.clientStationName = s == null ? "" : s;
    }

    /** Last antenna section count seen; a change forces a slot retune. */
    private transient int lastAntennaSections = -1;

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

    public void setModulation(Modulation m) {
        this.modulation = m;
        markDirty();
        requestRetune();
    }

    /**
     * Drop the existing slot and the client-side AL voice so the
     * next tick rebuilds them with the current parameters. Safe to
     * call when {@link #worldObj} is null or remote (no-op).
     */
    private void requestRetune() {
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(worldObj);
        if (state != null) {
            state.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        }
        io.hertzian.dynamics.audio.ClientAudioBridge.unregisterByKey(
            io.hertzian.dynamics.audio.ClientAudioBridge
                .blockKey(worldObj.provider.dimensionId, xCoord, yCoord, zCoord));
    }

    public void pullChunk(WorldRfState state, int sampleCount, long worldTick, float localHour) {
        if (worldObj == null || worldObj.isRemote) return;

        // Antenna mast: raise the effective height (horizon) and gain.
        // A change in the mast forces a retune so the new height and gain
        // reach rf-core, since the slot caches the parameters from its
        // first registration and would otherwise ignore later mast edits.
        int sections = io.hertzian.dynamics.world.AntennaSupport.countAbove(worldObj, xCoord, yCoord, zCoord);
        if (sections != lastAntennaSections) {
            lastAntennaSections = sections;
            state.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        }
        float effGain = antennaGain * io.hertzian.dynamics.world.AntennaSupport.gainFromSections(sections);
        float effY = yCoord + 0.5f + sections;

        SpectrumManager.ReceiverParameters params = new SpectrumManager.ReceiverParameters(
            tunedHz,
            bandwidthHz,
            modulation,
            effGain,
            xCoord + 0.5f,
            effY,
            zCoord + 0.5f,
            0f,
            0f,
            0f);
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);
        int id;
        try {
            id = state.getOrRegisterReceiver(key, params);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("receiver slot resolution failed", t);
            return;
        }
        try {
            lastChunk = state.manager()
                .mix(state.grid(), state.materials(), state.ionosphere(), id, sampleCount, worldTick, localHour);
            if (lastChunk == null) return;

            // Server-side demodulate to int16 PCM.
            io.hertzian.dynamics.core.ChunkDemodulator demod = state
                .getOrCreateDemodulator(key, modulation, lastChunk.sampleRateHz());
            short[] pcm = new short[lastChunk.sampleCount()];
            demod.demodulateToPcm16(lastChunk, pcm);

            // Noise reduction module: an SNR-driven downward gain.
            if (noiseReduction > 0f) {
                float snrDb = computeSnrDb(lastChunk);
                float t = (snrDb - 0f) / 20f;
                if (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
                float targetGain = (1f - noiseReduction) + noiseReduction * t;
                nrGainSmoothed += (targetGain - nrGainSmoothed) * 0.25f;
                for (int i = 0; i < pcm.length; i++) {
                    pcm[i] = (short) (pcm[i] * nrGainSmoothed);
                }
            }

            // Output volume: the final loudness control, after NR.
            if (volume < 0.999f) {
                for (int i = 0; i < pcm.length; i++) {
                    pcm[i] = (short) (pcm[i] * volume);
                }
            }

            cpw.mods.fml.common.network.NetworkRegistry.TargetPoint target = new cpw.mods.fml.common.network.NetworkRegistry.TargetPoint(
                worldObj.provider.dimensionId,
                xCoord + 0.5,
                yCoord + 0.5,
                zCoord + 0.5,
                96.0);
            String voiceKey = io.hertzian.dynamics.audio.ClientAudioBridge
                .blockKey(worldObj.provider.dimensionId, xCoord, yCoord, zCoord);
            io.hertzian.dynamics.net.NetworkHandler.CHANNEL.sendToAllAround(
                new io.hertzian.dynamics.net.PacketAudioChunk(
                    voiceKey,
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    modulation,
                    (int) lastChunk.sampleRateHz(),
                    pcm),
                target);

            scopeCounter++;
            if (scopeCounter >= 4) {
                scopeCounter = 0;
                float snrDb = computeSnrDb(lastChunk);
                cpw.mods.fml.common.network.NetworkRegistry.TargetPoint scopeTarget = new cpw.mods.fml.common.network.NetworkRegistry.TargetPoint(
                    worldObj.provider.dimensionId,
                    xCoord + 0.5,
                    yCoord + 0.5,
                    zCoord + 0.5,
                    64.0);

                String station = demod.lastStationName(4_000L);
                io.hertzian.dynamics.net.NetworkHandler.CHANNEL.sendToAllAround(
                    new io.hertzian.dynamics.net.PacketReceiverScope(
                        worldObj.provider.dimensionId,
                        xCoord,
                        yCoord,
                        zCoord,
                        snrDb,
                        station),
                    scopeTarget);
            }
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("receiver mix/send failed", t);
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
        tag.setInteger("modulation", modulation.code());
        tag.setFloat("noiseReduction", noiseReduction);
        tag.setFloat("volume", volume);
        for (int i = 0; i < PRESET_COUNT; i++) {
            tag.setDouble("preHz" + i, presetHz[i]);
            tag.setFloat("preBw" + i, presetBw[i]);
            tag.setInteger("preMod" + i, presetMod[i]);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("tunedHz")) tunedHz = tag.getDouble("tunedHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
        if (tag.hasKey("antennaGain")) antennaGain = tag.getFloat("antennaGain");
        if (tag.hasKey("noiseReduction")) noiseReduction = tag.getFloat("noiseReduction");
        if (tag.hasKey("volume")) volume = tag.getFloat("volume");
        for (int i = 0; i < PRESET_COUNT; i++) {
            if (tag.hasKey("preHz" + i)) presetHz[i] = tag.getDouble("preHz" + i);
            if (tag.hasKey("preBw" + i)) presetBw[i] = tag.getFloat("preBw" + i);
            if (tag.hasKey("preMod" + i)) presetMod[i] = tag.getInteger("preMod" + i);
        }
        if (tag.hasKey("modulation")) {
            try {
                modulation = Modulation.fromCode(tag.getInteger("modulation"));
            } catch (IllegalArgumentException e) {
                modulation = Modulation.NARROW_FM;
            }
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
