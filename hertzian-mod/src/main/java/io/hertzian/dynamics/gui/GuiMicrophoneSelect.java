package io.hertzian.dynamics.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;

import org.lwjgl.input.Mouse;

import io.hertzian.dynamics.audio.MicrophoneCapture;
import io.hertzian.dynamics.audio.MicrophoneConfig;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.gui.widget.ScrollList;
import io.hertzian.dynamics.gui.widget.Slider;

/**
 * Microphone settings screen, the radio equivalent of the input tab a
 * voice chat mod presents. It collects in one place everything the
 * operator needs to get their voice on air cleanly:
 *
 * <ul>
 * <li>A scrolling device list. Every capture device the driver
 * reports is shown, plus a default entry that maps to the
 * platform choice. Picking a row pins it and reopens capture on
 * the new device.</li>
 * <li>An activation toggle, push to talk against voice activation.
 * The threshold control below only matters in voice mode and is
 * dimmed otherwise.</li>
 * <li>An input gain slider, a linear amplification applied before the
 * audio leaves for the server.</li>
 * <li>A voice threshold slider with a live input meter drawn behind
 * it, so the operator can set the gate just above their idle room
 * noise the way the voice chat mods let you.</li>
 * </ul>
 *
 * <p>
 * The screen opens a capture session of its own so the meter is live
 * while tuning, and releases it on close. The session is reference
 * counted, so it coexists with an active push to talk without fighting
 * over the device.
 */
public final class GuiMicrophoneSelect extends HertzianGui {

    private static final int BTN_DEVICE_DEFAULT = 100;
    private static final int BTN_REFRESH = 101;
    private static final int BTN_MODE = 102;
    private static final int SLD_GAIN = 110;
    private static final int SLD_THRESHOLD = 111;

    private ScrollList deviceList;
    private ScopeButton modeButton;
    private Slider gainSlider;
    private Slider thresholdSlider;

    private MicrophoneCapture meterMic;
    private float meterLevel = 0f;

    public GuiMicrophoneSelect() {
        super("Microphone", "Input settings");
        this.panelWidth = 320;
        this.panelHeight = 250;
    }

    @Override
    public void initGui() {
        super.initGui();
        deviceList = new ScrollList(fontRendererObj);
        rebuildDeviceList();
        // Open a metering session so the level bar is live while editing.
        meterMic = MicrophoneCapture.acquire();
    }

    @Override
    protected void layoutWidgets() {
        int pl = panelLeft;
        int pt = panelTop;
        int pw = panelWidth;

        buttonList.add(new ScopeButton(BTN_DEVICE_DEFAULT, pl + 18, pt + 30, 90, 14, "Default"));
        buttonList.add(new ScopeButton(BTN_REFRESH, pl + pw - 78, pt + 30, 60, 14, "Refresh"));

        boolean voice = MicrophoneConfig.activation() == MicrophoneConfig.Activation.VOICE;
        modeButton = ScopeButton.toggle(BTN_MODE, pl + 18, pt + 150, 130, 16, "Mode: Voice", "Mode: PTT", voice);
        buttonList.add(modeButton);

        // Gain 0..4 mapped onto the slider 0..1.
        gainSlider = new Slider(SLD_GAIN, pl + 18, pt + 172, pw - 36, 16, "Gain", MicrophoneConfig.gain() / 4f);
        buttonList.add(gainSlider);

        thresholdSlider = new Slider(
            SLD_THRESHOLD,
            pl + 18,
            pt + 210,
            pw - 36,
            16,
            "Threshold",
            MicrophoneConfig.voiceThreshold());
        buttonList.add(thresholdSlider);
    }

    private void rebuildDeviceList() {
        List<String> labels = new ArrayList<>();
        labels.add("(default)");
        labels.addAll(MicrophoneCapture.listDevices());
        deviceList.setItems(labels);
        deviceList.setRowHeight(12);

        // Highlight the active choice.
        String current = MicrophoneCapture.preferredDevice();
        if (current == null) {
            deviceList.setSelected(0);
        } else {
            deviceList.selectByValue(current);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BTN_DEVICE_DEFAULT:
                applyDevice(null);
                deviceList.setSelected(0);
                break;
            case BTN_REFRESH:
                rebuildDeviceList();
                break;
            case BTN_MODE: {
                boolean voice = !modeButton.state();
                modeButton.setState(voice);
                MicrophoneConfig.setActivation(
                    voice ? MicrophoneConfig.Activation.VOICE : MicrophoneConfig.Activation.PUSH_TO_TALK);
                break;
            }
            default:
                break;
        }
    }

    private void applyDevice(String name) {
        MicrophoneCapture.setPreferredDevice(name);
        // Close the current sessions so the next acquire opens the new
        // device. The metering session is reopened immediately for the
        // live bar; the PTT handler reopens on its next key press.
        if (meterMic != null) {
            meterMic.release();
            meterMic = null;
        }
        MicrophoneCapture.shutdown();
        meterMic = MicrophoneCapture.acquire();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (deviceList == null) return;
        int row = deviceList.mouseClicked(mouseX, mouseY, button);
        if (row < 0) return;
        if (row == 0) {
            applyDevice(null);
        } else {
            applyDevice(deviceList.selectedValue());
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        super.mouseClickMove(mouseX, mouseY, button, time);
        if (deviceList != null) deviceList.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (deviceList != null) deviceList.mouseReleased();
        if (which != 0) return;
        if (gainSlider != null) {
            MicrophoneConfig.setGain(gainSlider.value() * 4f);
        }
        if (thresholdSlider != null) {
            MicrophoneConfig.setVoiceThreshold(thresholdSlider.value());
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0 || deviceList == null) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (deviceList.contains(mx, my)) {
            deviceList.scrollRows(dWheel > 0 ? 1 : -1);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // Drive the live meter from the metering capture session.
        if (meterMic != null) {
            meterMic.drain();
            meterLevel = meterMic.lastLevel();
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (meterMic != null) {
            meterMic.release();
            meterMic = null;
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int pl = panelLeft;
        int pt = panelTop;
        int pw = panelWidth;

        drawString(fontRendererObj, "Devices", pl + 18, pt + 46, COL_LABEL);
        deviceList.setBounds(pl + 18, pt + 58, pw - 36, 78);
        deviceList.draw(mouseX, mouseY);

        // Live input meter, drawn just above the threshold slider so the
        // operator can match the gate to their room noise. The threshold
        // marker rides on the same bar.
        int barX = pl + 18;
        int barY = pt + 196;
        int barW = pw - 36;
        drawString(fontRendererObj, "Input", barX, pt + 188, COL_LABEL);
        float level = meterLevel;
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        boolean voice = MicrophoneConfig.activation() == MicrophoneConfig.Activation.VOICE;
        int meterColour = (!voice || level >= MicrophoneConfig.voiceThreshold()) ? COL_SAFE : COL_TRACE;
        drawHorizontalBar(barX, barY, barW, 6, level, meterColour);
        if (voice) {
            int markX = barX + (int) (barW * MicrophoneConfig.voiceThreshold());
            drawRect(markX, barY - 2, markX + 1, barY + 8, COL_DANGER);
        }

        String hint = voice ? "Voice activation. Speak above the red marker to key the radio."
            : "Push to talk. Hold V while a powered radio is in hand.";
        drawString(fontRendererObj, hint, pl + 18, pt + panelHeight - 14, COL_LABEL);
    }
}
