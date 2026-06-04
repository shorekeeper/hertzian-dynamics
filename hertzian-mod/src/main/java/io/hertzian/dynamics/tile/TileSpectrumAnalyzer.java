package io.hertzian.dynamics.tile;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import cpw.mods.fml.common.network.NetworkRegistry;
import io.hertzian.dynamics.HertzianConfig;
import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.ComputeStats;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.RfCore;
import io.hertzian.dynamics.core.SpectrumChunk;
import io.hertzian.dynamics.core.SpectrumManager;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketSpectrumData;
import io.hertzian.dynamics.world.WorldRfState;

/**
 * Spectrum analyzer tile entity.
 *
 * <p>
 * Strategy: maintain a hidden rf-core receiver running at the
 * full {@link #MAX_SPAN_HZ} bandwidth, sample it at an adaptive
 * integration length, and compute a sliding window of
 * {@link #BINS} DFT bins covering only the user-selected span.
 *
 * <p>
 * Why the wide hidden bandwidth
 * --------------------------------
 * The visible span is purely an analysis zoom. Pinning the receiver
 * bandwidth to the visible span would also narrow what reaches the
 * analyzer at the rf-core mixer stage, so wideband signals (SSB
 * sidebands offset from carrier, FM modulation pairs, jammer
 * footprints) would disappear entirely. Keeping the receiver wide and
 * projecting a smaller DFT window decouples the displayed span from
 * the detail applied to the piece on display.
 *
 * <p>
 * Frequency coverage
 * -----------------------------------
 * {@link #MAX_SPAN_HZ} is 40 kHz. The engine runs every stage at a
 * 48 kHz sample rate, so the complex baseband the mixer produces
 * represents at most the full 48 kHz, roughly plus or minus 24 kHz
 * around the tuned centre. A 40 kHz span (plus or minus 20 kHz) sits
 * inside that with a guard band against the Nyquist edge, and is the
 * widest useful window the fixed sample rate allows. A wider sweep
 * across megahertz requires raising the engine sample rate, which is
 * a separate and costly change.
 *
 * <p>
 * Adaptive integration
 * -----------------------
 * Frequency resolution is bounded by {@code fs / N}, so longer
 * integrations resolve narrower features. Temporal resolution is
 * bounded by the integration window length, so shorter windows
 * track fast events (FM sweep traces, voice formants, jammer
 * pulse edges) as visible motion in the waterfall rather than as
 * smeared bright lines.
 *
 * <p>
 * The analyzer picks N based on span:
 * <ul>
 * <li>Narrow span (1 kHz) -> {@link #MIN_SCAN_SAMPLES} samples
 * so a 2-second audio sweep visibly traces a diagonal
 * across the waterfall.</li>
 * <li>Wide span ({@link #MAX_SPAN_HZ}) -> {@link #MAX_SCAN_SAMPLES}
 * samples so weak signals integrate up out of the noise
 * floor.</li>
 * </ul>
 *
 * <p>
 * Adaptive smoothing
 * --------------------
 * Mirrors the same trade-off: heavy smoothing at wide spans
 * gives stable visuals across many narrow features, light
 * smoothing at narrow spans keeps motion alive.
 *
 * <p>
 * Observer gating
 * -----------------
 * The scan (a native mixer call, a software demodulate for the
 * station decode, and the DFT) is the same per-tick cost as a live
 * receiver. {@link #tickScan} does no work unless a player sits
 * within {@link #OBSERVER_RADIUS} blocks, which is the same radius
 * the data packet is sent to: a frame no client can receive is not
 * worth computing, and an unwatched analyzer adds nothing to the
 * server tick.
 *
 * <p>
 * AGC bypass
 * ------------
 * The underlying rf-core receiver always runs its AGC; the engine
 * has no off-switch for it. With AGC active, the power level of
 * every chunk is normalised toward a fixed target, so the noise
 * floor pumps up and down whenever a signal appears or disappears.
 * SDR panadapters bypass AGC so the noise floor reads as a stable
 * line and signals stick up above it, the visual idiom players
 * expect.
 *
 * <p>
 * Because the rf-core side cannot be disabled cheaply, the bypass is
 * a post-processing step: after the DFT runs, the analyzer computes
 * the chunk's mean linear power, converts to dB, and subtracts that
 * mean from every bin so the per-chunk noise floor sits at a fixed
 * reference. The result is a steady speckled noise floor with
 * isolated peaks rising above it.
 *
 * <p>
 * Analyzer modulation
 * ---------------------
 * The hidden receiver's modulation field has no effect on the DFT
 * output (the analyzer reads the raw IQ output of the mixer, before
 * any modulation-specific processing). It is exposed in the GUI
 * because the station ID decode demodulates with the selected
 * modulation, so cycling the mode to match a station is how its name
 * is read. AM is the default.
 */
public final class TileSpectrumAnalyzer extends TileEntity {

    /** Number of DFT bins per scan. Sized for waterfall density. */
    public static final int BINS = 192;

    /**
     * Refresh every N server ticks. Raised from 2 to 3 (about 6.7 Hz)
     * with the lag fix: the DFT is the heaviest single step in the
     * scan, and a third fewer of them shaves the analyzer's serverside
     * cost without making the waterfall scroll visibly slower.
     */
    private static final int SCAN_INTERVAL_TICKS = 3;

    private static final float ENGINE_FS = 48_000f;

    /**
     * Maximum visible span. The hidden receiver always runs at
     * this bandwidth so any zoom level still has data to project.
     * See the class docs for why 40 kHz is the ceiling the fixed
     * 48 kHz engine sample rate allows.
     */
    public static final double MAX_SPAN_HZ = 40_000.0;

    /**
     * Radius, in blocks, within which a player must sit for the
     * analyzer to run a scan. Matches the data packet broadcast
     * radius below; computing a frame for a range no client can
     * receive would be wasted work.
     */
    private static final double OBSERVER_RADIUS = 96.0;

    /**
     * Wall-clock time of the last compute backend diagnostic log, shared
     * across all analyzers so the log is throttled globally rather than
     * per tile when {@link HertzianConfig#logComputeBackend} is on.
     */
    private static volatile long lastComputeLogMs = 0L;

    /**
     * Wide-span integration: long enough for tight frequency
     * resolution and integration gain on weak signals.
     */
    private static final int MAX_SCAN_SAMPLES = 2048;

    /**
     * Narrow-span integration: short enough that a 2-second
     * audio sweep produces visible motion in the waterfall
     * rather than a smeared bright line. 512 samples = 10.7 ms.
     */
    private static final int MIN_SCAN_SAMPLES = 512;

    /**
     * Hann window cache keyed by length. Built lazily so adaptive
     * integration sizes do not pay window construction cost on
     * every scan.
     */
    // private static final Map<Integer, float[]> HANN_CACHE = new HashMap<>();

    /**
     * Reference dB level the noise floor is biased to when AGC
     * bypass is engaged. Chosen so a signal peak at the analyzer's
     * full-scale output sits around -10 to 0 dB while keeping a
     * comfortable margin above the visual floor for natural noise
     * jitter to be visible.
     */
    private static final float AGC_BYPASS_FLOOR_DB = -65.0f;

    /**
     * Smoothing buffer kept across scans. dB-domain exponential
     * averaging suppresses AGC pump and packet-to-packet jitter
     * without losing the ability to resolve fast features.
     */
    private float[] smoothedSpectrum = null;

    private double centerHz = 145_000_000.0;
    private double spanHz = 15_000.0;
    private float[] lastSpectrum = new float[BINS];
    private int tickCounter = 0;

    /** Station name decoded from the centred signal, shipped to the client for the arrow. */
    private String decodedStation = "";

    public String decodedStation() {
        return decodedStation;
    }

    /**
     * Centre and span the most recent {@link #lastSpectrum} was computed
     * at, carried alongside the magnitudes by the data packet. The client
     * GUI keeps using its own live tuning for the axis and cursor so they
     * follow input instantly; these fields are the frame's own metadata,
     * available for staleness checks, and exist so the data packet no
     * longer has to overwrite the live tuning (which used to fight the
     * client's optimistic zoom and pan and made the cursor jump).
     */
    private double spectrumCenterHz = 145_000_000.0;
    private double spectrumSpanHz = 15_000.0;

    public double spectrumCenterHz() {
        return spectrumCenterHz;
    }

    public double spectrumSpanHz() {
        return spectrumSpanHz;
    }

    /**
     * When true, post-process the spectrum so the noise floor
     * sits at a fixed reference; defaults on because it produces
     * the more useful SDR-style display out of the box.
     */
    private boolean agcBypass = true;

    /**
     * Modulation tag passed to the underlying receiver. Has no
     * direct effect on what the analyzer draws, but is preserved
     * because the underlying rf-core receiver requires the field
     * and because future code versions may key behaviour off it.
     */
    private Modulation analyzerModulation = Modulation.AM;

    public double centerHz() {
        return centerHz;
    }

    public double spanHz() {
        return spanHz;
    }

    public float[] lastSpectrum() {
        return lastSpectrum;
    }

    public boolean agcBypass() {
        return agcBypass;
    }

    public Modulation analyzerModulation() {
        return analyzerModulation;
    }

    /**
     * Apply a freshly received spectrum frame from the network. Replaces
     * the magnitudes, the frame metadata and the decoded station in one
     * call. This is the client-side entry point for
     * {@link PacketSpectrumData}; it deliberately does NOT touch
     * {@link #centerHz} or {@link #spanHz}, leaving the live tuning under
     * the client's control so the GUI cursor and axis follow user input
     * rather than the lagging server frame.
     */
    public void applySpectrumFrame(float[] spectrum, double frameCenterHz, double frameSpanHz, String station) {
        if (spectrum != null) this.lastSpectrum = spectrum;
        this.spectrumCenterHz = frameCenterHz;
        this.spectrumSpanHz = frameSpanHz;
        this.decodedStation = station == null ? "" : station;
    }

    public void setCenterHz(double hz) {
        this.centerHz = hz;
        // Drop the smoothing buffer so a tuning change shows
        // immediately rather than averaging the old band with
        // the new one.
        this.smoothedSpectrum = null;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        }
    }

    public void setSpanHz(double hz) {
        this.spanHz = Math.max(200.0, Math.min(MAX_SPAN_HZ, hz));
        this.smoothedSpectrum = null;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        }
    }

    /**
     * Toggle the AGC bypass post-processing. The smoothing buffer
     * is dropped so the next scan reflects the new bias mode
     * immediately rather than averaging the two regimes together.
     * A retune is not necessary because the bypass lives entirely
     * on the analyzer's post-processing pass.
     */
    public void setAgcBypass(boolean v) {
        this.agcBypass = v;
        this.smoothedSpectrum = null;
        markDirty();
    }

    /**
     * Change the analyzer's hidden-receiver modulation. Forces a
     * retune so the rf-core side picks up the new field on the
     * next scan; without that the slot map would reuse the prior
     * receiver state and the modulation change would only take
     * effect after a center or span change.
     */
    public void setAnalyzerModulation(Modulation m) {
        if (m == null) return;
        this.analyzerModulation = m;
        this.smoothedSpectrum = null;
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            WorldRfState s = WorldRfState.forWorld(worldObj);
            if (s != null) s.forceRetuneReceiverAt(xCoord, yCoord, zCoord);
        }
    }

    /**
     * Hann window of length n, cached for reuse across scans.
     * Synchronized because multiple worlds tick on the same
     * server thread but the convention plugin runs Minecraft
     * 1.7.10 single-threaded for game logic anyway; the
     * synchronisation is paranoid insurance against a future
     * refactor that off-loads scans to a worker.
     */
    /*
     * private static synchronized float[] hannWindow(int n) {
     * float[] w = HANN_CACHE.get(n);
     * if (w != null) return w;
     * w = new float[n];
     * for (int i = 0; i < n; i++) {
     * w[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
     * }
     * HANN_CACHE.put(n, w);
     * return w;
     * }
     */

    /**
     * Integration length picked from span. Linear interpolation
     * between the narrow and wide endpoints; the function is
     * monotonic in span so a slow zoom-in continuously trades
     * frequency resolution for temporal resolution. The denominator
     * tracks {@link #MAX_SPAN_HZ} so the curve still spans the whole
     * range after the span ceiling was widened.
     */
    private static int samplesForSpan(double spanHz) {
        double t = (spanHz - 1_000.0) / (MAX_SPAN_HZ - 1_000.0);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return (int) (MIN_SCAN_SAMPLES + (MAX_SCAN_SAMPLES - MIN_SCAN_SAMPLES) * t);
    }

    /**
     * Smoothing alpha picked from span. Higher alpha keeps more
     * of the previous frame in the running average. Narrow spans
     * use a low alpha so sweep motion is visible; wide spans use
     * a high alpha so the spectrum is stable when there are many
     * narrow features. The denominator tracks {@link #MAX_SPAN_HZ}.
     */
    private static float smoothingForSpan(double spanHz) {
        double t = (spanHz - 1_000.0) / (MAX_SPAN_HZ - 1_000.0);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return (float) (0.12 + (0.55 - 0.12) * t);
    }

    /** True when at least one player sits within {@link #OBSERVER_RADIUS}. */
    private boolean hasObserverInRange() {
        double r2 = OBSERVER_RADIUS * OBSERVER_RADIUS;
        double cx = xCoord + 0.5, cy = yCoord + 0.5, cz = zCoord + 0.5;
        for (Object o : worldObj.playerEntities) {
            if (!(o instanceof EntityPlayer)) continue;
            EntityPlayer p = (EntityPlayer) o;
            double dx = p.posX - cx, dy = p.posY - cy, dz = p.posZ - cz;
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    public void tickScan(WorldRfState state, long worldTick, float localHour) {
        if (worldObj == null || worldObj.isRemote) return;

        // Observer gating: an analyzer nobody is near costs nothing.
        // The scan is as expensive as a live receiver, so running it for
        // an empty room loaded the server tick and stuttered the audio of
        // every other radio. No player in range also means no client to
        // send the frame to, so skipping is free of any visible effect.
        if (!hasObserverInRange()) return;

        SpectrumManager mgr = state.manager();
        long key = WorldRfState.packPos(xCoord, yCoord, zCoord);

        // Receiver pinned to MAX_SPAN_HZ so the full passband is always
        // available regardless of the user's zoom span.
        SpectrumManager.ReceiverParameters params = new SpectrumManager.ReceiverParameters(
            centerHz,
            (float) MAX_SPAN_HZ,
            analyzerModulation,
            1.0f,
            xCoord + 0.5f,
            yCoord + 0.5f,
            zCoord + 0.5f,
            0f,
            0f,
            0f);

        // Mix one full real-time tick of baseband every tick. CHUNK_SAMPLES
        // at 48 kHz is exactly 50 ms, one server tick, so feeding this to
        // the station decoder gives it a continuous, gap-free audio stream.
        // The DFT scan below reuses the leading samples of this same chunk,
        // so one mix serves both jobs.
        int mixSamples = io.hertzian.dynamics.tick.RadioTickHandler.CHUNK_SAMPLES;
        SpectrumChunk chunk;
        try {
            int id = state.getOrRegisterReceiver(key, params);
            chunk = mgr.mix(state.grid(), state.materials(), state.ionosphere(), id, mixSamples, worldTick, localHour);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("spectrum scan mix failed", t);
            return;
        }
        if (chunk == null) return;

        // Continuous station ID decode. The demodulator is position-keyed,
        // so its embedded FSK decoder keeps its state across ticks;
        // ChunkDemodulator.demodulate feeds the raw audio to that decoder
        // before its own audio band filter runs, so the 16/18 kHz
        // subcarrier survives. The analyzer modulation must match the
        // station's for the decode to succeed, which is why the GUI mode
        // cycle exists: tune the mode to the station to read its name.
        try {
            io.hertzian.dynamics.core.ChunkDemodulator demod = state
                .getOrCreateDemodulator(key, analyzerModulation, chunk.sampleRateHz());
            float[] audioScratch = new float[chunk.sampleCount()];
            demod.demodulate(chunk, audioScratch);
            decodedStation = demod.lastStationName(4_000L);
        } catch (Throwable t) {
            decodedStation = "";
        }

        // Gated DFT visualization. Counter advances every tick; the heavy
        // DFT and the packet only fire every SCAN_INTERVAL_TICKS.
        tickCounter++;
        if (tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        float smoothAlpha = smoothingForSpan(spanHz);
        int integrationSamples = samplesForSpan(spanHz);

        float[] iq = chunk.samples();
        int n = chunk.sampleCount();
        if (n > integrationSamples) n = integrationSamples;

        // Heavy zoom DFT runs in rf-core, on the GPU when the core was
        // built with the GPU backend and the request fits the pipeline
        // buffers, on the CPU otherwise. The native side windows the chunk,
        // projects BINS bins across the current span centered on baseband
        // DC, applies the PSD normalisation, converts to dB, and performs
        // the wide-span center-bin interpolation. The AGC-bias, visual
        // floor and smoothing below stay here because the smoothing buffer
        // is per-tile state and the bias is a cheap O(BINS) pass.
        float[] raw = new float[BINS];
        state.core()
            .analyzerDft(iq, n, (float) spanHz, ENGINE_FS, BINS, raw);

        if (HertzianConfig.logComputeBackend) {
            long now = System.currentTimeMillis();
            if (now - lastComputeLogMs > 10_000L) {
                lastComputeLogMs = now;
                ComputeStats cs = state.core()
                    .computeStats(RfCore.WORKLOAD_ZOOM_DFT);
                HertzianDynamics.LOGGER.info(
                    "zoom DFT compute: gpu={} cpu={} fallback={} lastGpu={} gpuAvail={}",
                    cs.gpuCalls(),
                    cs.cpuCalls(),
                    cs.fallbackCalls(),
                    cs.lastBackendGpu(),
                    cs.gpuAvailable());
            }
        }

        if (agcBypass) {
            double meanLin = 0.0;
            for (int b = 0; b < BINS; b++) meanLin += Math.pow(10.0, raw[b] / 10.0);
            meanLin /= BINS;
            float meanDb = (float) (10.0 * Math.log10(Math.max(1e-15, meanLin)));
            float bias = AGC_BYPASS_FLOOR_DB - meanDb;
            for (int b = 0; b < BINS; b++) raw[b] += bias;
        }

        final float VISUAL_FLOOR_DB = agcBypass ? -100f : -80f;
        for (int b = 0; b < BINS; b++) {
            if (raw[b] < VISUAL_FLOOR_DB) raw[b] = VISUAL_FLOOR_DB;
        }

        if (smoothedSpectrum == null || smoothedSpectrum.length != BINS) {
            smoothedSpectrum = raw.clone();
        } else {
            float oneMinusA = 1.0f - smoothAlpha;
            for (int b = 0; b < BINS; b++) {
                smoothedSpectrum[b] = smoothAlpha * smoothedSpectrum[b] + oneMinusA * raw[b];
            }
        }
        float[] result = smoothedSpectrum.clone();
        lastSpectrum = result;
        // Keep the frame metadata locally too so a single-player host's
        // own tile copy is consistent with what the packet carries.
        spectrumCenterHz = centerHz;
        spectrumSpanHz = spanHz;

        NetworkRegistry.TargetPoint target = new NetworkRegistry.TargetPoint(
            worldObj.provider.dimensionId,
            xCoord + 0.5,
            yCoord + 0.5,
            zCoord + 0.5,
            OBSERVER_RADIUS);
        NetworkHandler.CHANNEL.sendToAllAround(
            new PacketSpectrumData(
                worldObj.provider.dimensionId,
                xCoord,
                yCoord,
                zCoord,
                centerHz,
                spanHz,
                result,
                decodedStation),
            target);
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setDouble("centerHz", centerHz);
        tag.setDouble("spanHz", spanHz);
        tag.setBoolean("agcBypass", agcBypass);
        tag.setInteger("analyzerMod", analyzerModulation.code());
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("centerHz")) centerHz = tag.getDouble("centerHz");
        if (tag.hasKey("spanHz")) spanHz = Math.max(200.0, Math.min(MAX_SPAN_HZ, tag.getDouble("spanHz")));
        if (tag.hasKey("agcBypass")) agcBypass = tag.getBoolean("agcBypass");
        if (tag.hasKey("analyzerMod")) {
            try {
                analyzerModulation = Modulation.fromCode(tag.getInteger("analyzerMod"));
            } catch (IllegalArgumentException e) {
                analyzerModulation = Modulation.AM;
            }
        }
    }
}
