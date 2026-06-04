package io.hertzian.dynamics.tile;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import io.hertzian.dynamics.core.SpectrumChunk;

/**
 * Shared server-side helpers for the data-mode transceiver tiles (RTTY,
 * DTMF, SSTV). They all do the same housekeeping: find the nearest
 * transmitter to feed, extract demodulated audio from a mixed chunk,
 * derive a signal to noise ratio, and gate their receive work to when a
 * player is actually nearby so an unwatched terminal costs nothing. The
 * helpers live here so the three tiles do not each copy them.
 *
 * <p>
 * Audio extraction
 * The terminals work over single sideband. The receiver mixes the signal
 * to complex baseband; for USB the recovered audio is the real part of
 * that baseband, so the tones the codecs look for sit directly in the I
 * component. Taking the raw I rather than running it through the voice
 * passband keeps the data tones intact, which the comms filter would
 * otherwise clip at its 2.7 kHz edge.
 */
public final class DataModeSupport {

    public static final int RANGE_BLOCKS = 8;
    public static final double OBSERVER_RADIUS = 96.0;

    private DataModeSupport() {}

    public static TileRadioTransmitter findNearest(List<TileRadioTransmitter> txs, World world, int x, int y, int z) {
        TileRadioTransmitter best = null;
        double bestSq = (double) RANGE_BLOCKS * RANGE_BLOCKS;
        for (TileRadioTransmitter tx : txs) {
            if (tx.getWorldObj() != world) continue;
            double dx = tx.xCoord - x, dy = tx.yCoord - y, dz = tx.zCoord - z;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = tx;
            }
        }
        return best;
    }

    /** Real-part audio of a USB chunk, the tone signal the codecs parse. */
    public static float[] audioFromChunk(SpectrumChunk chunk) {
        int n = chunk.sampleCount();
        float[] iq = chunk.samples();
        float[] audio = new float[n];
        for (int k = 0; k < n; k++) audio[k] = iq[2 * k];
        return audio;
    }

    public static float snrDb(SpectrumChunk chunk) {
        float sig = chunk.signalPowerWatts();
        float noise = chunk.noiseFloorWatts();
        if (noise <= 0f) noise = 1.0e-21f;
        if (sig <= 0f) return -30f;
        double snr = 10.0 * Math.log10(sig / (double) noise);
        if (snr < -30.0) snr = -30.0;
        if (snr > 80.0) snr = 80.0;
        return (float) snr;
    }

    public static boolean hasObserver(World world, int x, int y, int z) {
        double r2 = OBSERVER_RADIUS * OBSERVER_RADIUS;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        for (Object o : world.playerEntities) {
            if (!(o instanceof EntityPlayer)) continue;
            EntityPlayer p = (EntityPlayer) o;
            double dx = p.posX - cx, dy = p.posY - cy, dz = p.posZ - cz;
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }
}
