package io.hertzian.dynamics.gui;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketRttyControl;
import io.hertzian.dynamics.tile.TileRttyTerminal;

/**
 * RTTY teleprinter GUI. The terminal is a two-way text mode, so the panel
 * is laid out as a teleprinter: a frequency display and tuning bank along
 * the top, a one-line message field the operator types into, the transmit
 * and clear controls, and a large scrolling window at the bottom that
 * shows the decoded incoming text.
 *
 * <p>
 * Tuning. The six step buttons carry their delta in
 * {@link ScopeButton#deltaHz}. Holding shift while pressing one multiplies
 * the delta by ten, the same fast-tune convention the receiver and
 * transmitter panels use; the modifier is read at press time through
 * {@link net.minecraft.client.gui.GuiScreen#isShiftKeyDown()} because the
 * 1.7.10 button dispatch does not carry it into
 * {@link #actionPerformed(GuiButton)}.
 *
 * <p>
 * Data flow. Every control turns into a {@link PacketRttyControl} sent
 * to the server, which owns the codec; the decoded text and the signal
 * meter come back through {@link io.hertzian.dynamics.net.PacketRttyData}
 * and are read here from the tile's client-side mirror. A sent message has
 * a carriage return and newline appended so the far end's decoder flushes
 * the line and the local echo, once it is received back, lands on its own
 * row.
 */
public final class GuiRttyTerminal extends HertzianGui {

    private final TileRttyTerminal tile;
    private final int tileX;
    private final int tileY;
    private final int tileZ;
    private final int tileDim;

    /** Base id of the six frequency step buttons; ids run base..base+5. */
    private static final int BTN_STEP_BASE = 100;

    private static final int BTN_SEND = 200;
    private static final int BTN_CLEAR_TX = 201;
    private static final int BTN_CLEAR_RX = 202;

    /** The single-line message entry the operator types into. */
    private GuiTextField field;

    public GuiRttyTerminal(TileRttyTerminal tile) {
        super("RTTY Terminal", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 288;
        this.panelHeight = 244;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.field = new GuiTextField(fontRendererObj, panelLeft + 18, panelTop + 90, panelWidth - 36, 16);
        this.field.setMaxStringLength(120);
        this.field.setFocused(true);
    }

    @Override
    protected void layoutWidgets() {
        final int pl = panelLeft;
        final int pt = panelTop;
        final int pw = panelWidth;

        // Frequency step bank. The two small inner steps are 100 Hz, fine
        // enough to walk onto an RTTY signal whose mark tone is only a few
        // hundred hertz wide.
        final double[] steps = { -1_000_000, -100_000, -100, 100, 100_000, 1_000_000 };
        final String[] labels = { "-1M", "-100k", "-100", "+100", "+100k", "+1M" };
        final int btnW = 42;
        final int gap = 4;
        final int total = 6 * btnW + 5 * gap;
        final int start = pl + (pw - total) / 2;
        for (int i = 0; i < 6; i++) {
            buttonList.add(
                ScopeButton.step(BTN_STEP_BASE + i, start + i * (btnW + gap), pt + 54, btnW, 14, labels[i], steps[i]));
        }

        buttonList.add(new ScopeButton(BTN_SEND, pl + 18, pt + 110, 70, 16, "Send"));
        buttonList.add(new ScopeButton(BTN_CLEAR_TX, pl + 92, pt + 110, 70, 16, "Stop TX"));
        buttonList.add(new ScopeButton(BTN_CLEAR_RX, pl + pw - 80, pt + 110, 62, 16, "Clear"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        // Frequency step buttons carry a non-zero delta; shift makes the
        // step ten times larger.
        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            double delta = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) {
                delta *= 10.0;
            }
            tile.setTunedHz(tile.tunedHz() + delta);
            send(PacketRttyControl.TUNE, "");
            return;
        }

        switch (button.id) {
            case BTN_SEND: {
                String message = field.getText();
                if (!message.isEmpty()) {
                    // Append CR/LF so the far decoder flushes the line.
                    send(PacketRttyControl.SEND, message + "\r\n");
                    field.setText("");
                }
                break;
            }
            case BTN_CLEAR_TX:
                send(PacketRttyControl.CLEAR_TX, "");
                break;
            case BTN_CLEAR_RX:
                // Clear the local mirror immediately so the window blanks
                // without waiting for the next status packet.
                tile.clearRx();
                send(PacketRttyControl.CLEAR_RX, "");
                break;
            default:
                break;
        }
    }

    /**
     * Send one control packet to the server with the current tuning and
     * the given action and text payload.
     */
    private void send(int action, String text) {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketRttyControl(tileDim, tileX, tileY, tileZ, action, tile.tunedHz(), tile.bandwidthHz(), text));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (field != null) {
            field.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        // Enter sends the line. The button list order from layoutWidgets
        // places the six step buttons first, so the Send button sits at
        // index six.
        if (field != null && field.isFocused()) {
            if (key == org.lwjgl.input.Keyboard.KEY_RETURN) {
                actionPerformed((GuiButton) buttonList.get(6));
                return;
            }
            if (field.textboxKeyTyped(ch, key)) {
                return;
            }
        }
        super.keyTyped(ch, key);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (field != null) {
            field.updateCursorCounter();
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        final int pl = panelLeft;
        final int pt = panelTop;
        final int pw = panelWidth;

        drawLcdDisplay(pl + 18, pt + 28, pw - 36, 24, String.format("%10.4f kHz", tile.tunedHz() / 1.0e3));

        // Mode line plus a signal bar. The bar maps the synced signal to
        // noise ratio from a usable -10..60 dB window onto its width.
        final float snr = tile.clientSnrDb();
        final boolean transmitting = tile.clientSending();
        drawString(fontRendererObj, "RTTY 45 baud  " + (transmitting ? "TX" : "RX"), pl + 18, pt + 76, COL_LABEL);

        float level = (snr + 10f) / 70f;
        if (level < 0f) {
            level = 0f;
        } else if (level > 1f) {
            level = 1f;
        }
        drawHorizontalBar(pl + 130, pt + 75, pw - 148, 8, level, level > 0.5f ? COL_SAFE : COL_DANGER);

        drawString(fontRendererObj, "Send", pl + 18, pt + 78, COL_LABEL);
        field.drawTextBox();

        // Decoded text window, word-wrapped and scrolled to the latest
        // line.
        final int tx0 = pl + 18;
        final int ty0 = pt + 132;
        final int tx1 = pl + pw - 18;
        final int ty1 = pt + panelHeight - 14;
        drawRect(tx0 - 1, ty0 - 1, tx1 + 1, ty1 + 1, COL_PANEL_BORDER);
        drawRect(tx0, ty0, tx1, ty1, COL_LCD_BG);

        final String text = tile.clientText();
        if (text != null && !text.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> lines = fontRendererObj.listFormattedStringToWidth(text, (tx1 - tx0) - 8);
            final int lineHeight = 10;
            final int maxLines = (ty1 - ty0 - 6) / lineHeight;
            final int from = Math.max(0, lines.size() - maxLines);
            int y = ty0 + 4;
            for (int i = from; i < lines.size(); i++) {
                fontRendererObj.drawString(lines.get(i), tx0 + 4, y, COL_LCD_TEXT);
                y += lineHeight;
            }
        } else {
            drawString(fontRendererObj, "Waiting for signal...", tx0 + 4, ty0 + 4, COL_LABEL);
        }
    }
}
