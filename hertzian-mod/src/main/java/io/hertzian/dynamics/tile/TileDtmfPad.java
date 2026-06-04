package io.hertzian.dynamics.tile;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.network.NetworkRegistry;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.DtmfCodec;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketDtmfData;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * DTMF keypad and tone selcall transceiver. Pressing a key sends its dual
 * tone to the nearest transmitter; the tile also listens on its frequency
 * and decodes incoming digits. A configurable selcall code lets the block
 * act as a selective-call receiver: when the trailing decoded digits
 * match the code, it raises a redstone output, the in-world equivalent of
 * a radio that only opens its squelch for its own address.
 *
 * <p>
 * Transmit and receive follow the same pattern as the other data-mode
 * tiles. The selcall match drives {@link #selcallActive}, which the block
 * reads for its redstone power; the match latches for a short window so a
 * one-shot tone burst produces a usable pulse rather than a single-tick
 * flicker.
 */
public final class TileDtmfPad extends TileEntity {

    private double tunedHz = 27_185_000.0; // CB channel 19 area.
    private float bandwidthHz = 3_000.0f;
    private String selcallCode = "1234";

    private final DtmfCodec.Encoder encoder = new DtmfCodec.Encoder();
    private transient DtmfCodec.Decoder decoder;
    private int sendCounter = 0;

    private boolean selcallActive = false;
    private long selcallUntilTick = 0;
    private static final long SELCALL_HOLD_TICKS = 60; // 3 s latch.

    private String clientDigits = "";
    private char clientLast = 0;
    private float clientSnrDb = -30f;
    private boolean clientSelcall = false;

    public double tunedHz() {
        return tunedHz;
    }

    public float bandwidthHz() {
        return bandwidthHz;
    }

    public String selcallCode() {
        return selcallCode;
    }

    public boolean selcallActive() {
        return selcallActive;
    }

    public String clientDigits() {
        return clientDigits;
    }

    public char clientLast() {
        return clientLast;
    }

    public float clientSnrDb() {
        return clientSnrDb;
    }

    public boolean clientSelcall() {
        return clientSelcall;
    }

    public void setClientData(String digits, char last, float snr, boolean selcall) {
        this.clientDigits = digits == null ? "" : digits;
        this.clientLast = last;
        this.clientSnrDb = snr;
        this.clientSelcall = selcall;
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

    public void setSelcallCode(String c) {
        this.selcallCode = c == null ? "" : c;
        markDirty();
        syncClient();
    }

    public void queueDigit(char c) {
        encoder.queueDigit(c);
    }

    public void queueSequence(String s) {
        encoder.queue(s);
    }

    public void clearRx() {
        if (decoder != null) decoder.clear();
        clientDigits = "";
    }

    private void retune() {
        if (worldObj == null || worldObj.isRemote) return;
        WorldRfState s = WorldRfState.forWorld(worldObj);
        if (s != null) s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
    }

    private void syncClient() {
        if (worldObj != null && !worldObj.isRemote) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void serverTick(WorldRfState state, List<TileRadioTransmitter> txs, int samples, long worldTick,
        float localHour) {
        if (worldObj == null || worldObj.isRemote) return;

        if (encoder.active()) {
            TileRadioTransmitter best = DataModeSupport.findNearest(txs, worldObj, xCoord, yCoord, zCoord);
            if (best != null) {
                if (best.modulation() != Modulation.USB) best.setModulation(Modulation.USB);
                float[] frame = new float[samples];
                encoder.fill(frame);
                best.enqueueAudio(frame);
            }
        }

        // Selcall latch expiry runs even with no observer so redstone is
        // honest about timing rather than frozen while the player is away.
        if (selcallActive && worldTick > selcallUntilTick) {
            selcallActive = false;
            worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, getBlockType());
        }

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
            HertzianDynamics.LOGGER.error("dtmf mix failed", t);
            return;
        }
        if (chunk == null) return;

        float snr = DataModeSupport.snrDb(chunk);
        float[] audio = DataModeSupport.audioFromChunk(chunk);
        if (decoder == null) decoder = new DtmfCodec.Decoder();
        decoder.feed(audio, chunk.sampleCount(), snr);

        // Selcall: trailing digits match the configured code.
        boolean match = false;
        String digits = decoder.digits();
        if (!selcallCode.isEmpty() && digits.endsWith(selcallCode)) {
            match = true;
            if (!selcallActive) {
                selcallActive = true;
                worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, getBlockType());
            }
            selcallUntilTick = worldTick + SELCALL_HOLD_TICKS;
        }

        sendCounter++;
        if (sendCounter >= 3) {
            sendCounter = 0;
            NetworkRegistry.TargetPoint target = new NetworkRegistry.TargetPoint(
                worldObj.provider.dimensionId,
                xCoord + 0.5,
                yCoord + 0.5,
                zCoord + 0.5,
                64.0);
            String tail = digits.length() > 24 ? digits.substring(digits.length() - 24) : digits;
            NetworkHandler.CHANNEL.sendToAllAround(
                new PacketDtmfData(
                    worldObj.provider.dimensionId,
                    xCoord,
                    yCoord,
                    zCoord,
                    snr,
                    match || selcallActive,
                    decoder.lastChar(),
                    tail),
                target);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("tunedHz", tunedHz);
        tag.setFloat("bandwidthHz", bandwidthHz);
        tag.setString("selcall", selcallCode);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("tunedHz")) tunedHz = tag.getDouble("tunedHz");
        if (tag.hasKey("bandwidthHz")) bandwidthHz = tag.getFloat("bandwidthHz");
        if (tag.hasKey("selcall")) selcallCode = tag.getString("selcall");
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
