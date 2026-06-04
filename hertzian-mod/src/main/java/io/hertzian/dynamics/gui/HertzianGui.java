package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.opengl.GL11;

/**
 * Common parent for every mod-specific GUI. Provides:
 *
 * <ul>
 * <li>Dark scope-style background panel rendering, no texture
 * file required.</li>
 * <li>Shared colour palette so every GUI feels like part of the
 * same instrument.</li>
 * <li>Pixel-perfect title bar with optional subtitle line.</li>
 * <li>Helpers for graticule and value-on-LCD rendering used by
 * the spectrum widget and the digital display.</li>
 * </ul>
 *
 * <p>
 * The "no texture file" choice is deliberate: a GUI texture
 * pipeline would force resource-pack indexing for every layout
 * tweak. Vector drawing through {@code drawRect} keeps every
 * change in code-review.
 */
public abstract class HertzianGui extends GuiScreen {

    // Scope-style palette. ARGB literals.
    protected static final int COL_PANEL_BG = 0xFF0A0F0A;
    protected static final int COL_PANEL_BORDER = 0xFF2A6E2A;
    protected static final int COL_GRATICULE = 0xFF1A4A1A;
    protected static final int COL_TRACE = 0xFF66FF66;
    protected static final int COL_LCD_BG = 0xFF1A1A0A;
    protected static final int COL_LCD_TEXT = 0xFFFFCC22;
    protected static final int COL_LABEL = 0xFFAACCAA;
    protected static final int COL_DANGER = 0xFFFF4444;
    protected static final int COL_SAFE = 0xFF44FF66;

    protected int panelWidth = 220;
    protected int panelHeight = 180;
    protected int panelLeft;
    protected int panelTop;

    private final String title;
    private final String subtitle;

    protected HertzianGui(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    @Override
    public void initGui() {
        super.initGui();
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        layoutWidgets();
    }

    /** Subclasses register buttons / widgets here. Called from {@link #initGui}. */
    protected abstract void layoutWidgets();

    @Override
    public boolean doesGuiPauseGame() {
        // Spectrum widgets and audio playback should keep running
        // while the GUI is open; pausing the game would freeze the
        // very signals the player is looking at.
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawPanel();
        drawTitleBar();
        drawContent(mouseX, mouseY, partialTicks);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Override in subclasses to draw widgets and content. */
    protected abstract void drawContent(int mouseX, int mouseY, float partialTicks);

    private void drawPanel() {
        drawRect(panelLeft - 2, panelTop - 2, panelLeft + panelWidth + 2, panelTop + panelHeight + 2, COL_PANEL_BORDER);
        drawRect(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, COL_PANEL_BG);
    }

    private void drawTitleBar() {
        int barH = subtitle == null ? 14 : 22;
        drawRect(panelLeft, panelTop, panelLeft + panelWidth, panelTop + barH, COL_PANEL_BORDER);
        drawCenteredString(fontRendererObj, title, panelLeft + panelWidth / 2, panelTop + 3, 0xFFE0FFE0);
        if (subtitle != null) {
            drawCenteredString(fontRendererObj, subtitle, panelLeft + panelWidth / 2, panelTop + 13, COL_LABEL);
        }
    }

    /** Draw a graticule grid inside a rectangle, useful for scope/waterfall widgets. */
    protected void drawGraticule(int x0, int y0, int x1, int y1, int divX, int divY) {
        for (int i = 1; i < divX; i++) {
            int xi = x0 + (x1 - x0) * i / divX;
            drawVerticalLine(xi, y0, y1 - 1, COL_GRATICULE);
        }
        for (int i = 1; i < divY; i++) {
            int yi = y0 + (y1 - y0) * i / divY;
            drawHorizontalLine(x0, x1 - 1, yi, COL_GRATICULE);
        }
    }

    /** LCD-style frequency display: dark amber digits on dark background. */
    protected void drawLcdDisplay(int x, int y, int w, int h, String text) {
        drawRect(x, y, x + w, y + h, COL_LCD_BG);
        drawRect(x, y, x + w, y + 1, COL_PANEL_BORDER);
        drawRect(x, y + h - 1, x + w, y + h, COL_PANEL_BORDER);
        drawRect(x, y, x + 1, y + h, COL_PANEL_BORDER);
        drawRect(x + w - 1, y, x + w, y + h, COL_PANEL_BORDER);
        int tw = fontRendererObj.getStringWidth(text);
        fontRendererObj.drawString(text, x + (w - tw) / 2, y + (h - 8) / 2, COL_LCD_TEXT);
    }

    /** Coloured horizontal bar from 0 to 1, used by S-meter and signal levels. */
    protected void drawHorizontalBar(int x, int y, int w, int h, float value, int colour) {
        drawRect(x, y, x + w, y + h, COL_LCD_BG);
        int barW = (int) (w * Math.max(0f, Math.min(1f, value)));
        drawRect(x, y, x + barW, y + h, colour);
        drawRect(x, y, x + w, y + 1, COL_GRATICULE);
        drawRect(x, y + h - 1, x + w, y + h, COL_GRATICULE);
        drawRect(x, y, x + 1, y + h, COL_GRATICULE);
        drawRect(x + w - 1, y, x + w, y + h, COL_GRATICULE);
    }

    /** Reset GL state common to widget rendering after we touched colour or blend. */
    protected void resetGl() {
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glDisable(GL11.GL_BLEND);
    }
}
