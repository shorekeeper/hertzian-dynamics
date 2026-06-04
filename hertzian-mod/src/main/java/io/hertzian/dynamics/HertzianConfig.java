package io.hertzian.dynamics;

import java.io.File;
import java.util.Locale;

import net.minecraftforge.common.config.Configuration;

/**
 * Mod configuration, loaded in preInit from the Forge config file.
 *
 * <p>
 * The settings split into two kinds. Compute settings choose how a result
 * is computed and are realism neutral: the CPU and GPU backends produce
 * the same numbers, so an operator can move work between them or tune the
 * Auto threshold without changing the simulation. Propagation settings
 * choose the physical conditions of the world. They are bounded to
 * realistic presets (Earth curvature, ionospheric solar activity) so they
 * act as world flavor rather than as cheats; nothing here lets a player
 * trivialise the radio link, remove the noise floor, or grant unlimited
 * range.
 *
 * <p>
 * Compute category:
 * <ul>
 * <li>{@code gpuEnabled} builds the Vulkan compute backend. On a host
 * without a working Vulkan device the engine falls back to CPU
 * regardless of this setting.</li>
 * <li>{@code zoomDftBackend} forces or auto-selects the backend for the
 * spectrum analyzer DFT, the one workload currently accelerated.</li>
 * <li>{@code zoomDftAutoThreshold} is the Auto-mode work threshold, the
 * sample-count times bin-count below which the CPU is used.</li>
 * <li>{@code logComputeBackend} periodically logs the dispatch counters
 * so an operator can confirm the GPU path is actually running.</li>
 * </ul>
 *
 * <p>
 * Propagation category:
 * <ul>
 * <li>{@code modelCurvature}, {@code earthRadiusM}, {@code earthKFactor},
 * {@code groundReferenceY} set the Earth-curvature radio horizon. The
 * flat voxel world has no natural horizon; the curvature model
 * restores one, and a smaller radius pulls it in for a compact world
 * without otherwise changing the physics.</li>
 * <li>{@code solarActivity} selects the ionospheric condition preset
 * (Low, Medium, High), which sets the diurnal foF2 table and so the
 * HF skywave band conditions for the world.</li>
 * </ul>
 */
public final class HertzianConfig {

    private HertzianConfig() {}

    /** Apply the Earth curvature radio horizon to ground waves. */
    public static boolean modelCurvature = true;

    /** Effective Earth radius in metres for the horizon. */
    public static float earthRadiusM = 2_000_000f;

    /** Atmospheric refraction k factor; 4/3 is standard. */
    public static float earthKFactor = 1.333_333f;

    /** World Y treated as ground level for antenna height. */
    public static float groundRefM = 63f;

    /** Ionospheric solar activity preset: 0 Low, 1 Medium, 2 High. */
    public static int solarActivity = 1;

    /** Use the Vulkan GPU compute backend when available. */
    public static boolean gpuEnabled = true;

    /** Zoom DFT backend mode: matches RfCore.COMPUTE_MODE_* (0 Auto, 1 CPU, 2 GPU). */
    public static int zoomDftMode = 0;

    /** Propagation raycast backend mode: 0 Auto, 1 CPU, 2 GPU. */
    public static int propagationMode = 0;

    /** Auto-mode work threshold for the raycast, in ray count per batch. */
    public static int propagationAutoThreshold = 256;

    /** Auto-mode work threshold for the zoom DFT, in sample-count times bins. */
    public static int zoomDftAutoThreshold = 16_384;

    /** Periodically log the compute dispatch counters for diagnostics. */
    public static boolean logComputeBackend = false;

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();

            modelCurvature = cfg.getBoolean(
                "modelCurvature",
                "propagation",
                true,
                "Apply the Earth-curvature radio horizon to ground waves. "
                    + "Off gives an infinite flat-world line of sight.");
            earthRadiusM = (float) cfg
                .get(
                    "propagation",
                    "earthRadiusM",
                    2_000_000.0,
                    "Effective Earth radius in metres. Real is 6371000; smaller pulls the "
                        + "radio horizon in so links die at shorter range. At the default a "
                        + "ground-level pair reaches roughly 6-7 km and a raised antenna far more.",
                    1000.0,
                    1.0e8)
                .getDouble();
            earthKFactor = (float) cfg
                .get(
                    "propagation",
                    "earthKFactor",
                    1.333_333,
                    "Atmospheric refraction k-factor; 1.333 is the standard 4/3 Earth.",
                    0.5,
                    5.0)
                .getDouble();
            groundRefM = (float) cfg
                .get(
                    "propagation",
                    "groundReferenceY",
                    63.0,
                    "World Y treated as ground level. Antenna height above this drives the horizon.",
                    0.0,
                    255.0)
                .getDouble();
            String solar = cfg.getString(
                "solarActivity",
                "propagation",
                "MEDIUM",
                "Ionospheric solar activity preset. Sets the HF skywave band conditions: "
                    + "LOW is solar minimum (lower MUF, fewer open bands), HIGH is solar maximum "
                    + "(higher MUF, more open bands). A realistic world-flavor choice, not a cheat.",
                new String[] { "LOW", "MEDIUM", "HIGH" });
            solarActivity = parseSolar(solar);

            gpuEnabled = cfg.getBoolean(
                "gpuEnabled",
                "compute",
                true,
                "Use the Vulkan GPU compute backend for heavy DSP when available. "
                    + "Off forces the CPU path. On a host without a working Vulkan device "
                    + "the engine falls back to CPU automatically regardless of this setting.");
            String mode = cfg.getString(
                "zoomDftBackend",
                "compute",
                "AUTO",
                "Backend for the spectrum analyzer DFT, the heaviest analyzer step. "
                    + "AUTO selects by work size, CPU and GPU force one backend. The result is "
                    + "identical on both backends; this only trades CPU time for GPU time.",
                new String[] { "AUTO", "CPU", "GPU" });
            zoomDftMode = parseMode(mode);
            String pmode = cfg.getString(
                "propagationBackend",
                "compute",
                "AUTO",
                "Backend for the batched voxel absorption raycast. AUTO selects by batch size, "
                    + "CPU and GPU force one backend. The result is backend independent within the "
                    + "self-test tolerance; this only trades CPU time for GPU time. The live audio "
                    + "propagation does not use this path yet; it serves the batched raycast API.",
                new String[] { "AUTO", "CPU", "GPU" });
            propagationMode = parseMode(pmode);
            propagationAutoThreshold = cfg
                .get(
                    "compute",
                    "propagationAutoThreshold",
                    256,
                    "AUTO-mode ray-count threshold for the raycast. Batches smaller than this run "
                        + "on the CPU because the GPU submission overhead would not pay off.",
                    1,
                    100_000)
                .getInt();
            zoomDftAutoThreshold = cfg
                .get(
                    "compute",
                    "zoomDftAutoThreshold",
                    16_384,
                    "AUTO-mode work threshold for the zoom DFT (sample count times bin count). "
                        + "Scans below this run on the CPU because the GPU submission overhead "
                        + "would not pay off. Lower it to push more work to the GPU.",
                    0,
                    100_000_000)
                .getInt();
            logComputeBackend = cfg.getBoolean(
                "logComputeBackend",
                "compute",
                false,
                "Periodically log which backend the analyzer DFT used and the dispatch counts, "
                    + "so an operator can confirm the GPU path is active.");
        } finally {
            if (cfg.hasChanged()) cfg.save();
        }
    }

    private static int parseSolar(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
            case "LOW":
                return 0;
            case "HIGH":
                return 2;
            case "MEDIUM":
            default:
                return 1;
        }
    }

    private static int parseMode(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
            case "CPU":
                return 1;
            case "GPU":
                return 2;
            case "AUTO":
            default:
                return 0;
        }
    }
}
