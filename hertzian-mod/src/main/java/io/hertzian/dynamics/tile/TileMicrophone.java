package io.hertzian.dynamics.tile;

import java.util.List;

import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.world.WorldRfState;

/**
 * Microphone tile entity. Owns the placement and the per-tick drain
 * interface that feeds the nearest transmitter.
 *
 * <p>
 * The pump path here queues a fixed test tone into the nearest
 * transmitter as an end-to-end check of the audio chain. Live
 * capture reaches the transmitter through the push-to-talk uplink
 * path rather than through this tile.
 */
public final class TileMicrophone extends TileEntity {

    private static final int RANGE_BLOCKS = 8;

    public void pumpInto(List<TileRadioTransmitter> transmitters, WorldRfState state) {
        if (worldObj == null || worldObj.isRemote) return;
        TileRadioTransmitter best = null;
        double bestSq = (double) RANGE_BLOCKS * RANGE_BLOCKS;
        for (TileRadioTransmitter tx : transmitters) {
            if (tx.getWorldObj() != worldObj) continue;
            double dx = tx.xCoord - xCoord;
            double dy = tx.yCoord - yCoord;
            double dz = tx.zCoord - zCoord;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = tx;
            }
        }
        if (best == null) return;
        // A test tone is written rather than captured audio. The
        // steady frame keeps the transmitter AGC envelope warm so a
        // live signal arriving later produces no startup transient.
        best.enqueueAudio(TONE_FRAME);
    }

    /**
     * 50 ms of 1 kHz sine wave at 48 kHz. Keeps the audio pipeline
     * measurable and gives an audible confirmation that a receiver is
     * in range when the block is placed next to a transmitter.
     */
    private static final float[] TONE_FRAME;
    static {
        int n = 2400;
        TONE_FRAME = new float[n];
        for (int i = 0; i < n; i++) {
            TONE_FRAME[i] = (float) (0.7 * Math.sin(2 * Math.PI * 1000.0 * i / 48000.0));
        }
    }
}
