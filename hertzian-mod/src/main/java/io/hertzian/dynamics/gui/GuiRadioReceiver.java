package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;

import io.hertzian.dynamics.audio.ClientAudioBridge;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.gui.widget.Slider;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketReceiverSettings;
import io.hertzian.dynamics.net.PacketReceiverStorePreset;
import io.hertzian.dynamics.tile.TileRadioReceiver;

/**
 * Receiver GUI, laid out as a cross between a car head unit and an SDR
 * receiver.
 *
 * <ul>
 * <li><b>Audio oscilloscope.</b> A live waveform of the sound coming
 * out of the receiver, the kind of trace an SDR front-end draws. The
 * PCM already passes through {@link ClientAudioBridge} on its way to
 * OpenAL, so the GUI reads a decimated snapshot of it via
 * {@link ClientAudioBridge#latestWaveform} keyed by this block's
 * voice key. It is real received audio, not a synthetic
 * animation.</li>
 * <li><b>Analog tuning dial.</b> A sliding scale under the frequency
 * readout: the tick marks move beneath a fixed needle as the set
 * is tuned, the analog-radio idiom, with megahertz labels.</li>
 * <li><b>Preset memory bank P1..P6.</b> Car-radio memory buttons. A
 * plain click recalls the slot; Shift+click, or arming the Store
 * toggle and clicking, writes the current tuning into it. Presets
 * live on the tile (see {@link TileRadioReceiver}) and persist.</li>
 * <li><b>Seek.</b> The two SEEK buttons start a client-side scan up or
 * down the band that stops on the first strong signal, read from
 * the synced S/N. Any manual tuning cancels it.</li>
 * <li><b>Status LEDs.</b> TU lights on a usable signal, ST on a
 * wideband-FM signal strong enough to be "stereo", SC while a scan
 * is running.</li>
 * <li><b>S-meter and history.</b> A signal meter, a scrolling S/N
 * history graph, the decoded station name, the noise reduction and
 * volume controls, and the Shift = x10 tuning modifier shared with
 * the other panels.</li>
 * </ul>
 *
 * <p>
 * The live data arrives by packet because the receiver's mixed chunk
 * only exists on the server; see {@link PacketReceiverSettings} and the
 * scope packet for why.
 */
public final class GuiRadioReceiver extends HertzianGui {

    private final TileRadioReceiver tile;
    private final int tileX, tileY, tileZ;
    private final int tileDim;
    private final String voiceKey;

    private static final int BTN_STEP_BASE = 100;
    private static final int BTN_MOD = 200;
    private static final int BTN_BW_NARROW = 201;
    private static final int BTN_BW_WIDE = 202;
    private static final int BTN_NR = 250;
    private static final int BTN_VOLUME = 260;
    private static final int BTN_PRESET_BASE = 300;
    private static final int BTN_SEEK_DOWN = 310;
    private static final int BTN_SEEK_UP = 311;
    private static final int BTN_STORE = 312;

    private static final int LED_ON_TUNE = 0xFF44FF66;
    private static final int LED_ON_STEREO = 0xFF49E0FF;
    private static final int LED_ON_SCAN = 0xFFFFCC22;

    // Seek (auto-scan) parameters.
    private static final double SEEK_STEP_HZ = 50_000.0;
    private static final int SEEK_INTERVAL_TICKS = 5;
    private static final float SEEK_THRESHOLD_DB = 10f;

    private Slider volumeSlider;
    private ScopeButton storeButton;
    private final ScopeButton[] presetButtons = new ScopeButton[6];

    private boolean storeArmed = false;
    private boolean scanning = false;
    private int scanDir = 1;
    private int scanTick = 0;

    // Rolling S/N history for the scrolling graph, dB. Ring buffer.
    private static final int HISTORY = 96;
    private final float[] snrHistory = new float[HISTORY];
    private int historyHead = 0;
    private int historyTick = 0;

    public GuiRadioReceiver(TileRadioReceiver tile) {
        super("Radio Receiver", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.voiceKey = ClientAudioBridge.blockKey(tileDim, tileX, tileY, tileZ);
        this.panelWidth = 264;
        this.panelHeight = 292;
        for (int i = 0; i < HISTORY; i++) snrHistory[i] = -30f;
    }

    @Override
    protected void layoutWidgets() {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Preset memory bank.
        int pCount = 6, pW = 34, pGap = 4;
        int pTotal = pCount * pW + (pCount - 1) * pGap;
        int pStart = pl + (pw - pTotal) / 2;
        for (int i = 0; i < pCount; i++) {
            presetButtons[i] = new ScopeButton(
                BTN_PRESET_BASE + i,
                pStart + i * (pW + pGap),
                pt + 164,
                pW,
                14,
                "P" + (i + 1));
            buttonList.add(presetButtons[i]);
        }

        // Seek + store row.
        buttonList.add(new ScopeButton(BTN_SEEK_DOWN, pl + 18, pt + 182, 62, 16, "SEEK \u25C4\u25C4"));
        buttonList.add(new ScopeButton(BTN_SEEK_UP, pl + 82, pt + 182, 62, 16, "SEEK \u25BA\u25BA"));
        storeButton = ScopeButton
            .toggle(BTN_STORE, pl + 148, pt + 182, pw - 18 - (148), 16, "Store: ARM", "Store", storeArmed);
        buttonList.add(storeButton);

        // Tuning step buttons.
        double[] steps = { -1_000_000, -100_000, -12_500, 12_500, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-12.5k", "+12.5k", "+100k", "+1M" };
        int sW = 34, sGap = 4;
        int sTotal = 6 * sW + 5 * sGap;
        int sStart = pl + (pw - sTotal) / 2;
        for (int i = 0; i < steps.length; i++) {
            buttonList.add(
                ScopeButton.step(BTN_STEP_BASE + i, sStart + i * (sW + sGap), pt + 202, sW, 14, labels[i], steps[i]));
        }

        // Mode and bandwidth selectors.
        buttonList.add(
            new ScopeButton(
                BTN_MOD,
                pl + 18,
                pt + 220,
                110,
                16,
                "Mode: " + tile.modulation()
                    .name()));
        buttonList.add(new ScopeButton(BTN_BW_NARROW, pl + 132, pt + 220, 52, 16, "Narrow"));
        buttonList.add(new ScopeButton(BTN_BW_WIDE, pl + 188, pt + 220, 58, 16, "Wide"));

        // Noise reduction and volume.
        buttonList.add(new ScopeButton(BTN_NR, pl + 18, pt + 240, 90, 16, "NR: " + nrLabel()));
        volumeSlider = new Slider(BTN_VOLUME, pl + 112, pt + 240, pw - 18 - 112, 16, "Vol", tile.volume());
        buttonList.add(volumeSlider);
    }

    private String nrLabel() {
        float v = tile.noiseReduction();
        return v <= 0.01f ? "Off" : (v < 0.5f ? "Low" : (v < 0.99f ? "Med" : "Max"));
    }

    private void stopScan() {
        scanning = false;
        scanTick = 0;
    }

    private void toggleScan(int dir) {
        if (scanning && scanDir == dir) {
            stopScan();
        } else {
            scanning = true;
            scanDir = dir;
            scanTick = 0;
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // Client-side auto-scan: step the tuning every few ticks and stop
        // when the synced S/N rises above the threshold. A step per cycle
        // gives the scope packet time to refresh the S/N before the next
        // check, so the stop condition reads a settled value.
        if (!scanning) return;
        scanTick++;
        if (scanTick < SEEK_INTERVAL_TICKS) return;
        scanTick = 0;
        if (tile.clientSnrDb() >= SEEK_THRESHOLD_DB) {
            stopScan();
            return;
        }
        tile.setTunedHz(tile.tunedHz() + scanDir * SEEK_STEP_HZ);
        sendSettings();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_VOLUME) return; // committed on release.

        // Frequency steps, with Shift = x10. Cancels any scan.
        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            stopScan();
            double delta = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) delta *= 10.0;
            tile.setTunedHz(tile.tunedHz() + delta);
            sendSettings();
            return;
        }

        if (button.id == BTN_SEEK_DOWN) {
            toggleScan(-1);
            return;
        }
        if (button.id == BTN_SEEK_UP) {
            toggleScan(1);
            return;
        }

        if (button.id == BTN_STORE) {
            storeArmed = !storeArmed;
            storeButton.setState(storeArmed);
            return;
        }

        if (button.id >= BTN_PRESET_BASE && button.id < BTN_PRESET_BASE + tile.presetCount()) {
            handlePreset(button.id - BTN_PRESET_BASE);
            return;
        }

        boolean changed = false;
        if (button.id == BTN_NR) {
            float v = tile.noiseReduction();
            float next = v <= 0.01f ? 0.33f : (v < 0.5f ? 0.66f : (v < 0.99f ? 1f : 0f));
            tile.setNoiseReduction(next);
            button.displayString = "NR: " + nrLabel();
            changed = true;
        } else if (button.id == BTN_MOD) {
            stopScan();
            Modulation[] all = Modulation.values();
            Modulation next = all[(tile.modulation()
                .ordinal() + 1) % all.length];
            tile.setModulation(next);
            button.displayString = "Mode: " + next.name();
            changed = true;
        } else if (button.id == BTN_BW_NARROW) {
            tile.setBandwidthHz(15_000f);
            changed = true;
        } else if (button.id == BTN_BW_WIDE) {
            tile.setBandwidthHz(50_000f);
            changed = true;
        }
        if (changed) sendSettings();
    }

    /**
     * Recall or store a preset slot. Storing is gated by either the
     * armed Store toggle or a held Shift; recall is the plain action.
     */
    private void handlePreset(int idx) {
        boolean store = storeArmed || isShiftKeyDown();
        if (store) {
            double hz = tile.tunedHz();
            float bw = tile.bandwidthHz();
            int mod = tile.modulation()
                .code();
            tile.setPreset(idx, hz, bw, mod);
            NetworkHandler.CHANNEL
                .sendToServer(new PacketReceiverStorePreset(tileDim, tileX, tileY, tileZ, idx, hz, bw, mod));
            if (storeArmed) {
                storeArmed = false;
                storeButton.setState(false);
            }
            return;
        }
        if (!tile.hasPreset(idx)) return;
        stopScan();
        tile.setTunedHz(tile.presetHz(idx));
        tile.setBandwidthHz(tile.presetBw(idx));
        try {
            tile.setModulation(Modulation.fromCode(tile.presetMod(idx)));
        } catch (IllegalArgumentException ignore) {}
        // Refresh the mode button label to the recalled value.
        for (Object o : buttonList) {
            if (o instanceof GuiButton && ((GuiButton) o).id == BTN_MOD) {
                ((GuiButton) o).displayString = "Mode: " + tile.modulation()
                    .name();
            }
        }
        sendSettings();
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (which == 0 && volumeSlider != null) {
            float v = volumeSlider.value();
            if (Math.abs(v - tile.volume()) > 0.001f) {
                tile.setVolume(v);
                sendSettings();
            }
        }
    }

    private void sendSettings() {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketReceiverSettings(
                tileDim,
                tileX,
                tileY,
                tileZ,
                tile.tunedHz(),
                tile.bandwidthHz(),
                tile.modulation()
                    .code(),
                tile.noiseReduction(),
                tile.volume()));
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Sample the S/N a few times a second for the scrolling graph.
        historyTick++;
        if (historyTick >= 3) {
            historyTick = 0;
            snrHistory[historyHead] = tile.clientSnrDb();
            historyHead = (historyHead + 1) % HISTORY;
        }
        float snr = tile.clientSnrDb();

        // Frequency LCD.
        String freq = String.format("%10.4f MHz", tile.tunedHz() / 1.0e6);
        drawLcdDisplay(pl + 18, pt + 28, pw - 36, 24, freq);

        // Analog tuning dial.
        drawDial(pl + 18, pt + 54, pw - 36, 16);

        // Mode and bandwidth readout, with status LEDs on the right.
        String info = tile.modulation()
            .name() + "   BW "
            + (int) tile.bandwidthHz()
            + " Hz";
        drawString(fontRendererObj, info, pl + 18, pt + 74, COL_LABEL);
        boolean tuneOn = snr >= SEEK_THRESHOLD_DB;
        boolean stereoOn = tile.modulation() == Modulation.WIDE_FM && snr >= 18f;
        drawLed(pl + pw - 94, pt + 73, "TU", tuneOn, LED_ON_TUNE);
        drawLed(pl + pw - 62, pt + 73, "ST", stereoOn, LED_ON_STEREO);
        drawLed(pl + pw - 30, pt + 73, "SC", scanning, LED_ON_SCAN);

        // S-meter.
        float level = (snr + 10f) / 70f;
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        int barColour = level > 0.66f ? COL_SAFE : (level > 0.2f ? COL_TRACE : COL_DANGER);
        drawString(fontRendererObj, "Sig", pl + 18, pt + 88, COL_LABEL);
        drawHorizontalBar(pl + 40, pt + 86, 140, 8, level, barColour);
        String snrTxt = (snr <= -29f) ? "no sig" : String.format("%.0f dB", snr);
        drawString(fontRendererObj, snrTxt, pl + 188, pt + 87, COL_LABEL);

        // Station name.
        String station = tile.clientStationName();
        if (station != null && !station.isEmpty()) {
            drawCenteredString(fontRendererObj, "\u266A " + station, pl + pw / 2, pt + 100, COL_LCD_TEXT);
        }

        // Dual mini scopes: live audio waveform left, S/N history right.
        int half = (pw - 36 - 8) / 2;
        int sLeft = pl + 18;
        int hLeft = pl + 18 + half + 8;
        drawString(fontRendererObj, "Audio", sLeft, pt + 110, COL_LABEL);
        drawString(fontRendererObj, "Signal", hLeft, pt + 110, COL_LABEL);
        drawScope(sLeft, pt + 120, half, 38);
        drawHistory(hLeft, pt + 120, half, 38);

        // Preset labels reflect stored frequencies live.
        for (int i = 0; i < presetButtons.length; i++) {
            if (presetButtons[i] == null) continue;
            if (tile.hasPreset(i)) {
                double mhz = tile.presetHz(i) / 1.0e6;
                presetButtons[i].displayString = mhz >= 100.0 ? String.format("%.1f", mhz) : String.format("%.2f", mhz);
            } else {
                presetButtons[i].displayString = "P" + (i + 1);
            }
        }

        // Hints.
        drawString(fontRendererObj, "Click preset to recall \u00B7 Shift+click to store", pl + 18, pt + 262, COL_LABEL);
        drawString(fontRendererObj, "Shift + step = x10 \u00B7 SEEK scans for signal", pl + 18, pt + 274, COL_LABEL);
    }

    /**
     * Sliding analog tuning scale. The needle is fixed in the centre and
     * the frequency ticks slide beneath it as the set is tuned, the
     * stretch of dial a player expects from an analog radio. Ticks are
     * spaced at a round step and labelled in megahertz on the major ones.
     */
    private void drawDial(int x, int y, int w, int h) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, COL_PANEL_BORDER);
        drawRect(x, y, x + w, y + h, COL_LCD_BG);

        double center = tile.tunedHz();
        double span = 1_200_000.0; // 1.2 MHz window across the dial.
        double left = center - span / 2.0;
        double right = center + span / 2.0;
        double tickStep = 100_000.0;
        double first = Math.ceil(left / tickStep) * tickStep;

        for (double f = first; f <= right; f += tickStep) {
            int tx = x + (int) ((f - left) / span * w);
            boolean major = Math.round(f / 500_000.0) * 500_000.0 == Math.round(f);
            // Treat multiples of 500 kHz as major ticks.
            boolean isMajor = ((long) Math.round(f) % 500_000L) == 0L;
            int tickTop = isMajor ? y + 2 : y + h / 2;
            drawRect(tx, tickTop, tx + 1, y + h - 2, COL_GRATICULE);
            if (isMajor) {
                String lbl = String.format("%.1f", f / 1.0e6);
                drawString(fontRendererObj, lbl, tx - fontRendererObj.getStringWidth(lbl) / 2, y + h - 9, 0xFF6FAF6F);
            }
        }

        int needle = x + w / 2;
        drawRect(needle - 1, y, needle + 1, y + h, COL_DANGER);
    }

    /** Live audio waveform from the real received PCM, SDR-style. */
    private void drawScope(int x, int y, int w, int h) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, COL_PANEL_BORDER);
        drawRect(x, y, x + w, y + h, COL_LCD_BG);
        drawGraticule(x, y, x + w, y + h, 4, 2);

        float[] wave = ClientAudioBridge.latestWaveform(voiceKey);
        int mid = y + h / 2;
        if (wave == null || wave.length == 0) {
            drawRect(x, mid, x + w, mid + 1, COL_TRACE);
            return;
        }
        int amp = (h / 2) - 2;
        int prevX = x, prevY = mid - (int) (clamp(wave[0]) * amp);
        for (int i = 1; i < wave.length; i++) {
            int cx = x + i * w / wave.length;
            int cy = mid - (int) (clamp(wave[i]) * amp);
            // Vertical connector then a step, a cheap polyline.
            int y0 = Math.min(prevY, cy), y1 = Math.max(prevY, cy);
            drawRect(cx, y0, cx + 1, y1 + 1, COL_TRACE);
            drawRect(prevX, prevY, cx + 1, prevY + 1, COL_TRACE);
            prevX = cx;
            prevY = cy;
        }
    }

    /** Scrolling S/N history bar graph. */
    private void drawHistory(int x, int y, int w, int h) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, COL_PANEL_BORDER);
        drawRect(x, y, x + w, y + h, COL_LCD_BG);
        drawGraticule(x, y, x + w, y + h, 4, 2);
        for (int i = 0; i < HISTORY; i++) {
            int idx = (historyHead + i) % HISTORY;
            float v = (snrHistory[idx] + 10f) / 70f;
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            int barH = (int) (v * (h - 2));
            int xx = x + i * w / HISTORY;
            int xx1 = x + (i + 1) * w / HISTORY;
            drawRect(xx, y + h - barH, xx1, y + h, COL_TRACE);
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
