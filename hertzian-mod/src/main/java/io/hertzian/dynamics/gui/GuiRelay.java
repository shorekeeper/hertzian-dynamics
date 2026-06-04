package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;

import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.gui.widget.Buttons;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketRelaySettings;
import io.hertzian.dynamics.tile.TileRelay;

/**
 * Relay GUI. Two tuning rows, one for the listen frequency and one for
 * the transmit frequency, plus modulation, power and an active toggle.
 * Output is kept apart from input so the relay does not feed back on
 * itself; the warning case is handled server side.
 */
public final class GuiRelay extends HertzianGui {

    private final TileRelay tile;
    private final int tileX, tileY, tileZ, tileDim;

    private static final int BTN_IN_BASE = 100;
    private static final int BTN_OUT_BASE = 110;
    private static final int BTN_MOD = 200;
    private static final int BTN_PWR_DEC = 201;
    private static final int BTN_PWR_INC = 202;
    private static final int BTN_ACTIVE = 203;

    private static final double[] STEPS = { -1_000_000, -100_000, -12_500, 12_500, 100_000, 1_000_000 };
    private static final String[] LABELS = { "-1M", "-100k", "-12.5k", "+12.5k", "+100k", "+1M" };
    private static final float[] POWER_STEPS = { 1f, 2f, 5f, 10f, 25f, 50f, 100f, 250f };

    public GuiRelay(TileRelay tile) {
        super("Signal Relay", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 256;
        this.panelHeight = 224;
    }

    @Override
    protected void layoutWidgets() {
        layoutStepRow(BTN_IN_BASE, panelTop + 56);
        layoutStepRow(BTN_OUT_BASE, panelTop + 100);
        buttonList.add(
            new GuiButton(
                BTN_MOD,
                panelLeft + 18,
                panelTop + 128,
                96,
                20,
                "Mode: " + tile.modulation()
                    .name()));
        buttonList.add(new GuiButton(BTN_PWR_DEC, panelLeft + 122, panelTop + 128, 44, 20, "- Pwr"));
        buttonList.add(new GuiButton(BTN_PWR_INC, panelLeft + 170, panelTop + 128, 44, 20, "+ Pwr"));
        buttonList.add(
            new Buttons.ToggleButton(
                BTN_ACTIVE,
                panelLeft + 18,
                panelTop + 152,
                panelWidth - 36,
                20,
                "ACTIVE (chunk loaded)",
                "STANDBY",
                tile.active()));
    }

    private void layoutStepRow(int base, int y) {
        int btnW = 38, btnH = 16, gap = 4;
        int total = btnW * STEPS.length + gap * (STEPS.length - 1);
        int start = panelLeft + (panelWidth - total) / 2;
        for (int i = 0; i < STEPS.length; i++) {
            buttonList
                .add(new Buttons.StepButton(base + i, start + i * (btnW + gap), y, btnW, btnH, LABELS[i], STEPS[i]));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        boolean changed = true;
        if (button.id >= BTN_IN_BASE && button.id < BTN_IN_BASE + STEPS.length) {
            tile.setInputHz(tile.inputHz() + STEPS[button.id - BTN_IN_BASE]);
        } else if (button.id >= BTN_OUT_BASE && button.id < BTN_OUT_BASE + STEPS.length) {
            tile.setOutputHz(tile.outputHz() + STEPS[button.id - BTN_OUT_BASE]);
        } else if (button.id == BTN_MOD) {
            Modulation[] all = Modulation.values();
            Modulation next = all[(tile.modulation()
                .ordinal() + 1) % all.length];
            tile.setModulation(next);
            button.displayString = "Mode: " + next.name();
        } else if (button.id == BTN_PWR_DEC) {
            tile.setTxPowerW(previousPower(tile.txPowerW()));
        } else if (button.id == BTN_PWR_INC) {
            tile.setTxPowerW(nextPower(tile.txPowerW()));
        } else if (button.id == BTN_ACTIVE && button instanceof Buttons.ToggleButton) {
            Buttons.ToggleButton tb = (Buttons.ToggleButton) button;
            tb.setState(!tb.state);
            tile.setActive(tb.state);
        } else {
            changed = false;
        }
        if (changed) {
            NetworkHandler.CHANNEL.sendToServer(
                new PacketRelaySettings(
                    tileDim,
                    tileX,
                    tileY,
                    tileZ,
                    tile.inputHz(),
                    tile.outputHz(),
                    tile.modulation()
                        .code(),
                    tile.txPowerW(),
                    tile.active()));
        }
    }

    private static float nextPower(float current) {
        for (float p : POWER_STEPS) if (p > current + 0.001f) return p;
        return POWER_STEPS[POWER_STEPS.length - 1];
    }

    private static float previousPower(float current) {
        float prev = POWER_STEPS[0];
        for (float p : POWER_STEPS) {
            if (p < current - 0.001f) prev = p;
            else break;
        }
        return prev;
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        drawString(fontRendererObj, "Listen", panelLeft + 18, panelTop + 30, COL_LABEL);
        drawLcdDisplay(
            panelLeft + 70,
            panelTop + 26,
            panelWidth - 88,
            18,
            String.format("%9.4f MHz", tile.inputHz() / 1.0e6));
        drawString(fontRendererObj, "Send", panelLeft + 18, panelTop + 78, COL_LABEL);
        drawLcdDisplay(
            panelLeft + 70,
            panelTop + 74,
            panelWidth - 88,
            18,
            String.format("%9.4f MHz", tile.outputHz() / 1.0e6));
        String info = tile.modulation()
            .name() + "   "
            + tile.txPowerW()
            + " W   "
            + (tile.active() ? "ACTIVE" : "STANDBY");
        drawCenteredString(
            fontRendererObj,
            info,
            panelLeft + panelWidth / 2,
            panelTop + panelHeight - 16,
            tile.active() ? COL_SAFE : COL_LABEL);
    }
}
