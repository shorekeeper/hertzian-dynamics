package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import io.hertzian.dynamics.audio.ClientAudioBridge;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.gui.widget.Slider;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.item.RadioModel;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketHandheldSettings;

/**
 * Front panel of the Handheld Radio, laid out as a portable transceiver.
 * The controls map onto what a real handheld has on its case:
 *
 * <ul>
 * <li>A power switch. The whole rig is dead until it is on.</li>
 * <li>A frequency display with a tuning bank, the same Shift for times
 * ten step convention the other panels use. Tuning is clamped to the
 * model band and snapped to the channel grid for fixed-grid sets.</li>
 * <li>A mode selector and a transmit power step, both limited to what
 * the model supports: the mode button cycles only the model's
 * modulations, the power buttons step the model's power table.</li>
 * <li>A volume control and a squelch control. Squelch keeps the speaker
 * silent until a signal of sufficient strength arrives; a monitor
 * button forces it open to listen for a weak station by ear.</li>
 * <li>A roger beep toggle. When on, the radio sends a short courtesy
 * tone at the end of each transmission. Models whose beep is not
 * removable show it locked on.</li>
 * <li>A sidetone toggle. When on, the operator hears their own audio
 * while keyed; when off the set is silent to the operator while
 * transmitting, the plain half-duplex behaviour.</li>
 * <li>A small receive scope and an RX lamp driven by the real audio the
 * client is playing for this radio.</li>
 * </ul>
 *
 * <p>
 * Every change is written into the held stack NBT and shipped to the
 * server through {@link PacketHandheldSettings}. The server side registry
 * reads the stack each tick and retunes its rf-core slots when the tuning
 * actually changed, which is what makes the set respond rather than stay
 * stuck on its previous frequency.
 */
public final class GuiHandheldRadio extends HertzianGui {

    private final EntityPlayer player;
    private final String voiceKey;

    private static final int BTN_STEP_BASE = 100;
    private static final int BTN_POWER = 200;
    private static final int BTN_MOD = 201;
    private static final int BTN_PWR_DEC = 202;
    private static final int BTN_PWR_INC = 203;
    private static final int BTN_MONITOR = 204;
    private static final int BTN_ROGER = 205;
    private static final int BTN_SELFMON = 206;
    private static final int SLD_VOLUME = 210;
    private static final int SLD_SQUELCH = 211;

    private ScopeButton powerButton;
    private ScopeButton monitorButton;
    private ScopeButton rogerButton;
    private ScopeButton selfMonButton;
    private Slider volumeSlider;
    private Slider squelchSlider;

    public GuiHandheldRadio(EntityPlayer player) {
        super("Handheld Radio", "Portable transceiver");
        this.player = player;
        this.voiceKey = ClientAudioBridge.handheldKey(player.getUniqueID());
        this.panelWidth = 248;
        this.panelHeight = 300;
    }

    private ItemStack stack() {
        return player.getCurrentEquippedItem();
    }

    @Override
    protected void layoutWidgets() {
        ItemStack s = stack();
        int pl = panelLeft;
        int pt = panelTop;
        int pw = panelWidth;

        boolean powered = s != null && ItemHandheldRadio.isPowered(s);
        powerButton = ScopeButton.toggle(BTN_POWER, pl + 18, pt + 28, 60, 16, "ON", "OFF", powered);
        buttonList.add(powerButton);

        // Tuning bank.
        double[] steps = { -1_000_000, -100_000, -12_500, 12_500, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-12.5k", "+12.5k", "+100k", "+1M" };
        int btnW = 36, gap = 4;
        int total = btnW * steps.length + gap * (steps.length - 1);
        int start = pl + (pw - total) / 2;
        for (int i = 0; i < steps.length; i++) {
            buttonList.add(
                ScopeButton.step(BTN_STEP_BASE + i, start + i * (btnW + gap), pt + 92, btnW, 14, labels[i], steps[i]));
        }

        String modLabel = s != null ? ItemHandheldRadio.modulation(s)
            .name() : "NARROW_FM";
        buttonList.add(new ScopeButton(BTN_MOD, pl + 18, pt + 112, 110, 16, "Mode: " + modLabel));
        buttonList.add(new ScopeButton(BTN_PWR_DEC, pl + 132, pt + 112, 50, 16, "- Pwr"));
        buttonList.add(new ScopeButton(BTN_PWR_INC, pl + 184, pt + 112, 46, 16, "+ Pwr"));

        // Receive controls.
        volumeSlider = new Slider(
            SLD_VOLUME,
            pl + 18,
            pt + 156,
            pw - 36,
            16,
            "Vol",
            s != null ? ItemHandheldRadio.volume(s) : 0.8f);
        buttonList.add(volumeSlider);

        squelchSlider = new Slider(
            SLD_SQUELCH,
            pl + 18,
            pt + 178,
            pw - 36,
            16,
            "Squelch",
            s != null ? ItemHandheldRadio.squelch(s) : 0.25f);
        buttonList.add(squelchSlider);

        boolean mon = s != null && ItemHandheldRadio.monitor(s);
        monitorButton = ScopeButton.toggle(BTN_MONITOR, pl + 18, pt + 200, 100, 16, "Monitor: ON", "Monitor", mon);
        buttonList.add(monitorButton);

        boolean roger = s != null && ItemHandheldRadio.rogerBeep(s);
        rogerButton = ScopeButton.toggle(BTN_ROGER, pl + 124, pt + 200, pw - 124 - 18, 16, "Roger: ON", "Roger", roger);
        buttonList.add(rogerButton);

        boolean self = s != null && ItemHandheldRadio.selfMonitor(s);
        selfMonButton = ScopeButton.toggle(BTN_SELFMON, pl + 18, pt + 222, 130, 16, "Sidetone: ON", "Sidetone", self);
        buttonList.add(selfMonButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ItemStack s = stack();
        if (s == null) return;
        RadioModel model = ItemHandheldRadio.model(s);

        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            double delta = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) delta *= 10.0;
            ItemHandheldRadio.setTunedHz(s, ItemHandheldRadio.tunedHz(s) + delta);
            send(s);
            return;
        }

        switch (button.id) {
            case BTN_POWER: {
                boolean now = !powerButton.state();
                powerButton.setState(now);
                ItemHandheldRadio.setPowered(s, now);
                send(s);
                break;
            }
            case BTN_MOD: {
                Modulation next = model.nextModulation(ItemHandheldRadio.modulation(s));
                ItemHandheldRadio.setModulation(s, next);
                button.displayString = "Mode: " + next.name();
                send(s);
                break;
            }
            case BTN_PWR_DEC:
                ItemHandheldRadio.setTxPowerW(s, model.previousPower(ItemHandheldRadio.txPowerW(s)));
                send(s);
                break;
            case BTN_PWR_INC:
                ItemHandheldRadio.setTxPowerW(s, model.nextPower(ItemHandheldRadio.txPowerW(s)));
                send(s);
                break;
            case BTN_MONITOR: {
                boolean now = !monitorButton.state();
                monitorButton.setState(now);
                ItemHandheldRadio.setMonitor(s, now);
                send(s);
                break;
            }
            case BTN_ROGER: {
                boolean now = !rogerButton.state();
                rogerButton.setState(now);
                ItemHandheldRadio.setRogerBeep(s, now);
                // The model may refuse the change; reflect the real state.
                rogerButton.setState(ItemHandheldRadio.rogerBeep(s));
                send(s);
                break;
            }
            case BTN_SELFMON: {
                boolean now = !selfMonButton.state();
                selfMonButton.setState(now);
                ItemHandheldRadio.setSelfMonitor(s, now);
                send(s);
                break;
            }
            default:
                break;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (which != 0) return;
        ItemStack s = stack();
        if (s == null) return;
        boolean changed = false;
        if (volumeSlider != null && Math.abs(volumeSlider.value() - ItemHandheldRadio.volume(s)) > 0.001f) {
            ItemHandheldRadio.setVolume(s, volumeSlider.value());
            changed = true;
        }
        if (squelchSlider != null && Math.abs(squelchSlider.value() - ItemHandheldRadio.squelch(s)) > 0.001f) {
            ItemHandheldRadio.setSquelch(s, squelchSlider.value());
            changed = true;
        }
        if (changed) send(s);
    }

    private void send(ItemStack s) {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketHandheldSettings(
                ItemHandheldRadio.isPowered(s),
                ItemHandheldRadio.tunedHz(s),
                ItemHandheldRadio.bandwidthHz(s),
                ItemHandheldRadio.txPowerW(s),
                ItemHandheldRadio.modulation(s)
                    .code(),
                ItemHandheldRadio.volume(s),
                ItemHandheldRadio.squelch(s),
                ItemHandheldRadio.monitor(s),
                ItemHandheldRadio.rogerBeep(s),
                ItemHandheldRadio.selfMonitor(s)));
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        ItemStack s = stack();
        int pl = panelLeft;
        int pt = panelTop;
        int pw = panelWidth;

        if (s == null) {
            drawCenteredString(fontRendererObj, "Radio missing", pl + pw / 2, pt + 60, COL_DANGER);
            return;
        }

        RadioModel model = ItemHandheldRadio.model(s);
        boolean powered = ItemHandheldRadio.isPowered(s);

        // Model name and tier.
        drawCenteredString(
            fontRendererObj,
            model.displayName() + "  (T" + model.tier() + ")",
            pl + pw / 2,
            pt + 16,
            COL_LCD_TEXT);

        // Frequency LCD. Dim when the set is off.
        String freq = String.format("%10.4f MHz", ItemHandheldRadio.tunedHz(s) / 1.0e6);
        drawLcdDisplay(pl + 86, pt + 26, pw - 104, 22, powered ? freq : "-- OFF --");

        // Mode, power and RX lamp.
        String info = ItemHandheldRadio.modulation(s)
            .name() + "   "
            + ItemHandheldRadio.txPowerW(s)
            + " W";
        drawString(fontRendererObj, info, pl + 18, pt + 56, COL_LABEL);

        float[] wave = ClientAudioBridge.latestWaveform(voiceKey);
        boolean rx = powered && hasEnergy(wave);
        drawLed(pl + pw - 60, pt + 55, "RX", rx, COL_SAFE);

        // Receive scope from the real played audio.
        drawScope(pl + 18, pt + 70, pw - 36, 18, powered ? wave : null);

        // Hints.
        drawString(fontRendererObj, "Shift + step = x10", pl + 18, pt + 244, COL_LABEL);
        drawString(
            fontRendererObj,
            "Hold V to talk. Sneak + right-click in world to toggle power.",
            pl + 18,
            pt + panelHeight - 14,
            COL_LABEL);
    }

    private static boolean hasEnergy(float[] wave) {
        if (wave == null) return false;
        for (float v : wave) {
            if (Math.abs(v) > 0.02f) return true;
        }
        return false;
    }

    private void drawScope(int x, int y, int w, int h, float[] wave) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, COL_PANEL_BORDER);
        drawRect(x, y, x + w, y + h, COL_LCD_BG);
        int mid = y + h / 2;
        if (wave == null || wave.length == 0) {
            drawRect(x, mid, x + w, mid + 1, COL_TRACE);
            return;
        }
        int amp = (h / 2) - 2;
        int prevY = mid - (int) (clamp(wave[0]) * amp);
        for (int i = 1; i < wave.length; i++) {
            int cx = x + i * w / wave.length;
            int cy = mid - (int) (clamp(wave[i]) * amp);
            int y0 = Math.min(prevY, cy);
            int y1 = Math.max(prevY, cy);
            drawRect(cx, y0, cx + 1, y1 + 1, COL_TRACE);
            prevY = cy;
        }
    }

    private void drawLed(int x, int y, String label, boolean on, int onColour) {
        int col = on ? onColour : 0xFF1A2A1A;
        drawRect(x, y, x + 8, y + 8, col);
        drawRect(x, y, x + 8, y + 1, COL_PANEL_BORDER);
        drawRect(x, y + 7, x + 8, y + 8, COL_PANEL_BORDER);
        drawRect(x, y, x + 1, y + 8, COL_PANEL_BORDER);
        drawRect(x + 7, y, x + 8, y + 8, COL_PANEL_BORDER);
        drawString(fontRendererObj, label, x + 10, y, on ? onColour : COL_LABEL);
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }
}
