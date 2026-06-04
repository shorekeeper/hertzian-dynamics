package io.hertzian.dynamics.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * Horizontal value slider drawn in the scope palette, used for the
 * receiver volume control. Extends {@link GuiButton}
 * so it lives in the host GUI's {@code buttonList} and inherits
 * visibility and bounds handling, but it replaces the momentary press
 * with a continuous drag, following the same pattern the vanilla
 * {@code GuiSlider} uses: the value is updated inside {@code drawButton}
 * while dragging and committed by the host on release.
 *
 * <p>
 * The value is normalised to [0, 1]; the host maps it to a
 * meaningful range. The host reads {@link #value()} on the release
 * event ({@code mouseMovedOrUp}) so a drag produces one network update
 * rather than one per frame.
 */
public final class Slider extends GuiButton {

    private float value;
    private boolean dragging;
    private final String prefix;

    public Slider(int id, int x, int y, int w, int h, String prefix, float initial) {
        super(id, x, y, w, h, "");
        this.prefix = prefix;
        this.value = clamp(initial);
    }

    public float value() {
        return value;
    }

    public void setValue(float v) {
        this.value = clamp(v);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (this.enabled && this.visible
            && mouseX >= this.xPosition
            && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height) {
            updateFromMouse(mouseX);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        dragging = false;
    }

    private void updateFromMouse(int mouseX) {
        float t = (float) (mouseX - (this.xPosition + 2)) / (float) (this.width - 4);
        this.value = clamp(t);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        if (dragging) updateFromMouse(mouseX);

        int x0 = this.xPosition, y0 = this.yPosition;
        int x1 = x0 + this.width, y1 = y0 + this.height;
        drawRect(x0, y0, x1, y1, 0xFF1A1A0A);
        drawRect(x0, y0, x1, y0 + 1, ScopeButton.COL_BORDER);
        drawRect(x0, y1 - 1, x1, y1, ScopeButton.COL_BORDER);
        drawRect(x0, y0, x0 + 1, y1, ScopeButton.COL_BORDER);
        drawRect(x1 - 1, y0, x1, y1, ScopeButton.COL_BORDER);

        int fillW = (int) ((this.width - 4) * value);
        drawRect(x0 + 2, y0 + 2, x0 + 2 + fillW, y1 - 2, 0xFF2A8E3A);

        int handleX = x0 + 2 + fillW;
        drawRect(handleX - 1, y0, handleX + 1, y1, 0xFFBFE8BF);

        String label = prefix + " " + (int) (value * 100f) + "%";
        this.drawCenteredString(mc.fontRenderer, label, x0 + this.width / 2, y0 + (this.height - 8) / 2, 0xFFFFCC22);
    }
}
