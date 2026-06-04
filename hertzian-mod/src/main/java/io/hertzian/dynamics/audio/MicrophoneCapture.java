package io.hertzian.dynamics.audio;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCdevice;

import io.hertzian.dynamics.HertzianDynamics;

/**
 * Microphone capture via OpenAL ALC_EXT_CAPTURE. Lives entirely
 * on the client: opens the user-selected capture device, polls
 * available samples each client tick, and exposes them as float
 * arrays normalised to [-1, 1].
 *
 * <p>
 * LWJGL2 form: {@link ALC11#alcCaptureOpenDevice(String,int,int,int)}
 * returns an {@link ALCdevice} object, not a long handle. Capture
 * samples come back as int16 mono PCM in a {@link ByteBuffer}.
 *
 * <p>
 * Capture is single-instance because most platforms refuse to
 * open the default microphone twice from the same process. The
 * subsystem starts on first request and stops when the last
 * client releases it.
 *
 * <p>
 * Input gain and level metering
 * The capture path now applies a configurable linear gain to the
 * drained samples, so a quiet microphone can be raised to a usable
 * level without leaving the game. The same drain computes a smoothed
 * root mean square level, exposed through {@link #lastLevel()}, which
 * the settings screen draws as a live input meter and which the push
 * to talk handler uses for voice activation. Both the gain and the
 * level are post amplification, so what the meter shows is what goes
 * on air.
 *
 * <p>
 * Device selection
 * The class exposes {@link #listDevices()} to enumerate the names the driver reports
 * for {@link ALC11#ALC_CAPTURE_DEVICE_SPECIFIER}, and
 * {@link #setPreferredDevice(String)} to pin one of them for the next
 * acquire. Passing {@code null} restores the default behaviour.
 *
 * <p>
 * The enumeration uses the standard {@code ALC_CAPTURE_DEVICE_SPECIFIER}
 * call. AL reports the device names as a single NUL-separated UTF-8
 * string terminated by an extra NUL byte. The parsing loop walks the
 * buffer slot by slot and accumulates one entry per NUL boundary.
 */
public final class MicrophoneCapture {

    /**
     * Engine capture sample rate. Matches the engine playback rate
     * so the PCM can be pushed into a transmitter without any
     * resampling stage.
     */
    public static final int SAMPLE_RATE = 48_000;
    /** Internal device-side ring buffer size, in samples. */
    private static final int DEVICE_BUFFER_SAMPLES = 48_000;

    private static MicrophoneCapture instance;

    /**
     * User-selected device name. {@code null} means "use the
     * platform default", which is also the behaviour on a fresh
     * install before the user touches the setting.
     */
    private static String preferredDevice = null;

    /** Linear input gain shared across acquire cycles. */
    private static volatile float gain = 1.0f;

    private final ALCdevice device;
    private final String deviceName;
    private boolean capturing = false;
    private int refCount = 0;
    private final IntBuffer countScratch = BufferUtils.createIntBuffer(1);
    private final ByteBuffer pcmScratch = BufferUtils.createByteBuffer(8192);

    /** Smoothed post gain RMS of the last drained block, for the meter. */
    private volatile float lastLevel = 0f;

    private MicrophoneCapture(ALCdevice device, String name) {
        this.device = device;
        this.deviceName = name;
    }

    /**
     * Set the linear input gain applied to every drained block. A
     * value of one passes the capture through unchanged; higher values
     * amplify. Clamped to a sane band so a runaway setting cannot turn
     * the floor noise into a full scale roar.
     */
    public static void setGain(float g) {
        if (g < 0f) g = 0f;
        if (g > 8f) g = 8f;
        gain = g;
    }

    /** Current linear input gain. */
    public static float gain() {
        return gain;
    }

    /**
     * Smoothed post gain input level of the most recent drain, in
     * roughly [0, 1]. Zero when no capture is active.
     */
    public float lastLevel() {
        return lastLevel;
    }

    /**
     * Return the immutable list of capture device names reported by
     * the AL driver. The first entry, if any, is the system default.
     * An empty list means the platform exposes no capture device.
     *
     * <p>
     * Names are platform-defined strings such as
     * {@code "Microphone (Realtek Audio)"} on Windows. The mod does
     * not prettify them: the strings are what the driver reports.
     */
    public static synchronized List<String> listDevices() {
        try {
            String raw = ALC10.alcGetString(null, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (String part : raw.split("\0", -1)) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                    result.add(trimmed);
                }
            }
            return result;
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("Microphone device enumeration failed", t);
            return Collections.emptyList();
        }
    }

    /**
     * Set the device name to use on the next {@link #acquire} call.
     * {@code null} resets to platform default. Already-open capture
     * sessions keep using the device they were opened with; the new
     * preference applies after the next release-and-acquire cycle.
     */
    public static synchronized void setPreferredDevice(String name) {
        preferredDevice = (name == null || name.isEmpty()) ? null : name;
        HertzianDynamics.LOGGER
            .info("Preferred microphone set to {}", preferredDevice == null ? "(default)" : preferredDevice);
    }

    /** Returns the currently configured preferred device, or null for default. */
    public static synchronized String preferredDevice() {
        return preferredDevice;
    }

    /** Name of the device the active capture instance is bound to, or null. */
    public String deviceName() {
        return deviceName;
    }

    public static synchronized MicrophoneCapture acquire() {
        if (instance == null) {
            try {
                String name = preferredDevice;
                ALCdevice d = ALC11
                    .alcCaptureOpenDevice(name, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SAMPLES);
                if (d == null) {
                    if (name != null) {
                        HertzianDynamics.LOGGER
                            .warn("Preferred mic '{}' could not be opened; falling back to default", name);
                        d = ALC11.alcCaptureOpenDevice(null, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SAMPLES);
                    }
                }
                if (d == null) {
                    HertzianDynamics.LOGGER.warn("No microphone available; PTT disabled");
                    return null;
                }
                String resolved = ALC10.alcGetString(d, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
                instance = new MicrophoneCapture(d, resolved);
                HertzianDynamics.LOGGER.info("Microphone capture opened: {}", resolved);
            } catch (Throwable t) {
                HertzianDynamics.LOGGER.warn("Microphone open failed; PTT disabled", t);
                return null;
            }
        }
        instance.refCount++;
        if (!instance.capturing) {
            ALC11.alcCaptureStart(instance.device);
            instance.capturing = true;
        }
        return instance;
    }

    public synchronized void release() {
        refCount--;
        if (refCount <= 0 && capturing) {
            ALC11.alcCaptureStop(device);
            capturing = false;
            lastLevel = 0f;
        }
    }

    /**
     * Drain whatever the device has accumulated since the last call,
     * apply the input gain, return as float PCM in [-1, 1], and update
     * the smoothed level meter. Returns an empty array if no samples
     * are available; never blocks.
     */
    public synchronized float[] drain() {
        if (!capturing) return EMPTY;
        countScratch.clear();
        countScratch.limit(1);
        ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES, countScratch);
        int available = countScratch.get(0);
        if (available <= 0) {
            // Decay the meter toward zero on silence so it settles.
            lastLevel *= 0.8f;
            return EMPTY;
        }
        int take = Math.min(available, pcmScratch.capacity() / 2);
        pcmScratch.clear();
        pcmScratch.limit(take * 2);
        ALC11.alcCaptureSamples(device, pcmScratch, take);
        float g = gain;
        float[] out = new float[take];
        double sumSq = 0.0;
        for (int i = 0; i < take; i++) {
            int lo = pcmScratch.get(2 * i) & 0xFF;
            int hi = pcmScratch.get(2 * i + 1);
            short s = (short) ((hi << 8) | lo);
            float v = (s / 32768f) * g;
            if (v > 1f) v = 1f;
            else if (v < -1f) v = -1f;
            out[i] = v;
            sumSq += v * v;
        }
        float rms = (float) Math.sqrt(sumSq / take);
        // Light smoothing so the meter does not flicker frame to frame.
        lastLevel = lastLevel * 0.6f + rms * 0.4f;
        return out;
    }

    private static final float[] EMPTY = new float[0];

    /**
     * Close the active capture device. The preferred device setting
     * persists; the next acquire opens it again. Called at JVM exit and
     * after the player changes the preference so the new choice takes
     * effect without a client restart.
     */
    public static synchronized void shutdown() {
        if (instance != null && instance.capturing) {
            ALC11.alcCaptureStop(instance.device);
            instance.capturing = false;
        }
        if (instance != null) {
            ALC11.alcCaptureCloseDevice(instance.device);
            instance = null;
        }
    }
}
