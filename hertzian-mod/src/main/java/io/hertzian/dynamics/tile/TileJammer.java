package io.hertzian.dynamics.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Tile entity for the wideband noise jammer. Slot-driven like the
 * other radio tiles. When {@link #active} is false the jammer
 * stays out of the slot map (and out of the spectrum manager
 * altogether), so a deactivated jammer costs no CPU.
 */
public final class TileJammer extends TileEntity {

    private boolean active = true;
    private double carrierHz = 145_000_000.0;
    private float bandwidthHz = 30_000.0f;
    private float txPowerW = 100.0f;
    private int jamProfile = 2;
    private float jamRateHz = 10.0f;
    private float jamSigma = 1.0f;

    public boolean active() {
        return active;
    }

    public void setActive(boolean v) {
        this.active = v;
        markDirty();
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState state = WorldRfState.forWorld(worldObj);
        if (state != null) {
            state.forceRetuneEmissionAt(xCoord, yCoord, zCoord);
        }
    }

    public double carrierHz() {
        return carrierHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public int jamProfile() {
        return jamProfile;
    }

    public String jamProfileLabel() {
        return jamProfile == 1 ? "Continuous" : "Pulsed";
    }

    public void setCarrierHz(double hz) {
        this.carrierHz = hz;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.forceRetuneEmissionAt(xCoord, yCoord, zCoord);
        }
    }

    public void setBandwidthHz(float hz) {
        this.bandwidthHz = hz;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.forceRetuneEmissionAt(xCoord, yCoord, zCoord);
        }
    }

    public void setJamProfile(int p) {
        this.jamProfile = p;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.forceRetuneEmissionAt(xCoord, yCoord, zCoord);
        }
    }

    public void tickSlot(WorldRfState state) {
        if (worldObj == null || worldObj.isRemote) return;
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);
        if (!active) {
            state.releaseSlotsAt(xCoord, yCoord, zCoord);
            return;
        }
        SpectrumManager.EmissionParameters params = new SpectrumManager.EmissionParameters(
            Modulation.NOISE,
            xCoord + 0.5f,
            yCoord + 0.5f,
            zCoord + 0.5f,
            0f,
            0f,
            0f,
            txPowerW,
            1.0f,
            carrierHz,
            bandwidthHz,
            0);
        try {
            state.getOrRegisterEmission(key, params, true, jamProfile, jamRateHz, jamSigma);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("jammer slot resolution failed", t);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setBoolean("active", active);
        tag.setDouble("carrierHz", carrierHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
        tag.setFloat("txPowerW", txPowerW);
        tag.setInteger("jamProfile", jamProfile);
        tag.setFloat("jamRateHz", jamRateHz);
        tag.setFloat("jamSigma", jamSigma);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("active")) active = tag.getBoolean("active");
        if (tag.hasKey("carrierHz")) carrierHz = tag.getDouble("carrierHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
        if (tag.hasKey("txPowerW")) txPowerW = tag.getFloat("txPowerW");
        if (tag.hasKey("jamProfile")) jamProfile = tag.getInteger("jamProfile");
        if (tag.hasKey("jamRateHz")) jamRateHz = tag.getFloat("jamRateHz");
        if (tag.hasKey("jamSigma")) jamSigma = tag.getFloat("jamSigma");
    }

    /**
     * Client sync. The default tile entity sends no description packet,
     * so before this override the client copy kept its field
     * initialisers (145 MHz, NarrowFM) instead of the real server
     * tuning, which is why the GUI showed stale defaults after a chunk
     * reload. Writing the full NBT into the update packet brings the
     * client copy in line; onDataPacket reads it back. Live edits push a
     * fresh packet through World.markBlockForUpdate in the settings
     * packet handler.
     */
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
