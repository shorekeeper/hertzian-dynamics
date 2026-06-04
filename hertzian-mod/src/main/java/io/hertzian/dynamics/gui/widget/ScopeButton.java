package io.hertzian.dynamics.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * Flat instrument-panel button drawn in the scope palette instead of
 * the vanilla stone texture. Introduced when the receiver and
 * transmitter GUIs were rebuilt to drop the vanilla button look. The
 * class deliberately keeps extending {@link GuiButton} so the existing
 * {@code buttonList} dispatch, click sound and {@code actionPerformed}
 * wiring keep working; only the rendering and a small payload are added.
 *
 * <p>
 * Three flavours share one class to keep the widget count low: a
 * plain momentary button, a frequency step button carrying a
 * {@code deltaHz} payload the host reads on press, and a two-state
 * toggle that swaps its label and accent colour. The flavour is chosen
 * by which factory or constructor the caller uses.
 */
public class ScopeButton extends GuiButton {

    public static final int COL_BG = 0xFF12331A;
    public static final int COL_HOVER = 0xFF1E5230;
    public static final int COL_BORDER = 0xFF2A6E2A;
    public static final int COL_DISABLED = 0xFF101510;
    public static final int COL_TEXT = 0xFFBFE8BF;
    public static final int COL_TEXT_HOVER = 0xFFFFFFFF;
    public static final int COL_TEXT_ON = 0xFF6CFF8A;

    /** Non-zero on a frequency step button; the GUI reads it on press. */
    public final double deltaHz;

    private final boolean toggle;
    private String labelOn;
    private String labelOff;
    private boolean state;
    private int accentOn = COL_TEXT_ON;

    public ScopeButton(int id, int x, int y, int w, int h, String label) {
        super(id, x, y, w, h, label);
        this.deltaHz = 0.0;
        this.toggle = false;
    }

    private ScopeButton(int id, int x, int y, int w, int h, String label, double deltaHz) {
        super(id, x, y, w, h, label);
        this.deltaHz = deltaHz;
        this.toggle = false;
    }

    private ScopeButton(int id, int x, int y, int w, int h, String on, String off, boolean state) {
        super(id, x, y, w, h, state ? on : off);
        this.deltaHz = 0.0;
        this.toggle = true;
        this.labelOn = on;
        this.labelOff = off;
        this.state = state;
    }

    /** Frequency step button carrying its own delta. */
    public static ScopeButton step(int id, int x, int y, int w, int h, String label, double deltaHz) {
        return new ScopeButton(id, x, y, w, h, label, deltaHz);
    }

    /** Two-state toggle button. */
    public static ScopeButton toggle(int id, int x, int y, int w, int h, String on, String off, boolean state) {
        return new ScopeButton(id, x, y, w, h, on, off, state);
    }

    public boolean isToggle() {
        return toggle;
    }

    public boolean state() {
        return state;
    }

    public void setState(boolean s) {
        this.state = s;
        this.displayString = s ? labelOn : labelOff;
    }

    public ScopeButton accent(int colour) {
        this.accentOn = colour;
        return this;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        boolean hover = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;
        this.field_146123_n = hover;

        int x0 = this.xPosition;
        int y0 = this.yPosition;
        int x1 = x0 + this.width;
        int y1 = y0 + this.height;

        int fill;
        if (!this.enabled) fill = COL_DISABLED;
        else if (toggle && state) fill = COL_HOVER;
        else if (hover) fill = COL_HOVER;
        else fill = COL_BG;

        drawRect(x0, y0, x1, y1, fill);
        // One pixel border on every edge.
        drawRect(x0, y0, x1, y0 + 1, COL_BORDER);
        drawRect(x0, y1 - 1, x1, y1, COL_BORDER);
        drawRect(x0, y0, x0 + 1, y1, COL_BORDER);
        drawRect(x1 - 1, y0, x1, y1, COL_BORDER);

        int textColour;
        if (!this.enabled) textColour = 0xFF556655;
        else if (toggle && state) textColour = accentOn;
        else if (hover) textColour = COL_TEXT_HOVER;
        else textColour = COL_TEXT;

        this.drawCenteredString(
            mc.fontRenderer,
            this.displayString,
            x0 + this.width / 2,
            y0 + (this.height - 8) / 2,
            textColour);
    }
}
