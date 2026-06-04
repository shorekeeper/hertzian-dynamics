package io.hertzian.dynamics.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.ChunkDemodulator;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.world.ChunkLoadManager;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Relay (repeater). Listens on inputHz, demodulates to audio, and
 * re-emits that audio on outputHz at its own location and power. A chain
 * of relays extends a link past a single hop's range or around terrain
 * the Fresnel solver closes.
 *
 * <p>
 * It holds both a receiver slot and an emission slot at its block
 * position; the rf-core slot maps key receivers and emissions
 * separately, so one position carries both without collision. While
 * active it force loads its chunk through ChunkLoadManager so it keeps
 * relaying when no player is nearby, the same mechanism the broadcast
 * transmitter uses.
 *
 * <p>
 * Squelch and feedback
 * The relay only transmits when the received signal to noise ratio is
 * above INPUT_SQUELCH_DB, with a short hold so brief dips do not chop
 * the output. Output must differ from input by at least the bandwidth,
 * otherwise the relay would hear its own transmission and run away into
 * feedback; below that separation the transmit side stays off and a
 * warning is logged once.
 */
public final class TileRelay extends TileEntity {

    private boolean active = true;
    private double inputHz = 145_000_000.0;
    private double outputHz = 145_500_000.0;
    private float bandwidthHz = 15_000.0f;
    private float txPowerW = 25.0f;
    private float antennaGain = 1.0f;
    private Modulation modulation = Modulation.NARROW_FM;

    private static final float INPUT_SQUELCH_DB = 6.0f;
    private static final int SQUELCH_HOLD_TICKS = 15;
    private long lastSignalTick = Long.MIN_VALUE;
    private boolean feedbackWarned = false;
    private transient int lastAntennaSections = -1;

    public boolean active() {
        return active;
    }

    public double inputHz() {
        return inputHz;
    }

    public double outputHz() {
        return outputHz;
    }

    public float txPowerW() {
        return txPowerW;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public Modulation modulation() {
        return modulation;
    }

    public void setActive(boolean v) {
        this.active = v;
        markDirty();
        if (worldObj == null || worldObj.isRemote) return;
        if (v) {
            ChunkLoadManager.request(worldObj, xCoord, yCoord, zCoord);
        } else {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.releaseSlotsAt(xCoord, yCoord, zCoord);
            ChunkLoadManager.release(worldObj, xCoord, yCoord, zCoord);
        }
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void setInputHz(double hz) {
        this.inputHz = hz;
        retune();
    }

    public void setOutputHz(double hz) {
        this.outputHz = hz;
        retune();
    }

    public void setTxPowerW(float w) {
        this.txPowerW = w;
        retune();
    }

    public void setModulation(Modulation m) {
        this.modulation = m;
        retune();
    }

    private void retune() {
        markDirty();
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState s = WorldRfState.forWorld(worldObj);
        if (s != null) {
            s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
            s.releaseEmissionAt(xCoord, yCoord, zCoord);
        }
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void tick(WorldRfState state, int sampleCount, long worldTick, float localHour) {
        if (worldObj == null || worldObj.isRemote) return;
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);
        if (!active) {
            state.releaseSlotsAt(xCoord, yCoord, zCoord);
            return;
        }
        ChunkLoadManager.request(worldObj, xCoord, yCoord, zCoord);

        int sections = io.hertzian.dynamics.world.AntennaSupport.countAbove(worldObj, xCoord, yCoord, zCoord);
        if (sections != lastAntennaSections) {
            lastAntennaSections = sections;
            state.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
            state.releaseEmissionAt(xCoord, yCoord, zCoord);
        }
        float effGain = antennaGain * io.hertzian.dynamics.world.AntennaSupport.gainFromSections(sections);
        float effY = yCoord + 0.5f + sections;

        SpectrumManager.ReceiverParameters rxp = SpectrumManager.ReceiverParameters
            .of(inputHz, bandwidthHz, modulation, xCoord + 0.5f, effY, zCoord + 0.5f);
        SpectrumChunk chunk;
        int rxId;
        try {
            rxId = state.getOrRegisterReceiver(key, rxp);
            chunk = state.manager()
                .mix(state.grid(), state.materials(), state.ionosphere(), rxId, sampleCount, worldTick, localHour);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("relay receive failed", t);
            return;
        }
        if (chunk == null) return;

        float sig = chunk.signalPowerWatts();
        float noise = chunk.noiseFloorWatts() <= 0f ? 1.0e-21f : chunk.noiseFloorWatts();
        double snrDb = sig > 0f ? 10.0 * Math.log10(sig / (double) noise) : -100.0;
        if (snrDb >= INPUT_SQUELCH_DB) {
            lastSignalTick = worldTick;
        }
        boolean transmitting = (worldTick - lastSignalTick) <= SQUELCH_HOLD_TICKS;

        boolean separated = Math.abs(outputHz - inputHz) >= bandwidthHz;
        if (!separated) {
            if (!feedbackWarned) {
                HertzianDynamics.LOGGER.warn(
                    "Relay at ({}, {}, {}) output too close to input; transmit disabled to avoid feedback",
                    xCoord,
                    yCoord,
                    zCoord);
                feedbackWarned = true;
            }
            state.releaseEmissionAt(xCoord, yCoord, zCoord);
            return;
        }
        feedbackWarned = false;

        if (!transmitting) {
            state.releaseEmissionAt(xCoord, yCoord, zCoord);
            return;
        }

        ChunkDemodulator demod = state.getOrCreateDemodulator(key, modulation, chunk.sampleRateHz());
        float[] pcm = new float[chunk.sampleCount()];
        demod.demodulate(chunk, pcm);

        SpectrumManager.EmissionParameters ep = new SpectrumManager.EmissionParameters(
            modulation,
            xCoord + 0.5f,
            effY,
            zCoord + 0.5f,
            0f,
            0f,
            0f,
            txPowerW,
            effGain,
            outputHz,
            bandwidthHz,
            16_384);
        try {
            int emId = state.getOrRegisterEmission(key, ep, false, 0, 0f, 0f);
            state.manager()
                .pushAudio(emId, pcm);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("relay transmit failed", t);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setBoolean("active", active);
        tag.setDouble("inputHz", inputHz);
        tag.setDouble("outputHz", outputHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
        tag.setFloat("txPowerW", txPowerW);
        tag.setFloat("antennaGain", antennaGain);
        tag.setInteger("modulation", modulation.code());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("active")) active = tag.getBoolean("active");
        if (tag.hasKey("inputHz")) inputHz = tag.getDouble("inputHz");
        if (tag.hasKey("outputHz")) outputHz = tag.getDouble("outputHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
        if (tag.hasKey("txPowerW")) txPowerW = tag.getFloat("txPowerW");
        if (tag.hasKey("antennaGain")) antennaGain = tag.getFloat("antennaGain");
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

    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj != null && !worldObj.isRemote) {
            ChunkLoadManager.release(worldObj, xCoord, yCoord, zCoord);
        }
    }
}
