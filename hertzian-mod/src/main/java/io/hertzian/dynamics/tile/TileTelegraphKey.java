package io.hertzian.dynamics.tile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.MorseEncoder;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Telegraph key: the transmit half of the telegraph pair. It is an audio
 * source for a CW transmitter, the way {@link TileMicrophone} and
 * {@link TileTestTone} are, but instead of voice or a test tone it feeds a
 * Morse keying stream. The owning block exposes a GUI with a text field
 * (typed text is encoded and sent automatically at the chosen speed) and
 * a straight key pad (hold to put a carrier on air, release for silence).
 *
 * <p>
 * How the keying reaches the air
 * The transmitter modulates whatever audio it is fed by its current
 * modulation. In CW that audio is a key-state stream: a value near one is
 * key down, near zero is key up, and the modulator applies the
 * raised-cosine envelope that stops the keying clicks from splattering
 * across the band. So this tile simply emits one per tick: it fills each
 * frame with ones while the key is down and zeros while it is up, then
 * hands the frame to the nearest transmitter through
 * {@link TileRadioTransmitter#enqueueAudio}. {@link #pumpInto} is called
 * once per server tick by the tick handler, exactly like the other audio
 * source tiles.
 *
 * <p>
 * Two keying sources share the timeline. An encoded message, queued
 * from typed text, plays out segment by segment and takes priority while
 * it lasts. When no message is playing the straight key state fills the
 * frame instead, so the operator can hand-send between automatic
 * messages. The nearest transmitter is switched to CW automatically, but
 * only when its modulation actually differs, so the switch does not force
 * a retune on every tick.
 */
public final class TileTelegraphKey extends TileEntity {

    private static final int RANGE_BLOCKS = 8;
    private static final float SAMPLE_RATE = 48_000.0f;

    /** Sending speed in words per minute, persisted. */
    private int wpm = 18;

    /** Pending keying segments, each {state(1 on/0 off), samples}. Server runtime only. */
    private final Deque<int[]> segments = new ArrayDeque<>();
    private int[] currentSegment = null;
    private int currentRemaining = 0;

    /** Straight key state, set by the GUI pad. Server runtime only. */
    private boolean straightKeyDown = false;

    public int wpm() {
        return wpm;
    }

    public void setWpm(int w) {
        this.wpm = Math.max(5, Math.min(40, w));
        markDirty();
    }

    /** Encode text at the current speed and queue it for sending. */
    public void queueText(String text) {
        double dotSamples = SAMPLE_RATE * (1200.0 / wpm) / 1000.0;
        List<int[]> encoded = MorseEncoder.encode(text, dotSamples);
        // Append after anything already queued so two quick sends do not
        // clobber each other; the operator hears them run back to back.
        segments.addAll(encoded);
    }

    /** Drop everything queued and stop sending immediately. */
    public void clearQueue() {
        segments.clear();
        currentSegment = null;
        currentRemaining = 0;
    }

    public void setStraightKey(boolean down) {
        this.straightKeyDown = down;
    }

    /** True while an encoded message is still playing out. */
    public boolean sending() {
        return currentSegment != null || !segments.isEmpty();
    }

    /**
     * Fill one frame of keying and feed it to the nearest CW transmitter.
     * Does nothing when neither a message nor the straight key is active,
     * which lets the transmitter squelch off air naturally between
     * transmissions.
     */
    public void pumpInto(java.util.List<TileRadioTransmitter> transmitters, WorldRfState state, int frameSamples) {
        if (worldObj == null || worldObj.isRemote) return;
        boolean active = sending() || straightKeyDown;
        if (!active) return;

        TileRadioTransmitter best = findNearest(transmitters);
        if (best == null) return;
        // Put the transmitter on CW so the key-state stream is modulated
        // correctly. Only touch it when it differs, otherwise every tick
        // would trigger a retune.
        if (best.modulation() != Modulation.CW) {
            best.setModulation(Modulation.CW);
        }

        float[] frame = new float[frameSamples];
        for (int i = 0; i < frameSamples; i++) {
            boolean on;
            if (currentSegment == null && !segments.isEmpty()) {
                currentSegment = segments.poll();
                currentRemaining = currentSegment[1];
            }
            if (currentSegment != null) {
                on = currentSegment[0] == 1;
                currentRemaining--;
                if (currentRemaining <= 0) currentSegment = null;
            } else {
                on = straightKeyDown;
            }
            frame[i] = on ? 1.0f : 0.0f;
        }
        best.enqueueAudio(frame);
    }

    private TileRadioTransmitter findNearest(java.util.List<TileRadioTransmitter> transmitters) {
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
        return best;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("wpm", wpm);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("wpm")) wpm = tag.getInteger("wpm");
    }
}
