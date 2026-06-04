package io.hertzian.dynamics.item;

import io.hertzian.dynamics.core.Modulation;

/**
 * Handheld radio model registry. Each model is a class of real radio
 * hardware expressed as a range of physical capabilities: the band it
 * can tune, whether it tunes freely or only on a fixed channel grid, the
 * selectable transmit power steps, the modulations it supports, and the
 * receiver noise figure passed to the spectrum engine. The model is
 * stored as the item metadata, so one item registration covers every
 * model and each gets its own icon and name.
 *
 * The model bounds are enforced by {@link ItemHandheldRadio}: tuning is
 * clamped to the band and snapped to the grid for fixed-grid sets,
 * modulation falls back to the first supported mode when an unsupported
 * one is requested, and power snaps to the nearest step. The noise figure
 * is read by the server registry when it registers the receiver slot, so
 * a better radio genuinely hears a weaker signal.
 *
 * Ordering note: index 0 is the general-purpose Falcon HT-5 so a plain
 * radio stack with no metadata behaves like a 145 MHz narrowband FM set,
 * matching the radio that existed before models were introduced. The
 * remaining order is cosmetic; it only drives the creative tab listing.
 */
public enum RadioModel {

    FALCON_HT5("falcon_ht5", "Falcon HT-5", 1,
            136_000_000.0, 470_000_000.0, 12_500.0, false,
            new float[] { 0.5f, 2f, 5f },
            new Modulation[] { Modulation.NARROW_FM, Modulation.WIDE_FM, Modulation.AM },
            9.0f, 145_000_000.0, 15_000f, true, false),

    CHIRP_FRS2("chirp_frs2", "Chirp FRS-2", 0,
            462_000_000.0, 467_725_000.0, 12_500.0, true,
            new float[] { 0.5f },
            new Modulation[] { Modulation.NARROW_FM },
            18.0f, 462_562_500.0, 12_500f, false, false),

    POCKET_CB("pocket_cb", "PocketCB HT-27", 0,
            26_965_000.0, 27_405_000.0, 10_000.0, true,
            new float[] { 1f, 4f },
            new Modulation[] { Modulation.AM, Modulation.NARROW_FM },
            16.0f, 27_185_000.0, 10_000f, true, false),

    MARINER_VHF("mariner_vhf", "Mariner VHF-H1", 1,
            156_000_000.0, 162_025_000.0, 25_000.0, true,
            new float[] { 1f, 5f },
            new Modulation[] { Modulation.NARROW_FM },
            8.0f, 156_800_000.0, 25_000f, false, false),

    SPECTRE_TR9("spectre_tr9", "Spectre TR-9", 2,
            136_000_000.0, 470_000_000.0, 12_500.0, false,
            new float[] { 1f, 2f, 4f, 6f },
            new Modulation[] { Modulation.NARROW_FM },
            5.5f, 446_000_000.0, 12_500f, true, false),

    NOMAD_MB44("nomad_mb44", "Nomad MB-44", 2,
            500_000.0, 470_000_000.0, 1_000.0, false,
            new float[] { 0.5f, 1f, 2.5f, 5f },
            new Modulation[] { Modulation.NARROW_FM, Modulation.WIDE_FM, Modulation.AM,
                    Modulation.USB, Modulation.LSB, Modulation.CW },
            6.0f, 145_000_000.0, 15_000f, true, true),

    LONGSHOT_HF("longshot_hf", "Longshot HF-Manpack", 3,
            3_000_000.0, 30_000_000.0, 1_000.0, false,
            new float[] { 10f, 20f },
            new Modulation[] { Modulation.USB, Modulation.LSB, Modulation.CW,
                    Modulation.AM, Modulation.NARROW_FM },
            7.0f, 14_200_000.0, 2_700f, true, false),

    CIPHER_T1("cipher_t1", "Cipher T-1", 3,
            1_500_000.0, 512_000_000.0, 5_000.0, false,
            new float[] { 1f, 2f, 4f, 8f },
            new Modulation[] { Modulation.NARROW_FM, Modulation.USB, Modulation.LSB },
            4.0f, 245_000_000.0, 15_000f, true, false);

    private final String key;
    private final String displayName;
    private final int tier;
    private final double minHz;
    private final double maxHz;
    private final double tuningStepHz;
    private final boolean fixedGrid;
    private final float[] powerSteps;
    private final Modulation[] modulations;
    private final float noiseFigureDb;
    private final double defaultHz;
    private final float defaultBandwidthHz;
    private final boolean beepRemovable;
    private final boolean hasWaterfall;

    RadioModel(String key, String displayName, int tier, double minHz, double maxHz, double tuningStepHz,
               boolean fixedGrid, float[] powerSteps, Modulation[] modulations, float noiseFigureDb, double defaultHz,
               float defaultBandwidthHz, boolean beepRemovable, boolean hasWaterfall) {
        this.key = key;
        this.displayName = displayName;
        this.tier = tier;
        this.minHz = minHz;
        this.maxHz = maxHz;
        this.tuningStepHz = tuningStepHz;
        this.fixedGrid = fixedGrid;
        this.powerSteps = powerSteps;
        this.modulations = modulations;
        this.noiseFigureDb = noiseFigureDb;
        this.defaultHz = defaultHz;
        this.defaultBandwidthHz = defaultBandwidthHz;
        this.beepRemovable = beepRemovable;
        this.hasWaterfall = hasWaterfall;
    }

    /** Resolve by item metadata, clamped to a valid model. */
    public static RadioModel byIndex(int i) {
        RadioModel[] all = values();
        if (i < 0 || i >= all.length) return all[0];
        return all[i];
    }

    public int index() {
        return ordinal();
    }

    public String key() {
        return key;
    }

    /** Icon and texture stem, resolved under textures/items. */
    public String textureName() {
        return "radio_" + key;
    }

    public String displayName() {
        return displayName;
    }

    public int tier() {
        return tier;
    }

    public double defaultHz() {
        return defaultHz;
    }

    public float defaultBandwidthHz() {
        return defaultBandwidthHz;
    }

    public float noiseFigureDb() {
        return noiseFigureDb;
    }

    public Modulation[] modulations() {
        return modulations;
    }

    public Modulation defaultModulation() {
        return modulations[0];
    }

    /** True when the courtesy beep can be turned off. Cheap sets wire it on. */
    public boolean beepRemovable() {
        return beepRemovable;
    }

    public boolean hasWaterfall() {
        return hasWaterfall;
    }

    public float defaultPower() {
        return powerSteps[0];
    }

    public boolean allows(Modulation m) {
        for (Modulation x : modulations) {
            if (x == m) return true;
        }
        return false;
    }

    /** Next supported modulation, wrapping. Used by the mode button. */
    public Modulation nextModulation(Modulation cur) {
        int idx = -1;
        for (int i = 0; i < modulations.length; i++) {
            if (modulations[i] == cur) {
                idx = i;
                break;
            }
        }
        return modulations[(idx + 1) % modulations.length];
    }

    /**
     * Clamp a frequency to the band, and for a fixed-grid set snap it to
     * the nearest channel. Free-tune sets only clamp.
     */
    public double clampHz(double hz) {
        double c = hz < minHz ? minHz : (hz > maxHz ? maxHz : hz);
        if (fixedGrid && tuningStepHz > 0) {
            double n = Math.round((c - minHz) / tuningStepHz);
            c = minHz + n * tuningStepHz;
            if (c < minHz) c = minHz;
            if (c > maxHz) c = maxHz;
        }
        return c;
    }

    public float nearestPower(float w) {
        float best = powerSteps[0];
        float bestDelta = Math.abs(w - best);
        for (float p : powerSteps) {
            float d = Math.abs(w - p);
            if (d < bestDelta) {
                bestDelta = d;
                best = p;
            }
        }
        return best;
    }

    public float nextPower(float cur) {
        for (float p : powerSteps) {
            if (p > cur + 1e-3f) return p;
        }
        return powerSteps[powerSteps.length - 1];
    }

    public float previousPower(float cur) {
        float prev = powerSteps[0];
        for (float p : powerSteps) {
            if (p < cur - 1e-3f) prev = p;
            else break;
        }
        return prev;
    }
}