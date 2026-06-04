package io.hertzian.dynamics.gui;

import java.util.List;

import net.minecraft.client.gui.GuiButton;

import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketTeletypeSettings;
import io.hertzian.dynamics.tile.TileTeletype;

/**
 * Telegraph receiver GUI: tune a CW signal and read the decoded Morse as
 * text. Built on the scope widgets like the rest of the panels.
 *
 * <p>
 * Layout
 * <ul>
 * <li>Frequency LCD with Shift = x10 step buttons.</li>
 * <li>Two CW filter widths, a clear button, and a WPM / S-meter row
 * with a TU (tuned) and KEY (key-down) indicator.</li>
 * <li>A scrolling keying scope: the per-chunk key-down level pushed by
 * the tile is appended to a ring and drawn as a bar trace, so the
 * dots and dashes are visible as they arrive.</li>
 * <li>A text area that word-wraps the decoded copy and stays scrolled to
 * the most recent line.</li>
 * </ul>
 */
public final class GuiTeletype extends HertzianGui {

    private final TileTeletype tile;
    private final int tileX, tileY, tileZ, tileDim;

    private static final int BTN_STEP_BASE = 100;
    private static final int BTN_BW_NARROW = 200;
    private static final int BTN_BW_WIDE = 201;
    private static final int BTN_CLEAR = 202;

    private static final int HISTORY = 96;
    private final float[] levelHistory = new float[HISTORY];
    private int historyHead = 0;
    private int historyTick = 0;

    public GuiTeletype(TileTeletype tile) {
        super("Telegraph Receiver", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 288;
        this.panelHeight = 264;
    }

    @Override
    protected void layoutWidgets() {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        double[] steps = { -1_000_000, -100_000, -1_000, 1_000, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-1k", "+1k", "+100k", "+1M" };
        int sW = 40, sGap = 4;
        int sTotal = 6 * sW + 5 * sGap;
        int sStart = pl + (pw - sTotal) / 2;
        for (int i = 0; i < steps.length; i++) {
            buttonList.add(
                ScopeButton.step(BTN_STEP_BASE + i, sStart + i * (sW + sGap), pt + 54, sW, 14, labels[i], steps[i]));
        }

        buttonList.add(new ScopeButton(BTN_BW_NARROW, pl + 18, pt + 72, 70, 16, "Narrow 250"));
        buttonList.add(new ScopeButton(BTN_BW_WIDE, pl + 92, pt + 72, 70, 16, "Wide 800"));
        buttonList.add(new ScopeButton(BTN_CLEAR, pl + pw - 80, pt + 72, 62, 16, "Clear"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            double d = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) d *= 10.0;
            tile.setTunedHz(tile.tunedHz() + d);
            send(false);
            return;
        }
        if (button.id == BTN_BW_NARROW) {
            tile.setBandwidthHz(250f);
            send(false);
        } else if (button.id == BTN_BW_WIDE) {
            tile.setBandwidthHz(800f);
            send(false);
        } else if (button.id == BTN_CLEAR) {
            tile.clearText();
            send(true);
        }
    }

    private void send(boolean clear) {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketTeletypeSettings(tileDim, tileX, tileY, tileZ, tile.tunedHz(), tile.bandwidthHz(), clear));
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Push a keying-level sample for the scope.
        historyTick++;
        if (historyTick >= 2) {
            historyTick = 0;
            levelHistory[historyHead] = tile.clientLevel();
            historyHead = (historyHead + 1) % HISTORY;
        }

        String freq = String.format("%10.4f kHz", tile.tunedHz() / 1.0e3);
        drawLcdDisplay(pl + 18, pt + 28, pw - 36, 24, freq);

        float snr = tile.clientSnrDb();
        boolean tuneOn = snr >= 8f;
        boolean keyOn = tile.clientLevel() > 0.3f;

        // WPM, S-meter, indicators.
        drawString(
            fontRendererObj,
            String.format("CW  %.0f WPM  BW %d Hz", tile.clientWpm(), (int) tile.bandwidthHz()),
            pl + 18,
            pt + 92,
            COL_LABEL);
        drawLed(pl + pw - 70, pt + 91, "TU", tuneOn, 0xFF44FF66);
        drawLed(pl + pw - 38, pt + 91, "KEY", keyOn, 0xFFFFCC22);

        float level = (snr + 10f) / 70f;
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        int barColour = level > 0.66f ? COL_SAFE : (level > 0.2f ? COL_TRACE : COL_DANGER);
        drawString(fontRendererObj, "Sig", pl + 18, pt + 104, COL_LABEL);
        drawHorizontalBar(pl + 40, pt + 102, 150, 8, level, barColour);

        // Keying scope.
        int kx0 = pl + 18, ky0 = pt + 116, kx1 = pl + pw - 18, ky1 = ky0 + 26;
        drawRect(kx0 - 1, ky0 - 1, kx1 + 1, ky1 + 1, COL_PANEL_BORDER);
        drawRect(kx0, ky0, kx1, ky1, COL_LCD_BG);
        int kw = kx1 - kx0, kh = ky1 - ky0;
        for (int i = 0; i < HISTORY; i++) {
            int idx = (historyHead + i) % HISTORY;
            float v = levelHistory[idx];
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            int barH = (int) (v * (kh - 2));
            int xx = kx0 + i * kw / HISTORY;
            int xx1 = kx0 + (i + 1) * kw / HISTORY;
            drawRect(xx, ky1 - barH, xx1, ky1, COL_TRACE);
        }

        // Decoded text area, word-wrapped, scrolled to the bottom.
        int tx0 = pl + 18, ty0 = pt + 148, tx1 = pl + pw - 18, ty1 = pt + panelHeight - 14;
        drawRect(tx0 - 1, ty0 - 1, tx1 + 1, ty1 + 1, COL_PANEL_BORDER);
        drawRect(tx0, ty0, tx1, ty1, COL_LCD_BG);

        String text = tile.clientText();
        if (text != null && !text.isEmpty()) {
            int wrapW = (tx1 - tx0) - 8;
            @SuppressWarnings("unchecked")
            List<String> lines = fontRendererObj.listFormattedStringToWidth(text, wrapW);
            int lineH = 10;
            int maxLines = (ty1 - ty0 - 6) / lineH;
            int from = Math.max(0, lines.size() - maxLines);
            int y = ty0 + 4;
            for (int i = from; i < lines.size(); i++) {
                fontRendererObj.drawString(lines.get(i), tx0 + 4, y, COL_LCD_TEXT);
                y += lineH;
            }
        } else {
            drawString(fontRendererObj, "Waiting for signal...", tx0 + 4, ty0 + 4, COL_LABEL);
        }

        drawString(fontRendererObj, "Shift + step = x10", pl + 18, pt + panelHeight - 12, COL_LABEL);
    }

    private void drawLed(int x, int y, String label, boolean on, int onColour) {
        int col = on ? onColour : 0xFF1A2A1A;
        drawRect(x, y, x + 8, y + 8, col);
        drawRect(x, y, x + 8, y + 1, COL_PANEL_BORDER);
        drawRect(x, y + 7, x + 8, y + 8, COL_PANEL_BORDER);
        drawRect(x, y, x + 1, y + 8, COL_PANEL_BORDER);
        drawRect(x + 7, y, x + 8, y + 8, COL_PANEL_BORDER);
        drawString(fontRendererObj, label, x + 10, y, on ? onColour : COL_LABEL);
    }
}
