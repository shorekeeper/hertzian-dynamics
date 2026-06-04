package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;

import io.hertzian.dynamics.gui.widget.Buttons;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketJammerSettings;
import io.hertzian.dynamics.tile.TileJammer;

/**
 * Jammer GUI. Shows active state, carrier frequency, bandwidth,
 * jam profile (continuous / pulsed). Frequency steps mirror the
 * transmitter so a jammer operator can target a known TX exactly.
 */
public final class GuiJammer extends HertzianGui {

    private final TileJammer tile;
    private final int tileX, tileY, tileZ, tileDim;

    private static final int BTN_TOGGLE = 100;
    private static final int BTN_PROFILE = 101;
    private static final int BTN_STEPS_BASE = 200;

    public GuiJammer(TileJammer tile) {
        super("Wideband Jammer", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 240;
        this.panelHeight = 200;
    }

    @Override
    protected void layoutWidgets() {
        // Activity toggle + profile switch in a row.
        buttonList.add(
            new Buttons.ToggleButton(
                BTN_TOGGLE,
                panelLeft + 20,
                panelTop + 100,
                80,
                20,
                "JAMMING",
                "OFF",
                tile.active()));
        buttonList.add(
            new GuiButton(BTN_PROFILE, panelLeft + 110, panelTop + 100, 110, 20, "Profile: " + tile.jamProfileLabel()));

        // Frequency step row.
        double[] steps = { -1_000_000, -100_000, -12_500, 12_500, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-12.5k", "+12.5k", "+100k", "+1M" };
        int btnW = 36, btnH = 16, gap = 4;
        int row = panelTop + 70;
        int total = btnW * steps.length + gap * (steps.length - 1);
        int start = panelLeft + (panelWidth - total) / 2;
        for (int i = 0; i < steps.length; i++) {
            buttonList.add(
                new Buttons.StepButton(
                    BTN_STEPS_BASE + i,
                    start + i * (btnW + gap),
                    row,
                    btnW,
                    btnH,
                    labels[i],
                    steps[i]));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_TOGGLE && button instanceof Buttons.ToggleButton) {
            Buttons.ToggleButton tb = (Buttons.ToggleButton) button;
            tb.setState(!tb.state);
            tile.setActive(tb.state);
            sendUpdate();
        } else if (button.id == BTN_PROFILE) {
            int next = (tile.jamProfile() % 2) + 1;
            tile.setJamProfile(next);
            button.displayString = "Profile: " + tile.jamProfileLabel();
            sendUpdate();
        } else if (button instanceof Buttons.StepButton) {
            tile.setCarrierHz(tile.carrierHz() + ((Buttons.StepButton) button).deltaHz);
            sendUpdate();
        }
    }

    private void sendUpdate() {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketJammerSettings(
                tileDim,
                tileX,
                tileY,
                tileZ,
                tile.active(),
                tile.carrierHz(),
                tile.bandwidthHz(),
                tile.jamProfile()));
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        String freq = String.format("%10.4f MHz", tile.carrierHz() / 1.0e6);
        drawLcdDisplay(panelLeft + 20, panelTop + 30, panelWidth - 40, 30, freq);
        String info = (tile.active() ? "ACTIVE" : "STANDBY") + "  |  BW " + (int) tile.bandwidthHz() + " Hz";
        drawCenteredString(
            fontRendererObj,
            info,
            panelLeft + panelWidth / 2,
            panelTop + 62,
            tile.active() ? COL_DANGER : COL_LABEL);
        drawString(
            fontRendererObj,
            "Active jammer drowns nearby receivers tuned to its band.",
            panelLeft + 8,
            panelTop + panelHeight - 16,
            COL_LABEL);
    }
}
