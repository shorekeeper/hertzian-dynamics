package io.hertzian.dynamics.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;

import io.hertzian.dynamics.core.SstvCodec;
import io.hertzian.dynamics.gui.widget.ScopeButton;
import io.hertzian.dynamics.net.NetworkHandler;
import io.hertzian.dynamics.net.PacketSstvControl;
import io.hertzian.dynamics.tile.TileSstvStation;

/**
 * SSTV monitor and station GUI, rebuilt around a multi-frame send queue
 * and two expandable side panels.
 *
 * <p>
 * Layout
 * <ul>
 * <li><b>Centre.</b> The frequency display and tuning bank, a status
 * row with the signal meter and the transmit or receive state, and
 * the picture monitor showing the currently viewed received frame.</li>
 * <li><b>Left panel (Queue).</b> Toggled open, it draws to the left of
 * the main panel and holds the send queue: buttons to add each
 * procedural pattern or the held map, the five queue slots with a
 * live thumbnail per slot, remove and clear, and start and stop with
 * the batch progress. Pattern thumbnails are generated locally from
 * the same codec the server uses, so they need no image transfer;
 * map slots show a placeholder because their pixels intentionally
 * never leave the server.</li>
 * <li><b>Right panel (Gallery).</b> Toggled open, it draws to the right
 * and pages through the received frames, up to five held at once,
 * keyed by the decoder frame number the line packets carry.</li>
 * </ul>
 *
 * <p>
 * Server-safety note carried into the UI
 * Nothing in this GUI uploads an image. Adding a pattern sends one ordinal;
 * adding a map sends an intent the server resolves from data it already
 * owns. Starting the batch transmits the frames one at a time on the
 * server. The panels are pure display fed by the small status packet.
 *
 * <p>
 * Received image store
 * Decoded lines arrive as {@link io.hertzian.dynamics.net.PacketSstvLine}
 * on the network thread, possibly before or without this GUI being open,
 * so the received pictures live in a static {@link #GALLERIES} map keyed by
 * block. {@link #acceptLine} files each line under its frame number into a
 * small ring of at most five frames; the open GUI reads it on the client
 * tick. A torn read of one pixel is harmless, so the store needs no lock.
 */
public final class GuiSstvStation extends HertzianGui {

    private final TileSstvStation tile;
    private final int tileX;
    private final int tileY;
    private final int tileZ;
    private final int tileDim;
    private final String imgKey;

    // Centre controls.
    private static final int BTN_STEP_BASE = 100;
    private static final int BTN_QUEUE_TOGGLE = 110;
    private static final int BTN_GALLERY_TOGGLE = 111;

    // Queue panel controls.
    private static final int BTN_ADD_BASE = 120; // 120..123 patterns, 124 map
    private static final int BTN_REMOVE = 130;
    private static final int BTN_CLEAR_QUEUE = 131;
    private static final int BTN_START = 132;
    private static final int BTN_STOP = 133;
    private static final int BTN_SLOT_BASE = 140; // 140..144 queue slots

    // Gallery panel controls.
    private static final int BTN_GAL_PREV = 150;
    private static final int BTN_GAL_NEXT = 151;
    private static final int BTN_GAL_LATEST = 152;

    private static final int QUEUE_W = 158;
    private static final int GALLERY_W = 130;

    /** Labels for the kind bytes the status packet carries. */
    private static final String[] PATTERN_LABELS = { "Bars", "Grad", "Check", "Hatch" };

    /** Cache of locally generated pattern thumbnails, keyed by pattern ordinal. */
    private static final Map<Integer, int[][]> PATTERN_CACHE = new HashMap<>();

    private boolean queueOpen = false;
    private boolean galleryOpen = false;
    private int selectedSlot = -1;

    // Side-panel buttons, placed each frame in drawContent.
    private final List<ScopeButton> queueButtons = new ArrayList<>();
    private final List<ScopeButton> galleryButtons = new ArrayList<>();
    private final ScopeButton[] slotButtons = new ScopeButton[TileSstvStation.MAX_QUEUE];

    public GuiSstvStation(TileSstvStation tile) {
        super("SSTV Station", "Hertzian Dynamics");
        this.tile = tile;
        this.tileX = tile.xCoord;
        this.tileY = tile.yCoord;
        this.tileZ = tile.zCoord;
        this.tileDim = tile.getWorldObj().provider.dimensionId;
        this.imgKey = tileDim + ":" + tileX + ":" + tileY + ":" + tileZ;
        this.panelWidth = 280;
        this.panelHeight = 320;
    }

    // ----- Received-frame store -------------------------------------------

    /**
     * Per-block ring of received pictures. Holds at most five decoded
     * frames keyed by their decoder frame number, follows the newest by
     * default, and lets the gallery panel page backwards.
     */
    private static final class RxGallery {

        final List<int[]> frames = new ArrayList<>();
        final List<Integer> seqs = new ArrayList<>();
        int view = 0;
        boolean follow = true;
    }

    private static final Map<String, RxGallery> GALLERIES = new HashMap<>();

    /**
     * File one decoded line into the received-frame store for the named
     * block. A line whose frame number is not yet known starts a new frame
     * buffer, dropping the oldest once five are held; the rest of the lines
     * for that frame fill it in. Out-of-range indices or short buffers are
     * ignored because the data came off the wire.
     */
    public static void acceptLine(int dim, int x, int y, int z, int frameSeq, int line, byte[] rgb) {
        if (line < 0 || line >= SstvCodec.H) {
            return;
        }
        final String key = dim + ":" + x + ":" + y + ":" + z;
        RxGallery g = GALLERIES.get(key);
        if (g == null) {
            g = new RxGallery();
            GALLERIES.put(key, g);
        }
        int slot = g.seqs.indexOf(frameSeq);
        if (slot < 0) {
            g.frames.add(new int[SstvCodec.W * SstvCodec.H]);
            g.seqs.add(frameSeq);
            while (g.frames.size() > TileSstvStation.MAX_QUEUE) {
                g.frames.remove(0);
                g.seqs.remove(0);
                if (g.view > 0) {
                    g.view--;
                }
            }
            slot = g.frames.size() - 1;
            if (g.follow) {
                g.view = slot;
            }
        }
        int[] image = g.frames.get(slot);
        for (int px = 0; px < SstvCodec.W && px * 3 + 2 < rgb.length; px++) {
            int r = rgb[px * 3] & 0xFF;
            int gg = rgb[px * 3 + 1] & 0xFF;
            int b = rgb[px * 3 + 2] & 0xFF;
            image[line * SstvCodec.W + px] = 0xFF000000 | (r << 16) | (gg << 8) | b;
        }
    }

    // ----- Layout ----------------------------------------------------------

    @Override
    protected void layoutWidgets() {
        final int pl = panelLeft;
        final int pt = panelTop;
        final int pw = panelWidth;

        // Tuning bank.
        final double[] steps = { -1_000_000, -100_000, -1_000, 1_000, 100_000, 1_000_000 };
        final String[] labels = { "-1M", "-100k", "-1k", "+1k", "+100k", "+1M" };
        final int btnW = 42;
        final int gap = 4;
        final int total = 6 * btnW + 5 * gap;
        final int start = pl + (pw - total) / 2;
        for (int i = 0; i < 6; i++) {
            buttonList.add(
                ScopeButton.step(BTN_STEP_BASE + i, start + i * (btnW + gap), pt + 54, btnW, 14, labels[i], steps[i]));
        }

        // Panel toggles.
        buttonList.add(ScopeButton.toggle(BTN_QUEUE_TOGGLE, pl + 18, pt + 72, 86, 14, "< Queue", "Queue >", queueOpen));
        buttonList.add(
            ScopeButton
                .toggle(BTN_GALLERY_TOGGLE, pl + pw - 104, pt + 72, 86, 14, "Gallery >", "< Gallery", galleryOpen));

        // Queue panel buttons. Created here, positioned and shown in
        // drawContent as the panel opens and closes.
        queueButtons.clear();
        queueButtons.add(new ScopeButton(BTN_ADD_BASE + 0, 0, 0, 36, 14, "+Bars"));
        queueButtons.add(new ScopeButton(BTN_ADD_BASE + 1, 0, 0, 36, 14, "+Grad"));
        queueButtons.add(new ScopeButton(BTN_ADD_BASE + 2, 0, 0, 36, 14, "+Chk"));
        queueButtons.add(new ScopeButton(BTN_ADD_BASE + 3, 0, 0, 36, 14, "+Htch"));
        queueButtons.add(new ScopeButton(BTN_ADD_BASE + 4, 0, 0, 50, 14, "+Map"));
        queueButtons.add(new ScopeButton(BTN_ADD_BASE + 5, 0, 0, 50, 14, "+Area"));
        queueButtons.add(new ScopeButton(BTN_REMOVE, 0, 0, 64, 14, "Remove"));
        queueButtons.add(new ScopeButton(BTN_CLEAR_QUEUE, 0, 0, 64, 14, "Clear"));
        queueButtons.add(new ScopeButton(BTN_START, 0, 0, 64, 16, "Start"));
        queueButtons.add(new ScopeButton(BTN_STOP, 0, 0, 64, 16, "Stop"));
        for (ScopeButton b : queueButtons) {
            buttonList.add(b);
        }
        for (int i = 0; i < slotButtons.length; i++) {
            slotButtons[i] = new ScopeButton(BTN_SLOT_BASE + i, 0, 0, QUEUE_W - 56, 26, "");
            buttonList.add(slotButtons[i]);
        }

        // Gallery panel buttons.
        galleryButtons.clear();
        galleryButtons.add(new ScopeButton(BTN_GAL_PREV, 0, 0, 36, 14, "Prev"));
        galleryButtons.add(new ScopeButton(BTN_GAL_NEXT, 0, 0, 36, 14, "Next"));
        galleryButtons.add(new ScopeButton(BTN_GAL_LATEST, 0, 0, 44, 14, "Latest"));
        for (ScopeButton b : galleryButtons) {
            buttonList.add(b);
        }
    }

    // ----- Actions ---------------------------------------------------------

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof ScopeButton && ((ScopeButton) button).deltaHz != 0.0) {
            double delta = ((ScopeButton) button).deltaHz;
            if (isShiftKeyDown()) {
                delta *= 10.0;
            }
            tile.setTunedHz(tile.tunedHz() + delta);
            send(PacketSstvControl.TUNE, 0);
            return;
        }

        switch (button.id) {
            case BTN_QUEUE_TOGGLE:
                queueOpen = !queueOpen;
                ((ScopeButton) button).setState(queueOpen);
                return;
            case BTN_GALLERY_TOGGLE:
                galleryOpen = !galleryOpen;
                ((ScopeButton) button).setState(galleryOpen);
                return;
            case BTN_REMOVE:
                if (selectedSlot >= 0) {
                    send(PacketSstvControl.REMOVE, selectedSlot);
                    selectedSlot = -1;
                }
                return;
            case BTN_CLEAR_QUEUE:
                send(PacketSstvControl.CLEAR_QUEUE, 0);
                selectedSlot = -1;
                return;
            case BTN_START:
                send(PacketSstvControl.START, 0);
                return;
            case BTN_STOP:
                send(PacketSstvControl.STOP, 0);
                return;
            case BTN_GAL_PREV:
                galleryStep(-1);
                return;
            case BTN_GAL_NEXT:
                galleryStep(1);
                return;
            case BTN_GAL_LATEST: {
                RxGallery g = GALLERIES.get(imgKey);
                if (g != null) {
                    g.follow = true;
                    g.view = Math.max(0, g.frames.size() - 1);
                }
                return;
            }
            default:
                break;
        }

        // Pattern and map add buttons.
        if (button.id >= BTN_ADD_BASE && button.id <= BTN_ADD_BASE + 3) {
            send(PacketSstvControl.ADD_PATTERN, button.id - BTN_ADD_BASE);
            return;
        }
        if (button.id == BTN_ADD_BASE + 4) {
            send(PacketSstvControl.ADD_MAP, 0);
            return;
        }

        if (button.id == BTN_ADD_BASE + 5) {
            send(PacketSstvControl.ADD_TERRAIN, 0);
            return;
        }

        // Queue slot select.
        if (button.id >= BTN_SLOT_BASE && button.id < BTN_SLOT_BASE + TileSstvStation.MAX_QUEUE) {
            int slot = button.id - BTN_SLOT_BASE;
            selectedSlot = (slot < tile.clientQueueSize()) ? slot : -1;
            return;
        }
    }

    private void galleryStep(int dir) {
        RxGallery g = GALLERIES.get(imgKey);
        if (g == null || g.frames.isEmpty()) {
            return;
        }
        g.follow = false;
        g.view += dir;
        if (g.view < 0) {
            g.view = 0;
        } else if (g.view >= g.frames.size()) {
            g.view = g.frames.size() - 1;
        }
    }

    private void send(int action, int param) {
        NetworkHandler.CHANNEL.sendToServer(
            new PacketSstvControl(tileDim, tileX, tileY, tileZ, action, param, tile.tunedHz(), tile.bandwidthHz()));
    }

    // ----- Drawing ---------------------------------------------------------

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        final int pl = panelLeft;
        final int pt = panelTop;
        final int pw = panelWidth;

        drawLcdDisplay(pl + 18, pt + 28, pw - 36, 24, String.format("%10.4f kHz", tile.tunedHz() / 1.0e3));

        // Status line.
        final String status;
        if (tile.sending()) {
            int idx = tile.clientTxFrameIndex();
            int qn = tile.clientQueueSize();
            status = String
                .format("TX %d/%d  %d%%", Math.max(1, idx + 1), Math.max(1, qn), (int) (tile.clientTxProgress() * 100));
        } else if (tile.clientReceiving()) {
            status = "RX locked  #" + tile.clientRxFrameSeq();
        } else {
            status = "Idle";
        }
        drawString(fontRendererObj, "SSTV 96x96  " + status, pl + 18, pt + 92, COL_LABEL);

        final float snr = tile.clientSnrDb();
        float level = (snr + 10f) / 70f;
        if (level < 0f) {
            level = 0f;
        } else if (level > 1f) {
            level = 1f;
        }
        drawHorizontalBar(pl + pw - 110, pt + 91, 92, 8, level, level > 0.5f ? COL_SAFE : COL_DANGER);

        // Picture monitor: the currently viewed received frame.
        drawMonitor(pl, pt, pw);

        // Side panels. Their buttons are positioned and shown here, before
        // the base class draws the button list on top.
        drawQueuePanel(mouseX, mouseY);
        drawGalleryPanel();

        drawString(fontRendererObj, "Shift + step = x10", pl + 18, pt + panelHeight - 12, COL_LABEL);
    }

    private void drawMonitor(int pl, int pt, int pw) {
        final int scale = 2;
        final int imageW = SstvCodec.W * scale;
        final int imageH = SstvCodec.H * scale;
        final int ix = pl + (pw - imageW) / 2;
        final int iy = pt + 108;
        drawRect(ix - 1, iy - 1, ix + imageW + 1, iy + imageH + 1, COL_PANEL_BORDER);
        drawRect(ix, iy, ix + imageW, iy + imageH, 0xFF000000);

        int[] image = null;
        int total = 0;
        int viewNo = 0;
        RxGallery g = GALLERIES.get(imgKey);
        if (g != null && !g.frames.isEmpty()) {
            int view = Math.min(Math.max(0, g.view), g.frames.size() - 1);
            image = g.frames.get(view);
            total = g.frames.size();
            viewNo = view + 1;
        }

        if (image != null) {
            for (int y = 0; y < SstvCodec.H; y++) {
                for (int x = 0; x < SstvCodec.W; x++) {
                    int colour = image[y * SstvCodec.W + x];
                    if (colour != 0) {
                        drawRect(ix + x * scale, iy + y * scale, ix + (x + 1) * scale, iy + (y + 1) * scale, colour);
                    }
                }
            }
            drawString(fontRendererObj, "Frame " + viewNo + " / " + total, ix, iy + imageH + 2, COL_LABEL);
        } else {
            drawCenteredString(fontRendererObj, "No image", ix + imageW / 2, iy + imageH / 2 - 4, COL_LABEL);
        }
    }

    private void drawQueuePanel(int mouseX, int mouseY) {
        final int pt = panelTop;
        final int left = panelLeft - QUEUE_W - 4;
        final boolean show = queueOpen;

        // Position and show or hide the queue buttons.
        int addY = pt + 22;
        positionRow(
            show,
            left + 8,
            addY,
            queueButtons.get(0),
            queueButtons.get(1),
            queueButtons.get(2),
            queueButtons.get(3));
        positionRow(show, left + 8, addY + 16, queueButtons.get(4), queueButtons.get(9)); // +Map, +Area

        for (int i = 0; i < slotButtons.length; i++) {
            place(slotButtons[i], left + 8, pt + 58 + i * 30, show, QUEUE_W - 16, 26);
            // Label slots from the synced kinds.
            byte[] kinds = tile.clientQueueKinds();
            if (i < kinds.length) {
                slotButtons[i].displayString = "  " + kindLabel(kinds[i]);
            } else {
                slotButtons[i].displayString = "  --";
            }
        }
        place(queueButtons.get(5), left + 8, pt + panelHeight - 60, show, 64, 14); // Remove
        place(queueButtons.get(6), left + 78, pt + panelHeight - 60, show, 64, 14); // Clear
        place(queueButtons.get(7), left + 8, pt + panelHeight - 42, show, 64, 16); // Start
        place(queueButtons.get(8), left + 78, pt + panelHeight - 42, show, 64, 16); // Stop

        if (!show) {
            return;
        }

        // Panel background and labels, drawn before the buttons render.
        drawRect(left - 2, pt - 2, left + QUEUE_W + 2, pt + panelHeight + 2, COL_PANEL_BORDER);
        drawRect(left, pt, left + QUEUE_W, pt + panelHeight, COL_PANEL_BG);
        drawCenteredString(fontRendererObj, "Send Queue", left + QUEUE_W / 2, pt + 8, 0xFFE0FFE0);

        // Slot thumbnails and selection highlight.
        byte[] kinds = tile.clientQueueKinds();
        for (int i = 0; i < slotButtons.length; i++) {
            int sy = pt + 58 + i * 30;
            if (i == selectedSlot && i < kinds.length) {
                drawRect(left + 6, sy - 1, left + QUEUE_W - 6, sy + 27, 0x402A8E3A);
            }
            if (i < kinds.length) {
                int k = kinds[i] & 0xFF;
                String ph = (k == TileSstvStation.KIND_TERRAIN) ? "AREA" : "MAP";
                drawThumb(thumbFor(kinds[i]), left + QUEUE_W - 44, sy + 1, 24, 24, ph);
            }
        }

        // Batch progress under the queue list.
        if (tile.sending()) {
            int qn = Math.max(1, tile.clientQueueSize());
            float overall = (tile.clientTxFrameIndex() + tile.clientTxProgress()) / qn;
            if (overall < 0f) {
                overall = 0f;
            } else if (overall > 1f) {
                overall = 1f;
            }
            drawHorizontalBar(left + 8, pt + panelHeight - 20, QUEUE_W - 16, 6, overall, COL_TRACE);
        }
    }

    private void drawGalleryPanel() {
        final int pt = panelTop;
        final int left = panelLeft + panelWidth + 4;
        final boolean show = galleryOpen;

        positionRow(show, left + 8, pt + 24, galleryButtons.get(0), galleryButtons.get(1));
        place(galleryButtons.get(2), left + 8, pt + 42, show, 44, 14);

        if (!show) {
            return;
        }

        drawRect(left - 2, pt - 2, left + GALLERY_W + 2, pt + panelHeight + 2, COL_PANEL_BORDER);
        drawRect(left, pt, left + GALLERY_W, pt + panelHeight, COL_PANEL_BG);
        drawCenteredString(fontRendererObj, "Received", left + GALLERY_W / 2, pt + 8, 0xFFE0FFE0);

        RxGallery g = GALLERIES.get(imgKey);
        int total = (g == null) ? 0 : g.frames.size();
        int viewNo = (g == null || total == 0) ? 0 : Math.min(g.view + 1, total);
        drawString(
            fontRendererObj,
            viewNo + " / " + total + (g != null && g.follow ? "  (live)" : ""),
            left + 8,
            pt + 60,
            COL_LABEL);

        // Thumbnail strip of the held frames.
        if (g != null) {
            int ty = pt + 76;
            for (int i = 0; i < g.frames.size(); i++) {
                boolean current = i == g.view;
                if (current) {
                    drawRect(left + 6, ty - 1, left + GALLERY_W - 6, ty + 49, 0x402A8E3A);
                }
                drawArgbThumb(g.frames.get(i), left + 10, ty, 48, 48);
                drawString(fontRendererObj, "#" + (i + 1), left + 64, ty + 20, COL_LABEL);
                ty += 52;
                if (ty > pt + panelHeight - 52) {
                    break;
                }
            }
        }
    }

    // ----- Helpers ---------------------------------------------------------

    /** Lay a row of buttons left to right at a fixed gap; hide them all if not shown. */
    private void positionRow(boolean show, int x, int y, ScopeButton... row) {
        int cx = x;
        for (ScopeButton b : row) {
            place(b, cx, y, show, b.width, b.height);
            cx += b.width + 4;
        }
    }

    private void place(ScopeButton b, int x, int y, boolean show, int w, int h) {
        b.xPosition = x;
        b.yPosition = y;
        b.width = w;
        b.height = h;
        b.visible = show;
        b.enabled = show;
    }

    private String kindLabel(byte kind) {
        int k = kind & 0xFF;
        if (k == TileSstvStation.KIND_MAP) {
            return "Map";
        }
        if (k == TileSstvStation.KIND_TERRAIN) {
            return "Area";
        }
        if (k >= 0 && k < PATTERN_LABELS.length) {
            return PATTERN_LABELS[k];
        }
        return "?";
    }

    /**
     * Resolve a queue slot's thumbnail pixels. Pattern slots regenerate the
     * picture locally from the codec, so the preview is exact without any
     * transfer; map slots return null and draw as a placeholder because
     * their pixels never leave the server.
     */
    private int[][] thumbFor(byte kind) {
        int k = kind & 0xFF;
        if (k == TileSstvStation.KIND_MAP || k == TileSstvStation.KIND_TERRAIN) {
            return null;
        }
        int[][] cached = PATTERN_CACHE.get(k);
        if (cached == null && k >= 0 && k < SstvCodec.PatternKind.values().length) {
            cached = SstvCodec.PatternKind.values()[k].generate();
            PATTERN_CACHE.put(k, cached);
        }
        return cached;
    }

    /** Draw a 0xRRGGBB image array scaled into the box, or a placeholder if null. */
    private void drawThumb(int[][] img, int x, int y, int w, int h, String placeholder) {
        if (img == null) {
            drawRect(x, y, x + w, y + h, 0xFF101510);
            drawCenteredString(fontRendererObj, placeholder, x + w / 2, y + h / 2 - 4, COL_LABEL);
            return;
        }
        for (int ty = 0; ty < h; ty++) {
            int sy = ty * SstvCodec.H / h;
            for (int tx = 0; tx < w; tx++) {
                int sx = tx * SstvCodec.W / w;
                drawRect(x + tx, y + ty, x + tx + 1, y + ty + 1, 0xFF000000 | img[sy][sx]);
            }
        }
    }

    /** Draw a packed-ARGB W*H frame scaled into the box. */
    private void drawArgbThumb(int[] frame, int x, int y, int w, int h) {
        if (frame == null) {
            drawRect(x, y, x + w, y + h, 0xFF000000);
            return;
        }
        for (int ty = 0; ty < h; ty++) {
            int sy = ty * SstvCodec.H / h;
            for (int tx = 0; tx < w; tx++) {
                int sx = tx * SstvCodec.W / w;
                int c = frame[sy * SstvCodec.W + sx];
                drawRect(x + tx, y + ty, x + tx + 1, y + ty + 1, c == 0 ? 0xFF000000 : c);
            }
        }
    }
}
