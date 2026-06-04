package io.hertzian.dynamics.gui.widget;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Minimal vertically scrolling list rendered in the scope palette.
 * Built for the transmitter station track browser and the playlist
 * side panel, which is needed to show as scrolling lists
 * rather than banks of vanilla buttons. The widget owns no Minecraft
 * list machinery ({@code GuiSlot} and friends are awkward inside a
 * custom panel); the host GUI forwards draw, click, drag and wheel
 * events to it explicitly.
 *
 * <p>
 * Scrolling is pixel based with a drag handle on the right edge,
 * the closest match to the classic media-player track pane the design
 * asked for. The wheel scrolls by whole rows; the handle scrolls
 * continuously.
 */
public final class ScrollList extends Gui {

    private static final int COL_BG = 0xFF0C150C;
    private static final int COL_BORDER = 0xFF2A6E2A;
    private static final int COL_ROW_ALT = 0xFF10210F;
    private static final int COL_ROW_SEL = 0xFF1E5230;
    private static final int COL_ROW_HOVER = 0xFF17401F;
    private static final int COL_TEXT = 0xFFBFE8BF;
    private static final int COL_TEXT_SEL = 0xFFFFFFFF;
    private static final int COL_TRACK = 0xFF0A0F0A;
    private static final int COL_HANDLE = 0xFF2A6E2A;

    private static final int SCROLLBAR_W = 5;

    private final FontRenderer font;
    private final List<String> items = new ArrayList<>();

    private int x, y, w, h;
    private int rowHeight = 12;
    private int scrollPx = 0;
    private int selected = -1;
    private boolean dragging = false;

    public ScrollList(FontRenderer font) {
        this.font = font;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        clampScroll();
    }

    public void setRowHeight(int rh) {
        this.rowHeight = Math.max(8, rh);
    }

    public void setItems(List<String> labels) {
        items.clear();
        if (labels != null) items.addAll(labels);
        if (selected >= items.size()) selected = -1;
        clampScroll();
    }

    public void clear() {
        items.clear();
        selected = -1;
        scrollPx = 0;
    }

    public int size() {
        return items.size();
    }

    public int selectedIndex() {
        return selected;
    }

    public String selectedValue() {
        return (selected >= 0 && selected < items.size()) ? items.get(selected) : null;
    }

    public void setSelected(int i) {
        this.selected = (i >= 0 && i < items.size()) ? i : -1;
    }

    public void selectByValue(String value) {
        selected = -1;
        if (value == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i)
                .equals(value)) {
                selected = i;
                return;
            }
        }
    }

    public boolean contains(int mx, int my) {
        return inside(mx, my);
    }

    private boolean inside(int mx, int my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int contentHeight() {
        return items.size() * rowHeight;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - h);
    }

    private void clampScroll() {
        int max = maxScroll();
        if (scrollPx < 0) scrollPx = 0;
        if (scrollPx > max) scrollPx = max;
    }

    /** Scroll by whole rows. Positive scrolls toward earlier items. */
    public void scrollRows(int rows) {
        scrollPx -= rows * rowHeight;
        clampScroll();
    }

    /** Returns the clicked row index, or -1 if no row was hit. */
    public int mouseClicked(int mx, int my, int button) {
        if (button != 0 || !inside(mx, my)) return -1;
        if (mx >= x + w - SCROLLBAR_W) {
            dragScrollbarTo(my);
            dragging = true;
            return -1;
        }
        int row = (my - y + scrollPx) / rowHeight;
        if (row >= 0 && row < items.size()) {
            selected = row;
            return row;
        }
        return -1;
    }

    public void mouseReleased() {
        dragging = false;
    }

    public void mouseDragged(int mx, int my) {
        if (dragging) dragScrollbarTo(my);
    }

    private void dragScrollbarTo(int my) {
        int max = maxScroll();
        if (max <= 0) {
            scrollPx = 0;
            return;
        }
        float t = (float) (my - y) / (float) h;
        scrollPx = (int) (t * max);
        clampScroll();
    }

    public void draw(int mx, int my) {
        drawRect(x - 1, y - 1, x + w + 1, y + h + 1, COL_BORDER);
        drawRect(x, y, x + w, y + h, COL_BG);

        int first = scrollPx / rowHeight;
        int yOffset = -(scrollPx % rowHeight);
        boolean hoverInside = inside(mx, my) && mx < x + w - SCROLLBAR_W;

        for (int i = first; i < items.size(); i++) {
            int ry = y + yOffset + (i - first) * rowHeight;
            if (ry >= y + h) break;
            int top = Math.max(ry, y);
            int bottom = Math.min(ry + rowHeight, y + h);

            int rowColour;
            if (i == selected) rowColour = COL_ROW_SEL;
            else if (hoverInside && my >= ry && my < ry + rowHeight) rowColour = COL_ROW_HOVER;
            else if ((i & 1) == 1) rowColour = COL_ROW_ALT;
            else rowColour = 0;

            if (rowColour != 0 && bottom > top) {
                drawRect(x, top, x + w - SCROLLBAR_W, bottom, rowColour);
            }

            int ty = ry + (rowHeight - 8) / 2;
            if (ty >= y - 4 && ty <= y + h - 4) {
                String label = trimToWidth(items.get(i), w - SCROLLBAR_W - 6);
                int textColour = (i == selected) ? COL_TEXT_SEL : COL_TEXT;
                font.drawString(label, x + 4, ty, textColour);
            }
        }

        drawScrollbar();
    }

    private void drawScrollbar() {
        int barX0 = x + w - SCROLLBAR_W;
        drawRect(barX0, y, x + w, y + h, COL_TRACK);
        int max = maxScroll();
        if (max <= 0) {
            drawRect(barX0, y, x + w, y + h, COL_HANDLE);
            return;
        }
        int handleH = Math.max(12, (int) ((float) h * h / contentHeight()));
        if (handleH > h) handleH = h;
        int travel = h - handleH;
        int handleY = y + (int) ((float) scrollPx / max * travel);
        drawRect(barX0, handleY, x + w, handleY + handleH, COL_HANDLE);
    }

    private String trimToWidth(String s, int width) {
        if (font.getStringWidth(s) <= width) return s;
        String ell = "...";
        int ellW = font.getStringWidth(ell);
        StringBuilder sb = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = font.getCharWidth(s.charAt(i));
            if (acc + cw + ellW > width) break;
            sb.append(s.charAt(i));
            acc += cw;
        }
        return sb.append(ell)
            .toString();
    }
}
