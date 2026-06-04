package io.hertzian.dynamics.gui.widget;

import net.minecraft.client.gui.GuiButton;

/**
 * Custom button variants. Currently a single subclass for "step
 * buttons" used in frequency tuning; future variants (toggles,
 * radio groups) extend the same package.
 */
public final class Buttons {

    private Buttons() {}

    /**
     * Compact square button with a single character label. Used in
     * banks of frequency-step buttons (- 1 MHz, +12.5 kHz, etc).
     */
    public static final class StepButton extends GuiButton {

        public final double deltaHz;

        public StepButton(int id, int x, int y, int w, int h, String label, double deltaHz) {
            super(id, x, y, w, h, label);
            this.deltaHz = deltaHz;
        }
    }

    /** Toggle button: shows a different label/colour per state. */
    public static final class ToggleButton extends GuiButton {

        public final String labelOn;
        public final String labelOff;
        public boolean state;

        public ToggleButton(int id, int x, int y, int w, int h, String labelOn, String labelOff, boolean state) {
            super(id, x, y, w, h, state ? labelOn : labelOff);
            this.labelOn = labelOn;
            this.labelOff = labelOff;
            this.state = state;
        }

        public void setState(boolean s) {
            this.state = s;
            this.displayString = s ? labelOn : labelOff;
        }
    }
}
