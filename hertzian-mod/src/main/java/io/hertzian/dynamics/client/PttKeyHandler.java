package io.hertzian.dynamics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.hertzian.dynamics.audio.EnvironmentMixer;
import io.hertzian.dynamics.audio.MicrophoneCapture;
import io.hertzian.dynamics.audio.MicrophoneConfig;
import io.hertzian.dynamics.gui.GuiIds;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketVoiceUplink;
import io.hertzian.dynamics.player.HertzianPlayerRadio;

/**
 * Push-to-talk and voice activation handler. The radio that responds is
 * the active radio: a radio carried in the gear slot takes priority, with
 * the held item as a fallback. This lets push-to-talk key the set while
 * the player does anything else with their hands, including holding a
 * weapon. The radio can be keyed two ways, chosen in the microphone
 * settings:
 *
 * <ul>
 * <li>Push to talk. The microphone is opened only while the bound key
 * is held, and every captured block is sent on air. Releasing the
 * key stops capture.</li>
 * <li>Voice activation. The microphone stays open while a powered
 * radio is active and the radio is keyed automatically whenever the
 * input level rises above the configured threshold. A short hang
 * time keeps the carrier up through the brief gaps between words
 * so speech is not chopped.</li>
 * </ul>
 *
 * <p>
 * Roger beep
 * When the transmission ends, the falling edge of the transmit state, and
 * the active radio has its roger beep flag set, the handler synthesises a
 * short courtesy tone and sends it up the same voice path as speech. The
 * beep rides over the air to the far station, the way a real radio's
 * courtesy tone does, rather than being a local sound effect. The
 * transmitter side squelch hold keeps the carrier alive for a fraction of
 * a second after the last meaningful audio, which is the window the beep
 * uses to reach the air before the carrier drops. The tone is a fixed
 * single burst with raised cosine edges so it does not click; only its
 * on/off state is a control, matching real radios.
 *
 * <p>
 * Headset
 * A headset carried in the gear slot is a close-talk microphone, so the
 * environment bleed that an open speaker microphone would pick up is
 * strongly attenuated before the chunk is sent. Without a headset the
 * full environment bleed is mixed under the voice as before.
 *
 * <p>
 * The captured PCM is already amplified by the configured input gain
 * inside {@link MicrophoneCapture#drain}, so the level the settings meter
 * shows is the level that goes on air. Capture state is a refcount on the
 * capture device, so this handler coexists with the settings screen's own
 * metering session without fighting over the hardware.
 */
public final class PttKeyHandler {

    private static final PttKeyHandler INSTANCE = new PttKeyHandler();

    /** Hang time after the level drops below threshold, milliseconds. */
    private static final long VOICE_HANG_MS = 300L;

    /** Residual fraction of the environment bleed kept when a headset is worn. */
    private static final float HEADSET_BLEED_FACTOR = 0.12f;

    /** Roger beep is a short descending two tone courtesy burst. */
    private static final float ROGER_HI_HZ = 1200f;
    private static final float ROGER_LO_HZ = 900f;
    /** Duration of each of the two tones, seconds. */
    private static final float ROGER_SEG_SECONDS = 0.07f;
    /** Raised cosine edge length, seconds, so the burst has no key click. */
    private static final float ROGER_RAMP_SECONDS = 0.012f;
    /**
     * Burst level. Kept well below full scale on purpose: the transmitter
     * applies pre-emphasis to FM, which boosts a high pitched tone, and a
     * loud tone then hits the limiter and breaks into harmonics, the harsh
     * square buzz a pure loud beep produced before. A moderate level and a
     * lower pitch leave the headroom for the tone to stay clean.
     */
    private static final float ROGER_LEVEL = 0.3f;

    /** Client transmit state for this tick, read by the HUD overlay. */
    private static volatile boolean transmittingNow = false;

    public static boolean isTransmitting() {
        return transmittingNow;
    }

    private final KeyBinding ptt = new KeyBinding("key.hertzian.ptt", Keyboard.KEY_V, "key.categories.hertzian");
    private final KeyBinding micCfg = new KeyBinding(
            "key.hertzian.micConfig",
            Keyboard.KEY_M,
            "key.categories.hertzian");
    private final KeyBinding gearKey = new KeyBinding(
            "key.hertzian.radioGear",
            Keyboard.KEY_B,
            "key.categories.hertzian");

    private MicrophoneCapture mic;
    private boolean capturing = false;
    private boolean wasTransmitting = false;
    private long lastAboveThresholdMs = Long.MIN_VALUE;

    private PttKeyHandler() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(INSTANCE.ptt);
        ClientRegistry.registerKeyBinding(INSTANCE.micCfg);
        ClientRegistry.registerKeyBinding(INSTANCE.gearKey);
        cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(INSTANCE);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (micCfg.isPressed()) {
            Minecraft.getMinecraft()
                    .displayGuiScreen(new io.hertzian.dynamics.gui.GuiMicrophoneSelect());
        }
        if (gearKey.isPressed()) {
            NetworkHandler.CHANNEL.sendToServer(new io.hertzian.dynamics.net.PacketOpenRadioGear());
        }

        if (event.phase != TickEvent.Phase.END) return;

        ItemStack stack = activeRadio();
        boolean radioActive = stack != null && ItemHandheldRadio.isPowered(stack);
        MicrophoneConfig.Activation mode = MicrophoneConfig.activation();

        // Decide whether the device should be open this tick. In voice
        // mode it stays open while a radio is active; in push to talk it
        // opens only with the key.
        boolean wantCapture = radioActive && (mode == MicrophoneConfig.Activation.VOICE || ptt.getIsKeyPressed());

        if (wantCapture && !capturing) {
            mic = MicrophoneCapture.acquire();
            capturing = mic != null;
        } else if (!wantCapture && capturing) {
            if (mic != null) mic.release();
            mic = null;
            capturing = false;
            lastAboveThresholdMs = Long.MIN_VALUE;
        }

        // Compute the transmit state for this tick.
        boolean transmit = false;
        float[] pcm = new float[0];
        if (capturing && mic != null) {
            pcm = mic.drain();
            if (mode == MicrophoneConfig.Activation.VOICE) {
                long now = System.currentTimeMillis();
                if (mic.lastLevel() >= MicrophoneConfig.voiceThreshold()) {
                    lastAboveThresholdMs = now;
                }
                transmit = (now - lastAboveThresholdMs) <= VOICE_HANG_MS;
            } else {
                // Push to talk: capture only happens while keyed, so any
                // captured block is meant to go out.
                transmit = true;
            }
        }

        if (transmit) {
            // Environment bleed. While keyed, the radio also picks up the
            // loud or close game sounds the player hears, mixed in under
            // the voice the way a handheld near a firefight carries the
            // gunfire. Half duplex keying breaks any feedback path: a
            // transmitting radio plays no received audio, so the bleed
            // cannot loop back into the link. A headset attenuates the
            // bleed because a close-talk mic rejects ambient sound. The
            // mic block sizes the chunk; when the mic produced nothing
            // this tick a default block is used so the environment still
            // goes out.
            int n = pcm.length > 0 ? pcm.length : 2400;
            float voiceLevel = (mic != null) ? mic.lastLevel() : 0f;
            float envScale = hasHeadset() ? HEADSET_BLEED_FACTOR : 1.0f;
            float[] env = EnvironmentMixer.get()
                    .drain(n, voiceLevel);
            if (pcm.length == 0) {
                for (int i = 0; i < env.length; i++) env[i] *= envScale;
                pcm = env;
            } else {
                for (int i = 0; i < pcm.length; i++) {
                    float v = pcm[i] + env[i] * envScale;
                    pcm[i] = v > 1f ? 1f : (v < -1f ? -1f : v);
                }
            }
            if (hasEnergy(pcm)) {
                NetworkHandler.CHANNEL.sendToServer(new PacketVoiceUplink(pcm));
            }
        }

        // Roger beep on the falling edge of transmit, while still carrying
        // the radio so a set put away mid over does not chirp. The beep is
        // sent even after the mic is released, because the server side
        // carrier is still up during its squelch hold.
        if (wasTransmitting && !transmit && radioActive && ItemHandheldRadio.rogerBeep(stack)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketVoiceUplink(makeRogerBeep()));
        }
        wasTransmitting = transmit;
        transmittingNow = transmit;
    }

    /** True if the block carries any non trivial energy worth sending. */
    private static boolean hasEnergy(float[] pcm) {
        for (float v : pcm) {
            if (v > 0.0008f || v < -0.0008f) return true;
        }
        return false;
    }

    /**
     * The radio that responds to the key this tick. The gear slot radio
     * takes priority over a held radio so a slotted set keeps working with
     * hands full. Returns null if neither is a handheld radio.
     */
    private ItemStack activeRadio() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return null;
        EntityPlayer p = mc.thePlayer;
        HertzianPlayerRadio gear = HertzianPlayerRadio.get(p);
        if (gear != null) {
            ItemStack slotted = gear.radio();
            if (slotted != null) return slotted;
        }
        ItemStack held = p.getCurrentEquippedItem();
        if (held != null && held.getItem() instanceof ItemHandheldRadio) return held;
        return null;
    }

    /** True when the player carries a headset in the gear headset slot. */
    private boolean hasHeadset() {
        HertzianPlayerRadio gear = HertzianPlayerRadio.get(Minecraft.getMinecraft().thePlayer);
        return gear != null && gear.hasHeadset();
    }

    /**
     * Build one roger beep burst as float PCM in [-1, 1] at the capture
     * rate. A short descending two tone, high then low, with raised cosine
     * attack and release and phase carried continuously across the tone
     * change so there is no click at the seam. Moderate level and modest
     * pitch keep it from clipping through the transmitter pre-emphasis and
     * limiter, which is what made the earlier single loud high tone sound
     * like a cheap square wave.
     */
    private static float[] makeRogerBeep() {
        int seg = (int) (MicrophoneCapture.SAMPLE_RATE * ROGER_SEG_SECONDS);
        int ramp = (int) (MicrophoneCapture.SAMPLE_RATE * ROGER_RAMP_SECONDS);
        int n = seg * 2;
        float[] pcm = new float[n];
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            float hz = (i < seg) ? ROGER_HI_HZ : ROGER_LO_HZ;
            phase += 2.0 * Math.PI * hz / MicrophoneCapture.SAMPLE_RATE;
            if (phase > Math.PI) phase -= 2.0 * Math.PI;
            float env;
            if (i < ramp) {
                env = 0.5f * (1f - (float) Math.cos(Math.PI * i / ramp));
            } else if (i > n - ramp) {
                env = 0.5f * (1f - (float) Math.cos(Math.PI * (n - i) / ramp));
            } else {
                env = 1f;
            }
            pcm[i] = (float) (ROGER_LEVEL * env * Math.sin(phase));
        }
        return pcm;
    }
}