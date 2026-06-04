package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import io.hertzian.dynamics.core.MorseEncoder;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketTelegraphKey;
import io.hertzian.dynamics.tile.TileTelegraphKey;

/**
 * Telegraph key GUI: type a message and send it, or hand-key with the
 * straight key pad. Built on the scope widgets like the rest of the
 * panels.
 *
 * <p>
 * Layout
 * <ul>
 * <li>A text field for the message, with a live dot/dash preview of
 * what will go on air.</li>
 * <li>Send and Clear buttons, and a WPM readout with minus/plus.</li>
 * <li>A large straight key pad. Press and hold the mouse over it to put
 * a carrier on air, release to drop it. The press and release are
 * sent as separate events so the carrier follows the mouse the way a
 * real key follows the hand. Closing the GUI while the key is held
 * sends a release so the carrier never sticks on.</li>
 * </ul>
 *
 * <p>
 * The key has no decoder of its own; to read what was sent, point a
 * Telegraph Receiver at the same frequency. The transmitter the key feeds
 * must be in range and not in station mode; the key switches its
 * modulation to CW automatically.
 */
public final class GuiTelegraphKey extends HertzianGui {

    private final TileTelegraphKey tile;
    private final int tileX, tileY, tileZ, tileDim;

    private static final int BTN_SEND = 100;
    private static final int BTN_CLEAR = 101;
    private static final int BTN_WPM_DEC = 102;
    private static final int BTN_WPM_INC = 103;

    private GuiTextField messageField;
    private int wpm;

    // Straight key pad bounds and held state.
    private int padX0, padY0, padX1, padY1;
    private boolean keyHeld = false;

    public GuiTelegraphKey(TileTelegraphKey tile) {
        super("Telegraph Key", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.wpm = tile.wpm();
        this.panelWidth = 270;
        this.panelHeight = 224;
    }

    @Override
    public void initGui() {
        super.initGui();
        messageField = new GuiTextField(fontRendererObj, panelLeft + 18, panelTop + 40, panelWidth - 36, 16);
        messageField.setMaxStringLength(64);
        messageField.setFocused(true);
    }

    @Override
    protected void layoutWidgets() {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;
        buttonList.add(new ScopeButton(BTN_SEND, pl + 18, pt + 84, 70, 16, "Send"));
        buttonList.add(new ScopeButton(BTN_CLEAR, pl + 92, pt + 84, 60, 16, "Clear"));
        buttonList.add(new ScopeButton(BTN_WPM_DEC, pl + pw - 58, pt + 84, 18, 16, "-"));
        buttonList.add(new ScopeButton(BTN_WPM_INC, pl + pw - 26, pt + 84, 18, 16, "+"));

        // Straight key pad occupies the lower band.
        padX0 = pl + 18;
        padY0 = pt + 128;
        padX1 = pl + pw - 18;
        padY1 = pt + panelHeight - 28;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BTN_SEND: {
                String text = messageField.getText();
                if (text != null && !text.isEmpty()) {
                    NetworkHandler.CHANNEL.sendToServer(
                        new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.SEND, wpm, text));
                }
                break;
            }
            case BTN_CLEAR:
                NetworkHandler.CHANNEL.sendToServer(
                    new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.CLEAR, 0, ""));
                break;
            case BTN_WPM_DEC:
                wpm = Math.max(5, wpm - 1);
                tile.setWpm(wpm);
                NetworkHandler.CHANNEL.sendToServer(
                    new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.SET_WPM, wpm, ""));
                break;
            case BTN_WPM_INC:
                wpm = Math.min(40, wpm + 1);
                tile.setWpm(wpm);
                NetworkHandler.CHANNEL.sendToServer(
                    new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.SET_WPM, wpm, ""));
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        super.mouseClicked(mx, my, btn);
        if (messageField != null) messageField.mouseClicked(mx, my, btn);
        if (btn == 0 && inPad(mx, my)) {
            keyHeld = true;
            NetworkHandler.CHANNEL
                .sendToServer(new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.KEY_DOWN, 0, ""));
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        super.mouseMovedOrUp(mx, my, which);
        if (which == 0 && keyHeld) {
            keyHeld = false;
            NetworkHandler.CHANNEL
                .sendToServer(new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.KEY_UP, 0, ""));
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (messageField != null && messageField.isFocused()) {
            if (key == org.lwjgl.input.Keyboard.KEY_RETURN) {
                actionPerformed((GuiButton) buttonList.get(0)); // Send.
                return;
            }
            if (messageField.textboxKeyTyped(ch, key)) return;
        }
        super.keyTyped(ch, key);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (messageField != null) messageField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // Never leave the carrier stuck on if the GUI closes mid-press.
        if (keyHeld) {
            keyHeld = false;
            NetworkHandler.CHANNEL
                .sendToServer(new PacketTelegraphKey(tileDim, tileX, tileY, tileZ, PacketTelegraphKey.KEY_UP, 0, ""));
        }
    }

    private boolean inPad(int mx, int my) {
        return mx >= padX0 && mx < padX1 && my >= padY0 && my < padY1;
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        drawString(fontRendererObj, "Message", pl + 18, pt + 30, COL_LABEL);
        if (messageField != null) messageField.drawTextBox();

        // Dot/dash preview of the typed message.
        String preview = MorseEncoder.toMorseString(messageField == null ? "" : messageField.getText());
        String shown = preview.isEmpty() ? "(type a message)" : preview;
        if (fontRendererObj.getStringWidth(shown) > pw - 36) {
            shown = fontRendererObj.trimStringToWidth(shown, pw - 42) + "...";
        }
        drawString(fontRendererObj, shown, pl + 18, pt + 64, COL_LCD_TEXT);

        // WPM readout between the controls.
        String wpmStr = wpm + " WPM";
        drawString(fontRendererObj, wpmStr, pl + pw - 60 - fontRendererObj.getStringWidth(wpmStr), pt + 88, COL_LABEL);

        // Straight key pad. Lights up while held.
        boolean down = keyHeld;
        int fill = down ? ScopeButton.COL_HOVER : ScopeButton.COL_BG;
        drawRect(padX0 - 1, padY0 - 1, padX1 + 1, padY1 + 1, COL_PANEL_BORDER);
        drawRect(padX0, padY0, padX1, padY1, fill);
        int cy = (padY0 + padY1) / 2 - 4;
        drawCenteredString(
            fontRendererObj,
            down ? "KEY DOWN" : "Hold to key",
            (padX0 + padX1) / 2,
            cy,
            down ? ScopeButton.COL_TEXT_ON : COL_LABEL);

        // Status line.
        String status = tile.sending() ? "Sending message..." : (down ? "On air" : "Idle");
        drawString(fontRendererObj, status, pl + 18, pt + panelHeight - 22, COL_LABEL);
        drawString(
            fontRendererObj,
            "Point a Telegraph Receiver at the same frequency to read.",
            pl + 18,
            pt + panelHeight - 12,
            COL_LABEL);
    }
}
