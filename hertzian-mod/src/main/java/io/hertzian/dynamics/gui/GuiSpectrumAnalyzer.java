package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Mouse;

import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.gui.widget.WaterfallWidget;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketSpectrumSettings;
import io.hertzian.dynamics.tile.TileSpectrumAnalyzer;

/**
 * SDR-style spectrum analyzer GUI.
 *
 * <p>
 * Controls and readouts
 * <ul>
 * <li>Every control is a {@link ScopeButton}, drawn in the scope
 * palette.</li>
 * <li>Frequency step buttons carry the Shift = x10 modifier, matching
 * the receiver and transmitter: holding Shift while pressing a
 * step button multiplies the step by ten.</li>
 * <li>The cursor is a solid bright crosshair drawn over the whole
 * spectrum and trace area whenever the mouse is inside it, with a
 * readout box showing the frequency under the cursor and the dB of
 * the bin beneath it. It tracks the tuning because the spectrum
 * data packet leaves the live centre and span untouched (see the
 * tile and packet side).</li>
 * <li>A dim centre marker shows the tuned frequency at all times, a
 * peak detector prints the strongest bin's frequency and level,
 * and the resolution bandwidth (span / bins) is shown so the
 * reading has context.</li>
 * <li>Frequency coverage extends to the tile's span ceiling,
 * {@link TileSpectrumAnalyzer#MAX_SPAN_HZ} (40 kHz); the span
 * presets and wheel zoom follow it.</li>
 * </ul>
 *
 * <p>
 * Tuning ownership
 * While the GUI is open the client owns the centre and span. Button
 * presses, the wheel zoom and click-to-tune update them optimistically
 * and ship a {@link PacketSpectrumSettings} to the server; the incoming
 * spectrum frames do not touch them, so the axis and cursor follow
 * input with no round-trip lag.
 */

public final class GuiSpectrumAnalyzer extends HertzianGui {

    private final TileSpectrumAnalyzer tile;
    private final int tileX, tileY, tileZ, tileDim;
    private final WaterfallWidget waterfall = new WaterfallWidget(TileSpectrumAnalyzer.BINS, 128);

    private float[] lastSpectrumRef;

    private static final int BTN_STEP_BASE = 100;
    private static final int BTN_BAND_CB = 110;
    private static final int BTN_BAND_HF20 = 111;
    private static final int BTN_BAND_VHF2 = 112;
    private static final int BTN_BAND_FM = 113;
    private static final int BTN_SPAN_1K = 120;
    private static final int BTN_SPAN_5K = 121;
    private static final int BTN_SPAN_15K = 122;
    private static final int BTN_SPAN_40K = 123;
    private static final int BTN_AGC_TOGGLE = 130;
    private static final int BTN_MOD_CYCLE = 131;

    private static final int CROSSHAIR = 0xFF49E0FF;
    private static final int CENTER_MARKER = 0x66229BFF;
    private static final int STATION_MARKER = 0xC0FFCC22;
    private static final int PEAK_MARKER = 0xFFFFE048;

    /**
     * Curated list of modulations the analyzer cycles through. The
     * hidden-receiver modulation has no effect on the DFT magnitude, but
     * it does drive the station ID decode: the analyzer must demodulate
     * with the station's modulation to recover the subcarrier, so cycling
     * the mode to match a station is how its name is read.
     */
    private static final Modulation[] ANALYZER_MODES = { Modulation.AM, Modulation.NARROW_FM, Modulation.WIDE_FM,
        Modulation.USB, Modulation.LSB, };

    // Cached waterfall + trace areas so handleMouseInput and mouseClicked
    // can pick them up without recomputing layout.
    private int wx0, wy0, wx1, wy1;
    private int tx0, ty0, tx1, ty1;

    public GuiSpectrumAnalyzer(TileSpectrumAnalyzer tile) {
        super("Spectrum Analyzer", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 360;
        this.panelHeight = 336;
    }

    @Override
    protected void layoutWidgets() {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Band presets.
        int bandW = 60, bandH = 14, bandGap = 6;
        int bandTotal = bandW * 4 + bandGap * 3;
        int bandStart = pl + (pw - bandTotal) / 2;
        int bandRow = pt + 40;
        buttonList.add(new ScopeButton(BTN_BAND_CB, bandStart, bandRow, bandW, bandH, "CB 27"));
        buttonList.add(new ScopeButton(BTN_BAND_HF20, bandStart + (bandW + bandGap), bandRow, bandW, bandH, "HF 14"));
        buttonList
            .add(new ScopeButton(BTN_BAND_VHF2, bandStart + 2 * (bandW + bandGap), bandRow, bandW, bandH, "VHF 145"));
        buttonList
            .add(new ScopeButton(BTN_BAND_FM, bandStart + 3 * (bandW + bandGap), bandRow, bandW, bandH, "FM 100"));

        // Frequency step buttons, Shift = x10.
        double[] steps = { -1_000_000, -100_000, -5_000, 5_000, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-5k", "+5k", "+100k", "+1M" };
        int btnW = 42, btnH = 14, gap = 4;
        int stepTotal = btnW * 6 + gap * 5;
        int stepStart = pl + (pw - stepTotal) / 2;
        int stepRow = pt + 58;
        for (int i = 0; i < steps.length; i++) {
            buttonList.add(
                ScopeButton
                    .step(BTN_STEP_BASE + i, stepStart + i * (btnW + gap), stepRow, btnW, btnH, labels[i], steps[i]));
        }

        // AGC bypass and analyzer modulation.
        int modeRow = pt + 76;
        int modeW = 110, modeGap = 8;
        int modeTotal = modeW * 2 + modeGap;
        int modeStart = pl + (pw - modeTotal) / 2;
        buttonList.add(
            ScopeButton
                .toggle(BTN_AGC_TOGGLE, modeStart, modeRow, modeW, 14, "AGC: Bypass", "AGC: On", tile.agcBypass()));
        buttonList.add(
            new ScopeButton(
                BTN_MOD_CYCLE,
                modeStart + modeW + modeGap,
                modeRow,
                modeW,
                14,
                "Mode: " + tile.analyzerModulation()
                    .name()));

        // Span presets pinned to the bottom; widest now matches the
        // 40 kHz coverage ceiling.
        int spanRow = pt + panelHeight - 22;
        int spanW = 56, spanGap = 6;
        int spanTotal = spanW * 4 + spanGap * 3;
        int spanStart = pl + (pw - spanTotal) / 2;
        buttonList.add(new ScopeButton(BTN_SPAN_1K, spanStart, spanRow, spanW, 14, "1 kHz"));
        buttonList.add(new ScopeButton(BTN_SPAN_5K, spanStart + (spanW + spanGap), spanRow, spanW, 14, "5 kHz"));
        buttonList.add(new ScopeButton(BTN_SPAN_15K, spanStart + 2 * (spanW + spanGap), spanRow, spanW, 14, "15 kHz"));
        buttonList.add(new ScopeButton(BTN_SPAN_40K, spanStart + 3 * (spanW + spanGap), spanRow, spanW, 14, "40 kHz"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            double d = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) d *= 10.0;
            tile.setCenterHz(tile.centerHz() + d);
            sendSettings();
            return;
        }

        boolean changed = true;
        switch (button.id) {
            case BTN_SPAN_1K:
                tile.setSpanHz(1_000.0);
                break;
            case BTN_SPAN_5K:
                tile.setSpanHz(5_000.0);
                break;
            case BTN_SPAN_15K:
                tile.setSpanHz(15_000.0);
                break;
            case BTN_SPAN_40K:
                tile.setSpanHz(TileSpectrumAnalyzer.MAX_SPAN_HZ);
                break;
            case BTN_BAND_CB:
                tile.setCenterHz(27_205_000.0);
                break;
            case BTN_BAND_HF20:
                tile.setCenterHz(14_200_000.0);
                break;
            case BTN_BAND_VHF2:
                tile.setCenterHz(145_000_000.0);
                break;
            case BTN_BAND_FM:
                tile.setCenterHz(100_000_000.0);
                break;
            case BTN_AGC_TOGGLE: {
                ScopeButton tb = (ScopeButton) button;
                tb.setState(!tb.state());
                tile.setAgcBypass(tb.state());
                break;
            }
            case BTN_MOD_CYCLE: {
                Modulation current = tile.analyzerModulation();
                int idx = -1;
                for (int i = 0; i < ANALYZER_MODES.length; i++) {
                    if (ANALYZER_MODES[i] == current) {
                        idx = i;
                        break;
                    }
                }
                Modulation next = ANALYZER_MODES[(idx + 1) % ANALYZER_MODES.length];
                tile.setAnalyzerModulation(next);
                button.displayString = "Mode: " + next.name();
                break;
            }
            default:
                changed = false;
                break;
        }
        if (changed) sendSettings();
    }

    private void sendSettings() {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketSpectrumSettings(
                tileDim,
                tileX,
                tileY,
                tileZ,
                tile.centerHz(),
                tile.spanHz(),
                tile.agcBypass(),
                tile.analyzerModulation()
                    .code()));
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (!inSpectrumArea(mouseX, mouseY)) return;

        boolean ctrl = GuiScreen.isCtrlKeyDown();
        boolean shift = GuiScreen.isShiftKeyDown();
        int dir = dWheel > 0 ? 1 : -1;

        if (ctrl) {
            // Ctrl + wheel pans the centre. Shift makes the pan fine.
            double step = shift ? 100.0 : 1_000.0;
            step = Math.max(step, tile.spanHz() * 0.05);
            tile.setCenterHz(tile.centerHz() + dir * step);
        } else {
            // Plain wheel zooms the span. Shift makes the zoom fine.
            double factor = shift ? 1.25 : 1.5;
            double newSpan = dir > 0 ? tile.spanHz() / factor : tile.spanHz() * factor;
            if (newSpan < 200.0) newSpan = 200.0;
            if (newSpan > TileSpectrumAnalyzer.MAX_SPAN_HZ) newSpan = TileSpectrumAnalyzer.MAX_SPAN_HZ;
            tile.setSpanHz(newSpan);
        }
        sendSettings();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        if (!inSpectrumArea(mouseX, mouseY)) return;
        tile.setCenterHz(frequencyAtX(mouseX));
        sendSettings();
    }

    private boolean inSpectrumArea(int mx, int my) {
        return mx >= wx0 && mx < wx1 && my >= wy0 && my < ty1;
    }

    private double frequencyAtX(int x) {
        double t = (double) (x - wx0) / (double) (wx1 - wx0);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        double half = tile.spanHz() * 0.5;
        return tile.centerHz() - half + t * tile.spanHz();
    }

    /** dB of the latest spectrum bin under widget x, or a floor if none. */
    private float dbAtX(int x) {
        float[] row = waterfall.latestRow();
        if (row == null || row.length == 0) return -120f;
        double t = (double) (x - wx0) / (double) (wx1 - wx0);
        if (t < 0) t = 0;
        if (t > 0.9999) t = 0.9999;
        int bin = (int) (t * row.length);
        if (bin < 0) bin = 0;
        if (bin >= row.length) bin = row.length - 1;
        return row[bin];
    }

    private double freqAtBin(int bin, int binCount) {
        double half = tile.spanHz() * 0.5;
        double bw = tile.spanHz() / binCount;
        return tile.centerHz() - half + (bin + 0.5) * bw;
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Header: centre, span, resolution bandwidth.
        String hdr = String.format(
            "Center %s MHz   Span %s kHz   RBW %.0f Hz",
            fmtMHz(tile.centerHz()),
            fmtKHz(tile.spanHz()),
            tile.spanHz() / TileSpectrumAnalyzer.BINS);
        drawCenteredString(fontRendererObj, hdr, pl + pw / 2, pt + 26, COL_LCD_TEXT);

        if (tile.lastSpectrum() != null && tile.lastSpectrum() != lastSpectrumRef) {
            waterfall.pushRow(tile.lastSpectrum());
            lastSpectrumRef = tile.lastSpectrum();
        }

        // Waterfall.
        wx0 = pl + 8;
        wy0 = pt + 96;
        wx1 = pl + pw - 8;
        wy1 = pt + panelHeight - 112;
        drawRect(wx0 - 1, wy0 - 1, wx1 + 1, wy1 + 1, COL_PANEL_BORDER);
        drawRect(wx0, wy0, wx1, wy1, COL_PANEL_BG);
        waterfall.draw(wx0, wy0, wx1, wy1);
        drawGraticule(wx0, wy0, wx1, wy1, 8, 4);

        // Trace below the waterfall.
        tx0 = wx0;
        ty0 = wy1 + 4;
        tx1 = wx1;
        ty1 = ty0 + 30;
        drawRect(tx0 - 1, ty0 - 1, tx1 + 1, ty1 + 1, COL_PANEL_BORDER);
        drawRect(tx0, ty0, tx1, ty1, COL_PANEL_BG);
        drawTrace(tx0, ty0, tx1, ty1);
        drawGraticule(tx0, ty0, tx1, ty1, 8, 2);

        // Centre marker: always shows where the tuned frequency is.
        int cx = (wx0 + wx1) / 2;
        drawVerticalLine(cx, wy0, ty1, CENTER_MARKER);

        // Station marker, if the centre signal decoded a name.
        String station = tile.decodedStation();
        boolean hasStation = station != null && !station.isEmpty();
        if (hasStation) {
            drawVerticalLine(cx, wy0, ty1, STATION_MARKER);
        }

        // Frequency axis labels.
        double half = tile.spanHz() * 0.5;
        String lo = String.format("%.4f", (tile.centerHz() - half) / 1.0e6);
        String mid = String.format("%.4f", tile.centerHz() / 1.0e6);
        String hi = String.format("%.4f", (tile.centerHz() + half) / 1.0e6);
        int axisY = ty1 + 2;
        drawString(fontRendererObj, lo, wx0, axisY, COL_LABEL);
        drawCenteredString(fontRendererObj, mid + " MHz", (wx0 + wx1) / 2, axisY, COL_LABEL);
        drawString(fontRendererObj, hi, wx1 - fontRendererObj.getStringWidth(hi), axisY, COL_LABEL);

        // Peak detector readout, with a marker line in the trace.
        float[] row = waterfall.latestRow();
        int peakLineY = axisY + 12;
        if (row != null && row.length > 0) {
            int peakBin = 0;
            float peakDb = row[0];
            for (int b = 1; b < row.length; b++) {
                if (row[b] > peakDb) {
                    peakDb = row[b];
                    peakBin = b;
                }
            }
            double peakHz = freqAtBin(peakBin, row.length);
            int px = wx0 + (int) ((double) peakBin / row.length * (wx1 - wx0));
            drawVerticalLine(px, ty0, ty1, PEAK_MARKER);
            drawString(
                fontRendererObj,
                String.format("Peak %.4f MHz  %.0f dB", peakHz / 1.0e6, peakDb),
                wx0,
                peakLineY,
                COL_LCD_TEXT);
        }

        // Station name and AGC mode line.
        int infoY = peakLineY + 11;
        if (hasStation) {
            drawString(fontRendererObj, "\u266A " + station, wx0, infoY, COL_LCD_TEXT);
        }
        String agcTag = tile.agcBypass() ? "AGC bypass" : "AGC on";
        drawString(fontRendererObj, agcTag, wx1 - fontRendererObj.getStringWidth(agcTag), infoY, COL_LABEL);

        // Hint line.
        drawCenteredString(
            fontRendererObj,
            "Scroll: zoom \u00B7 Ctrl+Scroll: pan \u00B7 Click: tune \u00B7 Shift+step: x10",
            pl + pw / 2,
            infoY + 11,
            COL_LABEL);

        // Hover crosshair and readout, drawn last so it sits over the
        // markers. Solid and bright so it never washes out against the
        // waterfall, which is what made the old half-transparent line
        // appear and disappear.
        if (inSpectrumArea(mouseX, mouseY)) {
            double f = frequencyAtX(mouseX);
            float dbAt = dbAtX(mouseX);
            drawVerticalLine(mouseX, wy0, ty1, CROSSHAIR);
            String tip = String.format("%.4f MHz  %.0f dB", f / 1.0e6, dbAt);
            int tipW = fontRendererObj.getStringWidth(tip) + 6;
            int tipX = mouseX + 6;
            if (tipX + tipW > pl + pw) tipX = mouseX - tipW - 2;
            int tipY = mouseY - 12;
            if (tipY < wy0) tipY = mouseY + 8;
            drawRect(tipX, tipY, tipX + tipW, tipY + 11, 0xE0000000);
            drawRect(tipX, tipY, tipX + tipW, tipY + 1, COL_PANEL_BORDER);
            drawString(fontRendererObj, tip, tipX + 3, tipY + 2, CROSSHAIR);
        }
    }

    private void drawTrace(int x0, int y0, int x1, int y1) {
        float[] row = waterfall.latestRow();
        if (row == null) return;
        int w = x1 - x0;
        int h = y1 - y0;
        for (int b = 0; b < row.length; b++) {
            float v = row[b];
            float t = (v + 100f) / 100f;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            int barH = (int) (t * (h - 2));
            int xx = x0 + b * w / row.length;
            int xx1 = x0 + (b + 1) * w / row.length;
            drawRect(xx, y1 - barH, xx1 - 1, y1, COL_TRACE);
        }
    }

    private static String fmtMHz(double hz) {
        return String.format("%.4f", hz / 1.0e6);
    }

    private static String fmtKHz(double hz) {
        return String.format("%.1f", hz / 1.0e3);
    }
}
