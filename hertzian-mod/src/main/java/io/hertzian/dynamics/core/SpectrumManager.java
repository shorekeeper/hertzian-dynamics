package io.hertzian.dynamics.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.hertzian.dynamics.core.internal.Layouts;
import io.hertzian.dynamics.core.internal.Native;

/**
 * Spectrum manager. Owns live emissions and receivers and dispatches
 * mix calls against the propagation solver.
 *
 * <p>
 * The manager is single threaded by contract. Calls from
 * different Minecraft server ticks happen on the same worker
 * thread; the Java side enforces this through standard server-thread
 * affinity.
 */
public final class SpectrumManager implements AutoCloseable {

    private final RfCore core;
    private final MemorySegment handle;
    private boolean closed = false;

    private SpectrumManager(RfCore core, MemorySegment handle) {
        this.core = core;
        this.handle = handle;
    }

    public static SpectrumManager create(RfCore core) {
        Native.ensureLoaded(null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) Native.HD_MANAGER_CREATE.invokeExact(core.handle(), out);
            ErrorCode.throwIfError(rc, "hd_manager_create");
            return new SpectrumManager(core, out.get(ValueLayout.ADDRESS, 0L));
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "manager create failed", t);
        }
    }

    /**
     * Set the Earth curvature parameters the native solver applies to
     * every later mix. Drives the radio horizon: a smaller radius pulls
     * the horizon closer so links die at shorter range. Called once per
     * world from {@link io.hertzian.dynamics.world.WorldRfState} with the
     * values from the mod config.
     */
    public void setCurvature(boolean enabled, float earthRadiusM, float kFactor, float groundRefM) {
        checkOpen();
        try {
            int rc = (int) Native.HD_MANAGER_SET_CURVATURE
                .invokeExact(handle, enabled ? 1 : 0, earthRadiusM, kFactor, groundRefM);
            ErrorCode.throwIfError(rc, "hd_manager_set_curvature");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "set_curvature failed", t);
        }
    }

    /**
     * Register an audio emission. Returns the opaque emission id
     * the caller uses to push audio and to unregister.
     */
    public int registerAudioEmission(EmissionParameters params) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(Layouts.EMISSION_DESC);
            writeEmissionDesc(desc, params, 0);
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            int rc = (int) Native.HD_MANAGER_REGISTER_EMISSION.invokeExact(handle, desc, out);
            ErrorCode.throwIfError(rc, "hd_manager_register_emission");
            return out.get(ValueLayout.JAVA_INT, 0L);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "register_emission failed", t);
        }
    }

    /**
     * Register a noise jammer. {@code jamProfile} is 1 for
     * continuous noise, 2 for pulsed noise; {@code jamRateHz} is
     * the gating frequency for pulsed mode.
     */
    public int registerJammer(EmissionParameters params, int jamProfile, float jamRateHz, float sigma) {
        checkOpen();
        if (jamProfile != 1 && jamProfile != 2) {
            throw new IllegalArgumentException("jamProfile must be 1 or 2");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(Layouts.EMISSION_DESC);
            writeEmissionDesc(desc, params, jamProfile);
            // Overlay jam fields on top of pcm_capacity reservation.
            desc.set(
                ValueLayout.JAVA_FLOAT,
                Layouts.EMISSION_DESC
                    .byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("jam_rate_hz")),
                jamRateHz);
            desc.set(
                ValueLayout.JAVA_FLOAT,
                Layouts.EMISSION_DESC.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("jam_sigma")),
                sigma);
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            int rc = (int) Native.HD_MANAGER_REGISTER_EMISSION.invokeExact(handle, desc, out);
            ErrorCode.throwIfError(rc, "hd_manager_register_emission(jammer)");
            return out.get(ValueLayout.JAVA_INT, 0L);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "register_jammer failed", t);
        }
    }

    public void unregisterEmission(int emissionId) {
        checkOpen();
        try {
            int rc = (int) Native.HD_MANAGER_UNREGISTER_EMISSION.invokeExact(handle, emissionId);
            ErrorCode.throwIfError(rc, "hd_manager_unregister_emission");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "unregister_emission failed", t);
        }
    }

    /** Push float PCM samples into the named emission's audio ring. */
    public void pushAudio(int emissionId, float[] samples) {
        checkOpen();
        if (samples.length == 0) return;
        try (Arena arena = Arena.ofConfined()) {
            // FFM in Java 21 preview spells the bulk array
            // allocator as allocateArray(layout, source).
            // The Java 22 stable API renames this to
            // allocateFrom(layout, source); when the project
            // upgrades the JVM target this call site needs the
            // single rename and no other change.
            MemorySegment buf = arena.allocateArray(ValueLayout.JAVA_FLOAT, samples);
            int rc = (int) Native.HD_MANAGER_PUSH_AUDIO.invokeExact(handle, emissionId, buf, (long) samples.length);
            ErrorCode.throwIfError(rc, "hd_manager_push_audio");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "push_audio failed", t);
        }
    }

    /** Register a receiver and return its opaque id. */
    public int registerReceiver(ReceiverParameters params) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfg = arena.allocate(Layouts.RECEIVER_CONFIG);
            writeReceiverConfig(cfg, params);
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            int rc = (int) Native.HD_MANAGER_REGISTER_RECEIVER.invokeExact(handle, cfg, out);
            ErrorCode.throwIfError(rc, "hd_manager_register_receiver");
            return out.get(ValueLayout.JAVA_INT, 0L);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "register_receiver failed", t);
        }
    }

    public void unregisterReceiver(int receiverId) {
        checkOpen();
        try {
            int rc = (int) Native.HD_MANAGER_UNREGISTER_RECEIVER.invokeExact(handle, receiverId);
            ErrorCode.throwIfError(rc, "hd_manager_unregister_receiver");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "unregister_receiver failed", t);
        }
    }

    /**
     * Mix one chunk for one receiver. The returned chunk owns its
     * sample array; callers may keep it for as long as needed.
     */
    public SpectrumChunk mix(VoxelGrid grid, MaterialTable materials, Ionosphere iono, int receiverId, int sampleCount,
        long serverTick, float localHour) {
        checkOpen();
        if (sampleCount <= 0) throw new IllegalArgumentException("sampleCount must be positive");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = arena.allocate(Layouts.CHUNK_HEADER);
            MemorySegment samples = arena.allocate(Layouts.IQ.byteSize() * sampleCount);
            int rc = (int) Native.HD_MANAGER_MIX_CHUNK.invokeExact(
                handle,
                grid.handle(),
                materials.handle(),
                iono.handle(),
                receiverId,
                sampleCount,
                serverTick,
                localHour,
                header,
                samples);
            ErrorCode.throwIfError(rc, "hd_manager_mix_chunk");
            return decodeChunk(header, samples, sampleCount);
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "mix failed", t);
        }
    }

    private static void writeEmissionDesc(MemorySegment desc, EmissionParameters p, int jamProfile) {
        java.lang.foreign.MemoryLayout.PathElement ge = java.lang.foreign.MemoryLayout.PathElement
            .groupElement("modulation");
        long offMod = Layouts.EMISSION_DESC.byteOffset(ge);
        desc.set(ValueLayout.JAVA_INT, offMod, p.modulation.code());
        desc.set(ValueLayout.JAVA_FLOAT, off("pos_x"), p.posX);
        desc.set(ValueLayout.JAVA_FLOAT, off("pos_y"), p.posY);
        desc.set(ValueLayout.JAVA_FLOAT, off("pos_z"), p.posZ);
        desc.set(ValueLayout.JAVA_FLOAT, off("vel_x"), p.velX);
        desc.set(ValueLayout.JAVA_FLOAT, off("vel_y"), p.velY);
        desc.set(ValueLayout.JAVA_FLOAT, off("vel_z"), p.velZ);
        desc.set(ValueLayout.JAVA_FLOAT, off("tx_power_w"), p.txPowerW);
        desc.set(ValueLayout.JAVA_FLOAT, off("antenna_gain"), p.antennaGain);
        desc.set(ValueLayout.JAVA_DOUBLE, off("carrier_hz"), p.carrierHz);
        desc.set(ValueLayout.JAVA_FLOAT, off("bandwidth_hz"), p.bandwidthHz);
        desc.set(ValueLayout.JAVA_INT, off("pcm_capacity"), p.pcmCapacity);
        desc.set(ValueLayout.JAVA_INT, off("jam_profile"), jamProfile);
        desc.set(ValueLayout.JAVA_FLOAT, off("jam_rate_hz"), 0.0f);
        desc.set(ValueLayout.JAVA_FLOAT, off("jam_sigma"), 0.0f);
    }

    private static void writeReceiverConfig(MemorySegment cfg, ReceiverParameters p) {
        cfg.set(ValueLayout.JAVA_DOUBLE, offR("tuned_hz"), p.tunedHz);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("bandwidth_hz"), p.bandwidthHz);
        cfg.set(ValueLayout.JAVA_INT, offR("modulation"), p.modulation.code());
        cfg.set(ValueLayout.JAVA_FLOAT, offR("antenna_gain"), p.antennaGain);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("pos_x"), p.posX);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("pos_y"), p.posY);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("pos_z"), p.posZ);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("vel_x"), p.velX);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("vel_y"), p.velY);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("vel_z"), p.velZ);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("agc_target"), 0.0f);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("agc_attack_seconds"), 0.0f);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("agc_release_seconds"), 0.0f);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("agc_max_gain"), 0.0f);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("agc_min_gain"), 0.0f);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("agc_min_gain"), 0.0f);
        cfg.set(ValueLayout.JAVA_FLOAT, offR("noise_figure_db"), p.noiseFigureDb);
        cfg.set(ValueLayout.JAVA_INT, offR("noise_environment"), p.noiseEnvironment.code());
    }

    private static long off(String name) {
        return Layouts.EMISSION_DESC.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement(name));
    }

    private static long offR(String name) {
        return Layouts.RECEIVER_CONFIG.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement(name));
    }

    private static SpectrumChunk decodeChunk(MemorySegment header, MemorySegment samples, int sampleCount) {
        double centerHz = header.get(
            ValueLayout.JAVA_DOUBLE,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("center_hz")));
        float sampleRateHz = header.get(
            ValueLayout.JAVA_FLOAT,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sample_rate_hz")));
        float bandwidthHz = header.get(
            ValueLayout.JAVA_FLOAT,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("bandwidth_hz")));
        int sc = header.get(
            ValueLayout.JAVA_INT,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sample_count")));
        int sequence = header.get(
            ValueLayout.JAVA_INT,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("sequence")));
        long tick = header.get(
            ValueLayout.JAVA_LONG,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("server_tick")));
        float noiseFloor = header.get(
            ValueLayout.JAVA_FLOAT,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("noise_floor_w")));
        float signalPower = header.get(
            ValueLayout.JAVA_FLOAT,
            Layouts.CHUNK_HEADER.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("signal_power_w")));
        float[] arr = samples.reinterpret(8L * sc)
            .toArray(ValueLayout.JAVA_FLOAT);
        // Bound the returned sample count to whatever the native side
        // wrote, even if the caller asked for more. Defensive: stops
        // bogus floats from leaking through if a layout drift sneaks
        // past the static checks.
        int n = Math.min(sc, sampleCount);
        if (arr.length != n * 2) {
            // Trim or pad.
            float[] resized = new float[n * 2];
            System.arraycopy(arr, 0, resized, 0, Math.min(arr.length, resized.length));
            arr = resized;
        }
        return new SpectrumChunk(centerHz, sampleRateHz, bandwidthHz, n, sequence, tick, noiseFloor, signalPower, arr);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            int rc = (int) Native.HD_MANAGER_DESTROY.invokeExact(handle);
            ErrorCode.throwIfError(rc, "hd_manager_destroy");
        } catch (HertzianException e) {
            throw e;
        } catch (Throwable t) {
            throw new HertzianException(ErrorCode.UNKNOWN, "manager destroy failed", t);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("SpectrumManager has been closed");
    }

    /**
     * Builder-style parameter struct for emissions. Mirrors the
     * fields of {@code hd_emission_desc_t} that the Java caller
     * is expected to set. Use {@link #of} for the common case.
     */
    public record EmissionParameters(Modulation modulation, float posX, float posY, float posZ, float velX, float velY,
        float velZ, float txPowerW, float antennaGain, double carrierHz, float bandwidthHz, int pcmCapacity) {

        public static EmissionParameters of(Modulation m, double carrierHz, float bandwidthHz, float posX, float posY,
            float posZ, float txPowerW) {
            return new EmissionParameters(m, posX, posY, posZ, 0, 0, 0, txPowerW, 1.0f, carrierHz, bandwidthHz, 16_384);
        }
    }

    public record ReceiverParameters(double tunedHz, float bandwidthHz, Modulation modulation, float antennaGain,
        float posX, float posY, float posZ, float velX, float velY, float velZ, float noiseFigureDb,
        NoiseEnvironment noiseEnvironment) {

        /**
         * Default receiver noise figure in dB. Twelve dB sits in the
         * middle of the 10 to 20 dB band typical of consumer handheld
         * and base receivers, so a radio that does not pin its own
         * value behaves like ordinary hardware. The rf-core mixer turns
         * this into the receiver half of the operating noise factor.
         */
        public static final float DEFAULT_NOISE_FIGURE_DB = 12.0f;

        /**
         * Backward compatible ten argument constructor. Callers that
         * predate the noise model keep compiling and receive the
         * default noise figure and a residential environment. The
         * canonical twelve argument constructor is the path for any
         * future caller that wants to pin a cheaper or better receiver,
         * or place it in a quieter or noisier site.
         */
        public ReceiverParameters(double tunedHz, float bandwidthHz, Modulation modulation, float antennaGain,
            float posX, float posY, float posZ, float velX, float velY, float velZ) {
            this(
                tunedHz,
                bandwidthHz,
                modulation,
                antennaGain,
                posX,
                posY,
                posZ,
                velX,
                velY,
                velZ,
                DEFAULT_NOISE_FIGURE_DB,
                NoiseEnvironment.RESIDENTIAL);
        }

        public static ReceiverParameters of(double tunedHz, float bandwidthHz, Modulation m, float posX, float posY,
            float posZ) {
            return new ReceiverParameters(tunedHz, bandwidthHz, m, 1.0f, posX, posY, posZ, 0, 0, 0);
        }
    }
}
