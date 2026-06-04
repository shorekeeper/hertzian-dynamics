package io.hertzian.dynamics.audio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import net.minecraft.client.Minecraft;

import io.hertzian.dynamics.HertzianDynamics;

/**
 * Client side microphone configuration, the radio equivalent of the input
 * settings a voice chat mod exposes. It holds the values the capture path
 * and the push to talk handler read every tick:
 *
 * <ul>
 * <li>Activation mode. Push to talk keys the radio while the bound key
 * is held; voice activation keys it whenever the measured input
 * level rises above the threshold, hands free.</li>
 * <li>Input gain. A linear amplification applied to the raw capture
 * before it is sent on air, so a quiet headset can be brought up to
 * a usable level without touching the operating system mixer.</li>
 * <li>Voice threshold. The normalised input level at which voice
 * activation opens, with a small hang time applied by the handler so
 * words are not clipped at their tail.</li>
 * </ul>
 *
 * <p>
 * The values persist to a small properties file under the game config
 * directory so a chosen setup survives a restart. Loading is lazy and
 * guarded; a missing or unreadable file simply leaves the defaults in
 * place, which match a typical desktop headset.
 */
public final class MicrophoneConfig {

    /** How the radio decides to transmit. */
    public enum Activation {
        /** Transmit only while the bound key is held. */
        PUSH_TO_TALK,
        /** Transmit whenever the input level exceeds the threshold. */
        VOICE
    }

    private static final String FILE_NAME = "hertzian_microphone.properties";

    private static Activation activation = Activation.PUSH_TO_TALK;
    private static float gain = 1.0f; // linear, 0 to 4
    private static float voiceThreshold = 0.12f; // normalised RMS, 0 to 1

    private static boolean loaded = false;

    private MicrophoneConfig() {}

    public static synchronized Activation activation() {
        ensureLoaded();
        return activation;
    }

    public static synchronized float gain() {
        ensureLoaded();
        return gain;
    }

    public static synchronized float voiceThreshold() {
        ensureLoaded();
        return voiceThreshold;
    }

    public static synchronized void setActivation(Activation a) {
        ensureLoaded();
        activation = a == null ? Activation.PUSH_TO_TALK : a;
        save();
    }

    public static synchronized void setGain(float g) {
        ensureLoaded();
        gain = clamp(g, 0f, 4f);
        MicrophoneCapture.setGain(gain);
        save();
    }

    public static synchronized void setVoiceThreshold(float t) {
        ensureLoaded();
        voiceThreshold = clamp(t, 0f, 1f);
        save();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = configFile();
            if (f != null && f.isFile()) {
                Properties p = new Properties();
                try (FileInputStream in = new FileInputStream(f)) {
                    p.load(in);
                }
                String mode = p.getProperty("activation", "PUSH_TO_TALK");
                activation = "VOICE".equalsIgnoreCase(mode) ? Activation.VOICE : Activation.PUSH_TO_TALK;
                gain = clamp(parseFloat(p.getProperty("gain"), 1.0f), 0f, 4f);
                voiceThreshold = clamp(parseFloat(p.getProperty("threshold"), 0.12f), 0f, 1f);
            }
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("Microphone config load failed; using defaults", t);
        }
        // Push the loaded gain into the capture path so the first acquire
        // already runs at the configured amplification.
        MicrophoneCapture.setGain(gain);
    }

    private static void save() {
        try {
            File f = configFile();
            if (f == null) return;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                if (!dir.mkdirs()) {
                    HertzianDynamics.LOGGER.warn("Could not create config dir for microphone settings");
                }
            }
            Properties p = new Properties();
            p.setProperty("activation", activation.name());
            p.setProperty("gain", Float.toString(gain));
            p.setProperty("threshold", Float.toString(voiceThreshold));
            try (FileOutputStream out = new FileOutputStream(f)) {
                p.store(out, "Hertzian Dynamics microphone settings");
            }
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("Microphone config save failed", t);
        }
    }

    private static File configFile() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.mcDataDir == null) return null;
        return new File(new File(mc.mcDataDir, "config"), FILE_NAME);
    }

    private static float parseFloat(String s, float fallback) {
        if (s == null) return fallback;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
