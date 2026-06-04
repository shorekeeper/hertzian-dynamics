package io.hertzian.dynamics.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.NoiseEnvironment;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.item.RadioModel;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketAudioChunk;
import io.hertzian.dynamics.player.HertzianPlayerRadio;

/**
 * Server-side registry for player Handheld Radios. Each entry tracks the
 * rf-core slot keys for one player, the squelch timer that decides whether
 * the player's transmitter is on air, and the tuning the receiver slot was
 * last registered with.
 *
 * <p>
 * Active radio
 * The radio that runs is the active radio: a radio carried in the player's
 * gear slot takes priority, with the held item as a fallback. This lets a
 * slotted radio operate hands-free. The registry reads all radio fields
 * from whichever stack is active.
 *
 * <p>
 * Retune on change
 * The rf-core slot map caches the parameters a receiver or emission was
 * registered with and does not update them on its own; the block radios
 * deal with this by calling a force retune whenever a control changes. The
 * handheld keys its slots by a salted player UUID rather than a block
 * position, so it cannot use the block path. The registry instead remembers
 * the tuning it last applied for each player and releases the receiver and
 * emission slots whenever the stack reports a different value, so the next
 * registration rebuilds them on the new channel. Without this a frequency,
 * bandwidth or mode change on a handheld has no effect, because the cached
 * slot keeps mixing the old channel. The matching demodulator is dropped
 * with the receiver slot, so the audio chain restarts cleanly.
 *
 * <p>
 * Squelch and volume
 * A powered radio always keeps a receiver slot so it can listen, but the
 * speaker is gated by the squelch control. The registry maps the squelch
 * level to a signal to noise threshold; while the channel sits below it,
 * and the monitor flag is off, no audio is sent and the receiver stays
 * silent the way a real squelched radio does. When the gate is open the
 * recovered audio is scaled by the volume control before it is sent, so
 * the operator's volume knob changes what they hear.
 *
 * <p>
 * Self-monitoring (half-duplex and sidetone)
 * A real set is half-duplex: the co-located transmitter desenses the
 * receiver, so the operator hears nothing of the channel while keyed. The
 * registry models this directly. While the player is transmitting, the
 * receive chunk is delivered to them only when the self-monitor flag is
 * set, in which case it is dominated by their own co-located emission and
 * reads as a sidetone. With the flag clear, no chunk is sent during
 * transmit, so the operator hears silence while talking. This is the
 * behaviour the self-monitor option controls; it defaults off.
 *
 * <p>
 * PTT squelch on the transmit side: a player only puts a carrier into the
 * band while they have spoken within the last
 * {@link #VOICE_SQUELCH_HOLD_TICKS} ticks.
 */
public final class HandheldRadioRegistry {

    private HandheldRadioRegistry() {}

    private static final long EMISSION_SALT = 0xCAFEBABE_F00DBEEFL;
    private static final long RECEIVER_SALT = 0xDEADBEEF_FACEFEEDL;

    private static final long VOICE_SQUELCH_HOLD_TICKS = 15L;
    private static final float SILENCE_THRESHOLD = 0.005f;

    private static final Map<UUID, RadioState> STATES = new HashMap<>();

    private static final class RadioState {

        int currentDim = Integer.MIN_VALUE;
        long emissionKey;
        long receiverKey;
        long lastVoiceTick = Long.MIN_VALUE;
        boolean emissionLive = false;

        // Last receiver tuning applied to the rf-core slot. Used to detect
        // a change so the slot can be force retuned.
        double lastTunedHz = Double.NaN;
        float lastBandwidthHz = Float.NaN;
        int lastModCode = Integer.MIN_VALUE;
    }

    /**
     * The active radio for a player: the gear slot radio takes priority,
     * the held item is the fallback. Returns null if neither is a handheld
     * radio.
     */
    private static ItemStack activeRadio(EntityPlayer player) {
        HertzianPlayerRadio gear = HertzianPlayerRadio.get(player);
        if (gear != null) {
            ItemStack slotted = gear.radio();
            if (slotted != null) return slotted;
        }
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemHandheldRadio) return held;
        return null;
    }

    public static void tickPlayer(EntityPlayerMP player, long worldTick, float localHour) {
        ItemStack stack = activeRadio(player);
        boolean powered = stack != null && ItemHandheldRadio.isPowered(stack);
        UUID id = player.getUniqueID();
        RadioState st = STATES.get(id);

        if (!powered) {
            if (st != null) releaseSlots(player.worldObj, st);
            STATES.remove(id);
            return;
        }
        if (st == null) {
            st = new RadioState();
            st.emissionKey = (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) ^ EMISSION_SALT;
            st.receiverKey = (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) ^ RECEIVER_SALT;
            STATES.put(id, st);
        }

        WorldRfState world = WorldRfState.forWorld(player.worldObj);
        if (world == null) return;
        st.currentDim = player.worldObj.provider.dimensionId;

        double tunedHz = ItemHandheldRadio.tunedHz(stack);
        Modulation mod = ItemHandheldRadio.modulation(stack);
        float bandwidthHz = ItemHandheldRadio.bandwidthHz(stack);
        float txPowerW = ItemHandheldRadio.txPowerW(stack);

        // Detect a tuning change and drop the stale slots so the next
        // registration rebuilds them on the new channel.
        boolean retuned = tunedHz != st.lastTunedHz || bandwidthHz != st.lastBandwidthHz
            || mod.code() != st.lastModCode;
        if (retuned) {
            world.releaseReceiverByKey(st.receiverKey);
            if (st.emissionLive) {
                world.releaseEmissionByKey(st.emissionKey);
                st.emissionLive = false;
            }
            st.lastTunedHz = tunedHz;
            st.lastBandwidthHz = bandwidthHz;
            st.lastModCode = mod.code();
        }

        // Receiver slot: always present while powered. The receiver noise
        // figure comes from the radio model, so a better set hears a
        // weaker signal. The environment is residential by default; a
        // future location-aware value can feed in here.
        RadioModel rm = ItemHandheldRadio.model(stack);
        SpectrumManager.ReceiverParameters rxParams = new SpectrumManager.ReceiverParameters(
            tunedHz,
            bandwidthHz,
            mod,
            1.0f,
            (float) player.posX,
            (float) player.posY + 1.6f,
            (float) player.posZ,
            0f,
            0f,
            0f,
            rm.noiseFigureDb(),
            NoiseEnvironment.RESIDENTIAL);
        int rxId = world.getOrRegisterReceiver(st.receiverKey, rxParams);

        // Emission slot: squelched to push to talk activity. The radio is
        // a transmitter only while keyed within the hold window; powered
        // and idle it registers no emission, so it cannot jam its own
        // frequency. The explicit sentinel check guards against the long
        // overflow that Long.MIN_VALUE - worldTick produces, which wrapped
        // to a large negative value and read as "transmitting" the moment
        // the radio was powered.
        boolean transmitting = st.lastVoiceTick != Long.MIN_VALUE
            && (worldTick - st.lastVoiceTick) <= VOICE_SQUELCH_HOLD_TICKS;
        if (transmitting) {
            SpectrumManager.EmissionParameters txParams = new SpectrumManager.EmissionParameters(
                mod,
                (float) player.posX,
                (float) player.posY + 1.6f,
                (float) player.posZ,
                0f,
                0f,
                0f,
                txPowerW,
                1.0f,
                tunedHz,
                bandwidthHz,
                16_384);
            world.getOrRegisterEmission(st.emissionKey, txParams, false, 0, 0f, 0f);
            st.emissionLive = true;
        } else if (st.emissionLive) {
            world.releaseEmissionByKey(st.emissionKey);
            st.emissionLive = false;
        }

        // Half-duplex and sidetone. While transmitting, the co-located
        // transmitter desenses the receiver, so the operator hears no
        // channel audio. The emission is already registered above so other
        // stations still hear the player. The chunk is delivered back to
        // the operator only when self-monitor is on, where it is dominated
        // by their own emission and reads as a sidetone. With self-monitor
        // off, nothing is delivered during transmit.
        boolean sidetone = ItemHandheldRadio.selfMonitor(stack);
        if (transmitting && !sidetone) {
            return;
        }

        // Mix one chunk for the listen path.
        SpectrumChunk chunk = world.manager()
            .mix(
                world.grid(),
                world.materials(),
                world.ionosphere(),
                rxId,
                io.hertzian.dynamics.tick.RadioTickHandler.CHUNK_SAMPLES,
                worldTick,
                localHour);
        if (chunk == null) return;

        // Squelch gate. A closed gate sends nothing, so the receiver goes
        // quiet at once and the client RX indicator drops.
        float snrDb = computeSnrDb(chunk);
        boolean monitor = ItemHandheldRadio.monitor(stack);
        float thresholdDb = squelchThresholdDb(ItemHandheldRadio.squelch(stack));
        boolean open = monitor || snrDb >= thresholdDb;
        if (!open) return;

        io.hertzian.dynamics.core.ChunkDemodulator demod = world
            .getOrCreateDemodulator(st.receiverKey, mod, chunk.sampleRateHz());
        short[] pcm = new short[chunk.sampleCount()];
        demod.demodulateToPcm16(chunk, pcm);

        // Speaker volume.
        float volume = ItemHandheldRadio.volume(stack);
        if (volume < 0.999f) {
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) (pcm[i] * volume);
            }
        }

        String voiceKey = io.hertzian.dynamics.audio.ClientAudioBridge.handheldKey(player.getUniqueID());
        NetworkHandler.CHANNEL.sendTo(
            new PacketAudioChunk(
                voiceKey,
                player.worldObj.provider.dimensionId,
                (int) player.posX,
                (int) player.posY,
                (int) player.posZ,
                mod,
                (int) chunk.sampleRateHz(),
                pcm),
            player);
    }

    public static void deliverVoice(EntityPlayerMP player, float[] pcm) {
        UUID id = player.getUniqueID();
        RadioState st = STATES.get(id);
        if (st == null) return;
        WorldRfState world = WorldRfState.forWorld(player.worldObj);
        if (world == null) return;

        boolean meaningful = hasMeaningfulAudio(pcm);
        if (meaningful) {
            st.lastVoiceTick = player.worldObj.getTotalWorldTime();
        }
        if (!st.emissionLive) {
            return;
        }
        try {
            ItemStack stack = activeRadio(player);
            if (stack == null) return;
            int emissionId = lookupOrRegisterEmission(world, st, stack, player);
            world.manager()
                .pushAudio(emissionId, pcm);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("handheld voice push failed", t);
        }
    }

    private static int lookupOrRegisterEmission(WorldRfState world, RadioState st, ItemStack stack,
        EntityPlayerMP player) {
        SpectrumManager.EmissionParameters params = new SpectrumManager.EmissionParameters(
            ItemHandheldRadio.modulation(stack),
            (float) player.posX,
            (float) player.posY + 1.6f,
            (float) player.posZ,
            0f,
            0f,
            0f,
            ItemHandheldRadio.txPowerW(stack),
            1.0f,
            ItemHandheldRadio.tunedHz(stack),
            ItemHandheldRadio.bandwidthHz(stack),
            16_384);
        return world.getOrRegisterEmission(st.emissionKey, params, false, 0, 0f, 0f);
    }

    /**
     * Map a squelch level in [0, 1] to a signal to noise threshold in dB.
     * Zero opens on almost anything (a slightly negative threshold so an
     * empty channel's own noise just clears it); one needs a strong signal
     * before the gate lifts.
     */
    private static float squelchThresholdDb(float squelch) {
        if (squelch < 0f) squelch = 0f;
        if (squelch > 1f) squelch = 1f;
        return -5f + squelch * 30f;
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

    private static boolean hasMeaningfulAudio(float[] samples) {
        if (samples == null || samples.length == 0) return false;
        int step = Math.max(1, samples.length / 16);
        for (int i = 0; i < samples.length; i += step) {
            if (Math.abs(samples[i]) > SILENCE_THRESHOLD) return true;
        }
        return false;
    }

    private static void releaseSlots(net.minecraft.world.World worldObj, RadioState st) {
        if (worldObj == null) return;
        WorldRfState world = WorldRfState.forWorld(worldObj);
        if (world == null) return;
        if (st.emissionLive) {
            world.releaseEmissionByKey(st.emissionKey);
            st.emissionLive = false;
        }
        world.releaseReceiverByKey(st.receiverKey);
        st.lastTunedHz = Double.NaN;
        st.lastBandwidthHz = Float.NaN;
        st.lastModCode = Integer.MIN_VALUE;
    }
}
