package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import io.hertzian.dynamics.core.DtmfCodec;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketDtmfControl;
import io.hertzian.dynamics.tile.TileDtmfPad;

/**
 * DTMF keypad and selcall GUI. The panel mirrors a telephone or fleet
 * radio handset: a frequency display and tuning bank at the top, the four
 * by four tone keypad in the middle, a received-digit readout with a
 * selcall indicator, and a code field at the bottom that arms the
 * selective-call match.
 *
 * <p>
 * Keypad. Each of the sixteen keys sends its character to the tile,
 * which queues the matching dual tone for the nearest transmitter. The
 * keys are laid from the {@link DtmfCodec#KEYS} table so the on-screen
 * layout and the tones stay in step if the table is ever changed.
 *
 * <p>
 * Selcall. The code field holds the digit string this station answers
 * to. Set Code stores it on the tile; when the trailing decoded digits
 * match, the tile raises a redstone output and this GUI lights the SELCALL
 * indicator. The match state is read from the tile's client mirror, fed by
 * {@link io.hertzian.dynamics.net.PacketDtmfData}.
 *
 * <p>
 * Tuning follows the shared shift-for-times-ten convention used by the
 * other panels.
 */
public final class GuiDtmfPad extends HertzianGui {

    private final TileDtmfPad tile;
    private final int tileX;
    private final int tileY;
    private final int tileZ;
    private final int tileDim;

    /** Base id of the six frequency step buttons. */
    private static final int BTN_STEP_BASE = 100;
    /** Base id of the sixteen keypad keys; ids run base..base+15, row-major. */
    private static final int BTN_KEY_BASE = 200;
    private static final int BTN_SET_CODE = 300;
    private static final int BTN_CLEAR = 301;

    /** Entry field for the selcall code this station answers to. */
    private GuiTextField codeField;

    public GuiDtmfPad(TileDtmfPad tile) {
        super("DTMF / Selcall", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 264;
        this.panelHeight = 268;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.codeField = new GuiTextField(fontRendererObj, panelLeft + 70, panelTop + panelHeight - 44, 90, 14);
        this.codeField.setMaxStringLength(16);
        this.codeField.setText(tile.selcallCode());
    }

    @Override
    protected void layoutWidgets() {
        final int pl = panelLeft;
        final int pt = panelTop;
        final int pw = panelWidth;

        // Frequency step bank.
        final double[] steps = { -1_000_000, -100_000, -5_000, 5_000, 100_000, 1_000_000 };
        final String[] labels = { "-1M", "-100k", "-5k", "+5k", "+100k", "+1M" };
        final int btnW = 38;
        final int gap = 4;
        final int total = 6 * btnW + 5 * gap;
        final int start = pl + (pw - total) / 2;
        for (int i = 0; i < 6; i++) {
            buttonList.add(
                ScopeButton.step(BTN_STEP_BASE + i, start + i * (btnW + gap), pt + 54, btnW, 14, labels[i], steps[i]));
        }

        // Four by four keypad. The layout is read straight from the codec
        // table so the buttons and the tones cannot drift apart.
        final int keyW = 40;
        final int keyGap = 6;
        final int keyStart = pl + (pw - (4 * keyW + 3 * keyGap)) / 2;
        final int keyTop = pt + 96;
        final int rowStep = 16 + 4;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                char key = DtmfCodec.KEYS[row][col];
                buttonList.add(
                    new ScopeButton(
                        BTN_KEY_BASE + row * 4 + col,
                        keyStart + col * (keyW + keyGap),
                        keyTop + row * rowStep,
                        keyW,
                        16,
                        String.valueOf(key)));
            }
        }

        buttonList.add(new ScopeButton(BTN_SET_CODE, pl + pw - 78, pt + panelHeight - 44, 60, 14, "Set Code"));
        buttonList.add(new ScopeButton(BTN_CLEAR, pl + 18, pt + panelHeight - 44, 48, 14, "Clear"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            double delta = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) {
                delta *= 10.0;
            }
            tile.setTunedHz(tile.tunedHz() + delta);
            send(PacketDtmfControl.TUNE, "");
            return;
        }

        if (button.id >= BTN_KEY_BASE && button.id < BTN_KEY_BASE + 16) {
            int index = button.id - BTN_KEY_BASE;
            char key = DtmfCodec.KEYS[index / 4][index % 4];
            tile.queueDigit(key);
            send(PacketDtmfControl.KEY, String.valueOf(key));
            return;
        }

        switch (button.id) {
            case BTN_SET_CODE:
                tile.setSelcallCode(codeField.getText());
                send(PacketDtmfControl.SET_CODE, codeField.getText());
                break;
            case BTN_CLEAR:
                tile.clearRx();
                send(PacketDtmfControl.CLEAR_RX, "");
                break;
            default:
                break;
        }
    }

    /** Send one control packet with the current tuning and the given action. */
    private void send(int action, String text) {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketDtmfControl(tileDim, tileX, tileY, tileZ, action, tile.tunedHz(), tile.bandwidthHz(), text));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (codeField != null) {
            codeField.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (codeField != null && codeField.isFocused() && codeField.textboxKeyTyped(ch, key)) {
            return;
        }
        super.keyTyped(ch, key);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (codeField != null) {
            codeField.updateCursorCounter();
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        final int pl = panelLeft;
        final int pt = panelTop;
        final int pw = panelWidth;

        drawLcdDisplay(pl + 18, pt + 28, pw - 36, 24, String.format("%10.4f kHz", tile.tunedHz() / 1.0e3));

        // Received digit tail.
        final String digits = tile.clientDigits();
        drawString(fontRendererObj, "RX: " + (digits.isEmpty() ? "----" : digits), pl + 18, pt + 76, COL_LCD_TEXT);

        // Selcall indicator: a small lamp plus a label, lit while the tile
        // reports a current match.
        final boolean selcall = tile.clientSelcall();
        final int lamp = selcall ? 0xFF44FF66 : 0xFF1A2A1A;
        drawRect(pl + pw - 60, pt + 75, pl + pw - 52, pt + 83, lamp);
        drawString(fontRendererObj, "SELCALL", pl + pw - 50, pt + 76, selcall ? 0xFF44FF66 : COL_LABEL);

        drawString(fontRendererObj, "Code", pl + 18, pt + panelHeight - 42, COL_LABEL);
        if (codeField != null) {
            codeField.drawTextBox();
        }
    }
}
