package io.hertzian.dynamics.gui;

/**
 * Stable GUI ID registry. Each block or item GUI gets an id used
 * by {@code IGuiHandler} to dispatch construction on both sides.
 * Never reorder; only append.
 */
public final class GuiIds {

    private GuiIds() {}

    public static final int RECEIVER = 0;
    public static final int TRANSMITTER = 1;
    public static final int JAMMER = 2;
    public static final int SPECTRUM_ANALYZER = 3;
    public static final int HANDHELD_RADIO = 4;
    public static final int RELAY = 5;
    public static final int TELETYPE = 6;
    public static final int TELEGRAPH_KEY = 7;
    public static final int RTTY = 8;
    public static final int DTMF = 9;
    public static final int SSTV = 10;
    public static final int RADIO_GEAR = 11;
}