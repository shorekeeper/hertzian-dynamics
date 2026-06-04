package io.hertzian.dynamics.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Mouse;

import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.core.StationId;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.gui.widget.ScrollList;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketStationControl;
import io.hertzian.dynamics.net.PacketTransmitterSettings;
import io.hertzian.dynamics.tile.TileRadioTransmitter;
import io.hertzian.dynamics.world.StationLibrary;

/**
 * Transmitter GUI.
 *
 * <ul>
 * <li>The available .qoa tracks are shown in a scrolling
 * {@link ScrollList} (mouse wheel, drag handle) rather than a
 * column of buttons.</li>
 * <li>A slide-out panel on the right holds the playlist: add the
 * selected track, remove an entry, clear it, and choose Linear
 * (in order) or Shuffle (random within the playlist) playback.
 * The panel animates in and out; its widgets ride along with the
 * slide and only become clickable once it is fully open.</li>
 * <li>Frequency tuning gained the Shift = x10 modifier: holding Shift
 * while pressing a step button multiplies the step by ten.</li>
 * </ul>
 *
 * <p>
 * The playlist itself lives on the tile and is synced to the client
 * through the tile description packet, so this GUI reads
 * {@link TileRadioTransmitter#playlist()} for display and mutates it
 * optimistically while a {@link PacketStationControl} carries the same
 * edit to the server. Transport and playlist actions fire on their own
 * cadence through that control packet; the carrier-side settings (mode,
 * name, power, broadcast) still travel in
 * {@link PacketTransmitterSettings}.
 */
public final class GuiRadioTransmitter extends HertzianGui {

    private final TileRadioTransmitter tile;
    private final int tileX, tileY, tileZ, tileDim;

    private static final int BTN_STEP_BASE = 100;
    private static final int BTN_MODE_TOGGLE = 200;
    private static final int BTN_SIG_MOD = 201;
    private static final int BTN_PWR_DEC = 202;
    private static final int BTN_PWR_INC = 203;
    private static final int BTN_BROADCAST = 204;
    private static final int BTN_PLAY = 210;
    private static final int BTN_STOP = 211;
    private static final int BTN_SEEK_BACK = 212;
    private static final int BTN_SEEK_FWD = 213;
    private static final int BTN_LOOP = 214;
    private static final int BTN_PLAYLIST_TOGGLE = 220;
    private static final int BTN_PL_SHUFFLE = 230;
    private static final int BTN_PL_ADD = 231;
    private static final int BTN_PL_REMOVE = 232;
    private static final int BTN_PL_CLEAR = 233;
    private static final int BTN_PL_PLAY = 234;

    private static final int SIDE_WIDTH = 150;

    private GuiTextField nameField;
    private ScrollList availableList;
    private ScrollList playlistList;

    private ScopeButton plShuffle, plAdd, plRemove, plClear, plPlay;

    // Slide-out animation state.
    private boolean playlistOpen = false;
    private float slide = 0f;
    private long lastAnimMs;

    // Double-click detection for "play this track".
    private int lastClickIndex = -1;
    private long lastClickMs = 0L;

    // Cheap change detection so the lists are not rebuilt every frame.
    private List<String> lastAvailRef = null;
    private int lastPlaylistHash = Integer.MIN_VALUE;

    /**
     * Real track identifier for each row of the available-tracks browser,
     * parallel to the strings shown in {@code availableList}. A null entry
     * is a playlist header row, which is not a selectable track. Rebuilt by
     * {@link #rebuildBrowserRows} whenever the track list changes.
     */
    private List<String> rowTrackIds = new ArrayList<>();

    /** Track list pushed by PacketStationList. Static so the packet handler can reach it. */
    private static volatile List<String> availableTracks = new ArrayList<>();

    public static void setAvailableTracks(List<String> tracks) {
        availableTracks = tracks;
    }

    public GuiRadioTransmitter(TileRadioTransmitter tile) {
        super("Radio Transmitter", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.panelWidth = 264;
        this.panelHeight = tile.mode() == TileRadioTransmitter.Mode.STATION ? 320 : 200;
    }

    @Override
    public void initGui() {
        super.initGui();
        nameField = new GuiTextField(fontRendererObj, panelLeft + 50, panelTop + 64, panelWidth - 68, 14);
        nameField.setMaxStringLength(StationId.MAX_NAME_BYTES);
        nameField.setText(tile.stationName());

        availableList = new ScrollList(fontRendererObj);
        playlistList = new ScrollList(fontRendererObj);
        lastAvailRef = null;
        lastPlaylistHash = Integer.MIN_VALUE;
        lastAnimMs = System.currentTimeMillis();

        // Ask the server for the available track list.
        NetworkHandler.CHANNEL
            .sendToServer(new PacketStationControl(tileDim, tileX, tileY, tileZ, PacketStationControl.LIST, 0, ""));
    }

    @Override
    protected void layoutWidgets() {
        boolean station = tile.mode() == TileRadioTransmitter.Mode.STATION;
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Frequency step buttons.
        double[] steps = { -1_000_000, -100_000, -12_500, 12_500, 100_000, 1_000_000 };
        String[] labels = { "-1M", "-100k", "-12.5k", "+12.5k", "+100k", "+1M" };
        int btnW = 38, btnH = 14, gap = 4;
        int total = btnW * 6 + gap * 5;
        int start = pl + (pw - total) / 2;
        for (int i = 0; i < 6; i++) {
            buttonList.add(
                ScopeButton
                    .step(BTN_STEP_BASE + i, start + i * (btnW + gap), pt + 84, btnW, btnH, labels[i], steps[i]));
        }

        buttonList.add(
            ScopeButton.toggle(BTN_MODE_TOGGLE, pl + 18, pt + 102, 116, 16, "Mode: STATION", "Mode: NORMAL", station));
        buttonList.add(
            new ScopeButton(
                BTN_SIG_MOD,
                pl + 140,
                pt + 102,
                pw - 158,
                16,
                "Sig: " + tile.modulation()
                    .name()));
        buttonList.add(new ScopeButton(BTN_PWR_DEC, pl + 18, pt + 120, 40, 16, "- Pwr"));
        buttonList.add(new ScopeButton(BTN_PWR_INC, pl + 60, pt + 120, 40, 16, "+ Pwr"));
        buttonList.add(
            ScopeButton
                .toggle(BTN_BROADCAST, pl + 104, pt + 120, pw - 122, 16, "On Air", "Off Air", tile.broadcasting()));

        if (station) {
            int ty = pt + 140;
            buttonList.add(new ScopeButton(BTN_PLAY, pl + 18, ty, 50, 16, tile.playing() ? "Pause" : "Play"));
            buttonList.add(new ScopeButton(BTN_STOP, pl + 70, ty, 36, 16, "Stop"));
            buttonList.add(new ScopeButton(BTN_SEEK_BACK, pl + 108, ty, 34, 16, "<<10"));
            buttonList.add(new ScopeButton(BTN_SEEK_FWD, pl + 144, ty, 34, 16, "10>>"));
            buttonList.add(ScopeButton.toggle(BTN_LOOP, pl + 180, ty, 44, 16, "Loop", "Once", tile.loopTrack()));

            buttonList.add(
                ScopeButton
                    .toggle(BTN_PLAYLIST_TOGGLE, pl + pw - 58, pt + 176, 50, 14, "List <", "List >", playlistOpen));

            // Side panel widgets. Created here, positioned and shown every
            // frame in drawContent as the panel slides.
            plShuffle = ScopeButton.toggle(BTN_PL_SHUFFLE, 0, 0, 130, 16, "Shuffle", "Linear", tile.shuffle());
            plAdd = new ScopeButton(BTN_PL_ADD, 0, 0, 62, 16, "Add");
            plRemove = new ScopeButton(BTN_PL_REMOVE, 0, 0, 62, 16, "Remove");
            plClear = new ScopeButton(BTN_PL_CLEAR, 0, 0, 62, 16, "Clear");
            plPlay = new ScopeButton(BTN_PL_PLAY, 0, 0, 62, 16, "Play All");
            buttonList.add(plShuffle);
            buttonList.add(plAdd);
            buttonList.add(plRemove);
            buttonList.add(plClear);
            buttonList.add(plPlay);
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b instanceof ScopeButton && ((ScopeButton) b).deltaHz != 0.0) {
            double d = ((ScopeButton) b).deltaHz;
            if (isShiftKeyDown()) d *= 10.0;
            tile.setCarrierHz(tile.carrierHz() + d);
            sendSettings();
            return;
        }

        switch (b.id) {
            case BTN_MODE_TOGGLE: {
                ScopeButton tb = (ScopeButton) b;
                tb.setState(!tb.state());
                tile.setMode(tb.state() ? TileRadioTransmitter.Mode.STATION : TileRadioTransmitter.Mode.NORMAL);
                sendSettings();
                this.panelHeight = tb.state() ? 320 : 200;
                this.playlistOpen = false;
                this.slide = 0f;
                buttonList.clear();
                initGui();
                return;
            }
            case BTN_SIG_MOD: {
                Modulation[] all = Modulation.values();
                Modulation next = all[(tile.modulation()
                    .ordinal() + 1) % all.length];
                tile.setModulation(next);
                b.displayString = "Sig: " + next.name();
                sendSettings();
                return;
            }
            case BTN_PWR_DEC:
                tile.setTxPowerW(previousPower(tile.txPowerW()));
                sendSettings();
                return;
            case BTN_PWR_INC:
                tile.setTxPowerW(nextPower(tile.txPowerW()));
                sendSettings();
                return;
            case BTN_BROADCAST: {
                ScopeButton tb = (ScopeButton) b;
                tb.setState(!tb.state());
                tile.setBroadcasting(tb.state());
                sendSettings();
                return;
            }
            case BTN_PLAY:
                sendControl(tile.playing() ? PacketStationControl.PAUSE : PacketStationControl.PLAY, 0, "");
                tile.setPlaying(!tile.playing());
                b.displayString = tile.playing() ? "Pause" : "Play";
                return;
            case BTN_STOP:
                sendControl(PacketStationControl.STOP, 0, "");
                tile.setPlaying(false);
                return;
            case BTN_SEEK_BACK:
                sendControl(PacketStationControl.SEEK, Math.max(0, tile.positionSeconds() - 10), "");
                return;
            case BTN_SEEK_FWD:
                sendControl(PacketStationControl.SEEK, tile.positionSeconds() + 10, "");
                return;
            case BTN_LOOP: {
                ScopeButton tb = (ScopeButton) b;
                tb.setState(!tb.state());
                tile.setLoopTrack(tb.state());
                sendControl(PacketStationControl.LOOP, tb.state() ? 1 : 0, "");
                return;
            }
            case BTN_PLAYLIST_TOGGLE: {
                playlistOpen = !playlistOpen;
                ((ScopeButton) b).setState(playlistOpen);
                return;
            }
            case BTN_PL_SHUFFLE: {
                ScopeButton tb = (ScopeButton) b;
                tb.setState(!tb.state());
                tile.setShuffle(tb.state());
                sendControl(PacketStationControl.SHUFFLE, tb.state() ? 1 : 0, "");
                return;
            }
            case BTN_PL_ADD: {
                String tr = selectedAvailableTrack();
                if (tr != null) {
                    tile.addToPlaylist(tr);
                    sendControl(PacketStationControl.PLAYLIST_ADD, 0, tr);
                }
                return;
            }
            case BTN_PL_REMOVE: {
                int i = playlistList.selectedIndex();
                if (i >= 0) {
                    tile.removeFromPlaylist(i);
                    sendControl(PacketStationControl.PLAYLIST_REMOVE, i, "");
                    playlistList.setSelected(-1);
                }
                return;
            }
            case BTN_PL_CLEAR:
                tile.clearPlaylist();
                sendControl(PacketStationControl.PLAYLIST_CLEAR, 0, "");
                return;
            case BTN_PL_PLAY:
                tile.startPlaylist();
                sendControl(PacketStationControl.PLAYLIST_PLAY, 0, "");
                return;
            default:
                break;
        }
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        super.mouseClicked(mx, my, btn);
        if (nameField != null) nameField.mouseClicked(mx, my, btn);
        if (tile.mode() != TileRadioTransmitter.Mode.STATION) return;

        int idx = availableList.mouseClicked(mx, my, btn);
        if (idx >= 0 && idx < rowTrackIds.size()) {
            String track = rowTrackIds.get(idx);
            if (track == null) {
                // Playlist header row: not a track, clear the selection so a
                // header is never treated as the selected track.
                availableList.setSelected(-1);
            } else {
                long now = System.currentTimeMillis();
                boolean dbl = (idx == lastClickIndex && now - lastClickMs < 300L);
                lastClickIndex = idx;
                lastClickMs = now;
                tile.selectTrack(track);
                sendControl(PacketStationControl.TRACK, 0, track);
                if (dbl) {
                    tile.setPlaying(true);
                    sendControl(PacketStationControl.PLAY, 0, "");
                }
            }
        }

        if (slide > 0.5f) {
            playlistList.mouseClicked(mx, my, btn);
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int b, long t) {
        super.mouseClickMove(mx, my, b, t);
        if (availableList != null) availableList.mouseDragged(mx, my);
        if (playlistList != null) playlistList.mouseDragged(mx, my);
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        super.mouseMovedOrUp(mx, my, which);
        if (availableList != null) availableList.mouseReleased();
        if (playlistList != null) playlistList.mouseReleased();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        if (tile.mode() != TileRadioTransmitter.Mode.STATION) return;
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        int rows = dWheel > 0 ? 1 : -1;
        if (slide > 0.5f && playlistList.contains(mx, my)) {
            playlistList.scrollRows(rows);
        } else if (availableList.contains(mx, my)) {
            availableList.scrollRows(rows);
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (nameField != null && nameField.isFocused()) {
            if (key == org.lwjgl.input.Keyboard.KEY_RETURN) {
                applyName();
                nameField.setFocused(false);
                return;
            }
            if (nameField.textboxKeyTyped(ch, key)) {
                applyName();
                return;
            }
        }
        super.keyTyped(ch, key);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (nameField != null) nameField.updateCursorCounter();
    }

    private void applyName() {
        tile.setStationName(nameField.getText());
        sendSettings();
    }

    private static final float[] POWER_STEPS = { 0.5f, 1f, 2f, 5f, 10f, 25f, 50f, 100f, 250f };

    private static float nextPower(float c) {
        for (float p : POWER_STEPS) if (p > c + 0.001f) return p;
        return POWER_STEPS[POWER_STEPS.length - 1];
    }

    private static float previousPower(float c) {
        float prev = POWER_STEPS[0];
        for (float p : POWER_STEPS) {
            if (p < c - 0.001f) prev = p;
            else break;
        }
        return prev;
    }

    private void sendSettings() {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketTransmitterSettings(
                tileDim,
                tileX,
                tileY,
                tileZ,
                tile.carrierHz(),
                tile.modulation()
                    .code(),
                tile.txPowerW(),
                tile.broadcasting(),
                tile.mode()
                    .ordinal(),
                tile.stationName()));
    }

    private void sendControl(int action, double param, String track) {
        NetworkHandler.CHANNEL
            .sendToServer(new PacketStationControl(tileDim, tileX, tileY, tileZ, action, param, track));
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        String freq = String.format("%10.4f MHz", tile.carrierHz() / 1.0e6);
        drawLcdDisplay(pl + 18, pt + 26, pw - 36, 24, freq);

        String info = tile.modulation()
            .name() + "   "
            + tile.txPowerW()
            + " W   "
            + (tile.broadcasting() ? "ON AIR" : "OFF");
        drawCenteredString(fontRendererObj, info, pl + pw / 2, pt + 54, tile.broadcasting() ? COL_SAFE : COL_LABEL);

        drawString(fontRendererObj, "ID", pl + 18, pt + 68, COL_LABEL);
        if (nameField != null) nameField.drawTextBox();

        if (tile.mode() == TileRadioTransmitter.Mode.STATION) {
            drawStation(mouseX, mouseY);
        } else {
            drawString(fontRendererObj, "Shift + step button = x10", pl + 18, pt + panelHeight - 26, COL_LABEL);
            drawString(
                fontRendererObj,
                "Feed a Test Tone or Microphone within 8 blocks.",
                pl + 18,
                pt + panelHeight - 14,
                COL_LABEL);
        }
    }

    private void drawStation(int mouseX, int mouseY) {
        int pl = panelLeft, pt = panelTop, pw = panelWidth;

        // Now playing line.
        String track = friendlyTrack(tile.selectedTrack());
        String state = tile.playing() ? "PLAY" : "PAUSE";
        drawString(fontRendererObj, state + "  " + trim(track, pw - 90), pl + 18, pt + 158, COL_LCD_TEXT);

        double dur = tile.durationSeconds(), pos = tile.positionSeconds();
        float frac = dur > 0 ? (float) (pos / dur) : 0f;
        drawHorizontalBar(pl + 18, pt + 168, pw - 36, 6, frac, COL_TRACE);
        String tstr = String
            .format("%d:%02d / %d:%02d", (int) pos / 60, (int) pos % 60, (int) dur / 60, (int) dur % 60);
        drawString(fontRendererObj, tstr, pl + pw - 18 - fontRendererObj.getStringWidth(tstr), pt + 158, COL_LABEL);

        drawString(fontRendererObj, "Tracks", pl + 18, pt + 178, COL_LABEL);

        // Available track list.
        refreshAvailable();
        int alTop = pt + 190, alBottom = pt + panelHeight - 14;
        availableList.setBounds(pl + 18, alTop, pw - 36, alBottom - alTop);
        availableList.draw(mouseX, mouseY);

        // Slide-out side panel animation, time based so the speed is
        // independent of frame rate.
        long now = System.currentTimeMillis();
        float dt = (now - lastAnimMs) / 1000f;
        if (dt > 0.1f) dt = 0.1f;
        lastAnimMs = now;
        float target = playlistOpen ? 1f : 0f;
        if (slide < target) slide = Math.min(target, slide + dt * 6f);
        else if (slide > target) slide = Math.max(target, slide - dt * 6f);

        int sideLeft = pl + pw + (int) ((1f - slide) * SIDE_WIDTH);
        boolean show = slide > 0.05f;
        boolean live = slide > 0.95f;

        // Reposition the side panel widgets before super.drawScreen
        // renders them.
        if (plShuffle != null) {
            int by1 = pt + panelHeight - 44, by2 = pt + panelHeight - 24;
            place(plShuffle, sideLeft + 10, pt + 22, show, live);
            place(plAdd, sideLeft + 10, by1, show, live);
            place(plRemove, sideLeft + 78, by1, show, live);
            place(plClear, sideLeft + 10, by2, show, live);
            place(plPlay, sideLeft + 78, by2, show, live);
        }

        if (show) {
            drawRect(sideLeft - 2, pt - 2, sideLeft + SIDE_WIDTH + 2, pt + panelHeight + 2, COL_PANEL_BORDER);
            drawRect(sideLeft, pt, sideLeft + SIDE_WIDTH, pt + panelHeight, COL_PANEL_BG);
            drawCenteredString(fontRendererObj, "Playlist", sideLeft + SIDE_WIDTH / 2, pt + 8, 0xFFE0FFE0);

            refreshPlaylist();
            int listTop = pt + 44, listBottom = pt + panelHeight - 58;
            playlistList.setBounds(sideLeft + 10, listTop, SIDE_WIDTH - 20, listBottom - listTop);
            playlistList.draw(mouseX, mouseY);

            drawString(
                fontRendererObj,
                tile.shuffle() ? "Order: random" : "Order: in order",
                sideLeft + 10,
                pt + panelHeight - 56,
                COL_LABEL);
        }
    }

    private void place(ScopeButton b, int x, int y, boolean show, boolean live) {
        b.xPosition = x;
        b.yPosition = y;
        b.visible = show;
        b.enabled = live;
    }

    private void refreshAvailable() {
        List<String> a = availableTracks;
        if (a != lastAvailRef) {
            lastAvailRef = a;
            rebuildBrowserRows(a);
        }
    }

    /**
     * Build the grouped browser view from the flat list of track
     * identifiers. Root tracks are shown by their bare name. Playlist
     * tracks are grouped under a non-selectable header carrying the
     * playlist display name, with each track indented beneath it. The
     * incoming list is already ordered root-first then grouped by folder
     * (see {@link StationLibrary#list()}), so one pass produces the view.
     * {@link #rowTrackIds} is filled in parallel so a clicked row maps back
     * to its real identifier.
     */
    private void rebuildBrowserRows(List<String> tracks) {
        List<String> display = new ArrayList<>();
        rowTrackIds = new ArrayList<>();
        int selectedRow = -1;
        String sel = tile.selectedTrack();
        String currentPlaylist = null;

        for (String id : tracks) {
            if (StationLibrary.isPlaylistTrack(id)) {
                String folder = StationLibrary.playlistOf(id);
                if (!folder.equals(currentPlaylist)) {
                    currentPlaylist = folder;
                    display.add("\u25B6 " + StationLibrary.playlistDisplayName(folder));
                    rowTrackIds.add(null);
                }
                display.add("   " + StationLibrary.trackNameOf(id));
                rowTrackIds.add(id);
            } else {
                display.add(StationLibrary.trackNameOf(id));
                rowTrackIds.add(id);
            }
            if (id.equals(sel)) selectedRow = display.size() - 1;
        }

        availableList.setItems(display);
        availableList.setSelected(selectedRow);
    }

    /**
     * Real track identifier currently selected in the browser, or null when
     * the selection is empty or sits on a playlist header row.
     */
    private String selectedAvailableTrack() {
        int i = availableList.selectedIndex();
        if (i < 0 || i >= rowTrackIds.size()) return null;
        return rowTrackIds.get(i);
    }

    /**
     * Friendly one-line name for a track identifier. A playlist track reads
     * as "Playlist Name / file"; a root track is its bare name; an empty
     * identifier reads as a placeholder.
     */
    private static String friendlyTrack(String id) {
        if (id == null || id.isEmpty()) return "(no track)";
        if (StationLibrary.isPlaylistTrack(id)) {
            return StationLibrary.playlistDisplayName(StationLibrary.playlistOf(id)) + " / "
                + StationLibrary.trackNameOf(id);
        }
        return id;
    }

    private void refreshPlaylist() {
        List<String> p = tile.playlist();
        int hash = p.hashCode();
        if (hash != lastPlaylistHash) {
            int sel = playlistList.selectedIndex();
            List<String> display = new ArrayList<>(p.size());
            for (String id : p) {
                display.add(friendlyTrack(id));
            }
            playlistList.setItems(display);
            playlistList.setSelected(sel);
            lastPlaylistHash = hash;
        }
    }

    private String trim(String s, int width) {
        if (fontRendererObj.getStringWidth(s) <= width) return s;
        return fontRendererObj.trimStringToWidth(s, width - 6) + "...";
    }
}
