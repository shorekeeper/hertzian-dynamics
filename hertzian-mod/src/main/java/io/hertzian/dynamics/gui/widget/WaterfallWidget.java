package io.hertzian.dynamics.gui.widget;

import net.minecraft.client.gui.Gui;

/**
 * Waterfall display: a 2D buffer of spectrum rows, newest at the
 * top, oldest at the bottom. Magnitude in dB maps to colour through
 * a viridis-derived palette.
 *
 * <p>
 * Rendering details:
 * <ul>
 * <li>Bin stretching. Each bin occupies
 * {@code (b+1)*w/bins - b*w/bins} pixels, so the row fills the
 * widget width exactly with no right-side margin.</li>
 * <li>Viridis palette. Perceptually uniform, so equal dB steps read
 * as equal visual steps: deep purple through blue for the noise
 * floor, green for a moderate signal, yellow toward a strong
 * carrier.</li>
 * <li>dB range from -100 to 0 dB. This spans the DFT scanner output,
 * which runs from about -90 dB at the noise floor to roughly
 * -10 dB on a saturated carrier, with headroom at both ends so
 * the colour map does not clip.</li>
 * </ul>
 */
public final class WaterfallWidget extends Gui {

    private final int bins;
    private final int rows;
    private final float[][] history;
    private int writeRow = 0;
    private boolean filled = false;

    public WaterfallWidget(int bins, int rows) {
        this.bins = bins;
        this.rows = rows;
        this.history = new float[rows][bins];
    }

    public void pushRow(float[] spectrum) {
        if (spectrum.length != bins) return;
        System.arraycopy(spectrum, 0, history[writeRow], 0, bins);
        writeRow = (writeRow + 1) % rows;
        if (writeRow == 0) filled = true;
    }

    public void draw(int x0, int y0, int x1, int y1) {
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 0 || h <= 0) return;
        int rowCount = filled ? rows : writeRow;
        if (rowCount == 0) return;

        // With 192 bins and 128 rows the worst case is 24,576
        // drawRect calls per frame, several times the overhead a
        // Forge GUI tolerates at 60 FPS. Adjacent bins of identical
        // colour are collapsed into one wider rect, so a flat noise
        // floor region (many neighbouring bins in the same colour
        // bucket) costs one rect instead of N. The worst case (every
        // bin a different colour) matches the per-bin cost; the
        // typical case (noise floor plus a few peaks) is an order of
        // magnitude cheaper.
        for (int r = 0; r < rowCount; r++) {
            int historyIdx = (writeRow - 1 - r + rows) % rows;
            int yy0 = y0 + r * h / rowCount;
            int yy1 = y0 + (r + 1) * h / rowCount;
            float[] rowData = history[historyIdx];

            int runStart = 0;
            int runColour = magnitudeToColour(rowData[0]);
            for (int b = 1; b < bins; b++) {
                int colour = magnitudeToColour(rowData[b]);
                if (colour != runColour) {
                    int xx0 = x0 + runStart * w / bins;
                    int xx1 = x0 + b * w / bins;
                    drawRect(xx0, yy0, xx1, yy1, runColour);
                    runStart = b;
                    runColour = colour;
                }
            }
            int xx0 = x0 + runStart * w / bins;
            int xx1 = x0 + bins * w / bins;
            drawRect(xx0, yy0, xx1, yy1, runColour);
        }
    }

    public float[] latestRow() {
        int idx = (writeRow - 1 + rows) % rows;
        return history[idx];
    }

    /**
     * dB to ARGB mapping through a viridis-style perceptually
     * uniform palette. Range: -100 dB (deep purple, near black)
     * to 0 dB (yellow, near white).
     *
     * <p>
     * The palette uses six anchor stops sampled from the
     * canonical matplotlib viridis colormap. Linear interpolation
     * between stops gives a smooth gradient with no banding.
     */
    private static int magnitudeToColour(float db) {
        float t = (db + 100f) / 100f;
        if (t < 0f) t = 0f;
        else if (t > 1f) t = 1f;

        // Six-stop viridis approximation, RGB triples.
        // Stop 0.0: deep purple (68, 1, 84)
        // Stop 0.2: blue (59, 82, 139)
        // Stop 0.4: teal (33, 145, 140)
        // Stop 0.6: green (94, 201, 98)
        // Stop 0.8 and 1.0: bright yellow (253, 231, 37). The last two
        // stops are duplicated to keep the hot end bright without
        // overshooting into red.
        final float[][] stops = { { 68f, 1f, 84f }, { 59f, 82f, 139f }, { 33f, 145f, 140f }, { 94f, 201f, 98f },
            { 253f, 231f, 37f }, { 253f, 231f, 37f }, };
        float segment = t * 5f;
        int idx = (int) segment;
        if (idx >= 5) idx = 4;
        float frac = segment - idx;
        float[] a = stops[idx];
        float[] b = stops[idx + 1];
        int r = (int) (a[0] + (b[0] - a[0]) * frac);
        int g = (int) (a[1] + (b[1] - a[1]) * frac);
        int bl = (int) (a[2] + (b[2] - a[2]) * frac);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
