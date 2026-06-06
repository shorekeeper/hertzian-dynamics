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
        this.panelWidth = 264;
        this.panelHeight = 200;
    }

    @Override
    protected void layoutWidgets() {
        // Activity toggle + profile switch in a row.
        int gap = 10;
        int toggleW = 80;
        int profileW = 110;

        int total = toggleW + gap + profileW;
        int startX = panelLeft + (panelWidth - total) / 2;
        int y = panelTop + 100;

        // Toggle
        buttonList.add(new Buttons.ToggleButton(BTN_TOGGLE, startX, y, toggleW, 20, "JAMMING", "OFF", tile.active()));

        // Profile
        buttonList.add(
            new GuiButton(BTN_PROFILE, startX + toggleW + gap, y, profileW, 20, "Profile: " + tile.jamProfileLabel()));

        // Frequency step row.
        double[] steps = { -1_000_000, -100_000, -12_500, 12_500, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-12.5k", "+12.5k", "+100k", "+1M" };
        int btnW = 36, btnH = 16;
        gap = 4;
        total = btnW * steps.length + gap * (steps.length - 1);
        int row = panelTop + 70;
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
            double delta = ((Buttons.StepButton) button).deltaHz;
            if (isShiftKeyDown()) delta *= 10.0;
            tile.setCarrierHz(tile.carrierHz() + delta);
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
        fontRendererObj.drawSplitString(
            "Active jammer drowns nearby receivers tuned to its band.",
            panelLeft + 8,
            panelTop + panelHeight - 28,
            panelWidth - 16,
            COL_LABEL);
    }
}
