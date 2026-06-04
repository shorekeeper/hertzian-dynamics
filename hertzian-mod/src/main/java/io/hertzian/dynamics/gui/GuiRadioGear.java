package io.hertzian.dynamics.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import io.hertzian.dynamics.inventory.ContainerRadioGear;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.player.HertzianPlayerRadio;

/**
 * Gear screen. Shows the radio and headset carry slots above the player
 * inventory, drawn in the flat scope palette the other panels use so no
 * texture asset is required. A power button toggles the carried radio
 * through the container button path.
 *
 * The slot frame and label positions here mirror the slot coordinates in
 * {@link ContainerRadioGear}; the two must agree or the drawn frames and
 * the clickable slots drift apart. The player inventory is placed low
 * enough that no panel text overlaps it.
 *
 * Tuning and the radio settings (frequency, mode, sidetone, and so on)
 * are still edited by holding the radio and right-clicking it; this screen
 * only manages where the radio and headset are carried and whether the
 * carried radio is on.
 */
public final class GuiRadioGear extends GuiContainer {

    private static final int COL_PANEL_BG = 0xFF0A0F0A;
    private static final int COL_PANEL_BORDER = 0xFF2A6E2A;
    private static final int COL_SLOT_BG = 0xFF1A1A0A;
    private static final int COL_LABEL = 0xFFAACCAA;
    private static final int COL_SAFE = 0xFF44FF66;

    private static final int BTN_POWER = 0;

    private final EntityPlayer player;

    public GuiRadioGear(EntityPlayer player) {
        super(new ContainerRadioGear(player, HertzianPlayerRadio.get(player)));
        this.player = player;
        this.xSize = 176;
        this.ySize = 176;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        buttonList.add(new GuiButton(BTN_POWER, guiLeft + 8, guiTop + 40, 60, 16, "Power"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_POWER) {
            mc.playerController.sendEnchantPacket(inventorySlots.windowId, 0);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // Screen space: this layer runs before GuiContainer applies its
        // guiLeft/guiTop translate for the slots.
        drawRect(guiLeft - 2, guiTop - 2, guiLeft + xSize + 2, guiTop + ySize + 2, COL_PANEL_BORDER);
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, COL_PANEL_BG);

        drawSlotFrame(guiLeft + 44, guiTop + 18);
        drawSlotFrame(guiLeft + 116, guiTop + 18);
        fontRendererObj.drawString("Radio", guiLeft + 34, guiTop + 7, COL_LABEL);
        fontRendererObj.drawString("Headset", guiLeft + 102, guiTop + 7, COL_LABEL);

        HertzianPlayerRadio gear = HertzianPlayerRadio.get(player);
        boolean on = false;
        if (gear != null) {
            ItemStack radio = gear.radio();
            on = radio != null && ItemHandheldRadio.isPowered(radio);
        }
        fontRendererObj.drawString(on ? "ON" : "OFF", guiLeft + 74, guiTop + 44, on ? COL_SAFE : COL_LABEL);
        fontRendererObj.drawString("Hold the radio to change settings.", guiLeft + 8, guiTop + 62, COL_LABEL);
    }

    private void drawSlotFrame(int x, int y) {
        drawRect(x - 1, y - 1, x + 17, y + 17, COL_PANEL_BORDER);
        drawRect(x, y, x + 16, y + 16, COL_SLOT_BG);
    }
}