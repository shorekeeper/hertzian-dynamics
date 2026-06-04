package io.hertzian.dynamics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import io.hertzian.dynamics.audio.ClientAudioBridge;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.player.HertzianPlayerRadio;

/**
 * In-game HUD strip for the carried radio. Drawn next to the hotbar, the
 * way an offhand cell sits beside it, but custom so it can show radio
 * state rather than a bare item. It renders two cells, the carried radio
 * and the headset, plus a transmit status lamp:
 *
 * <ul>
 * <li>The radio cell shows the active radio icon. Off, it is dimmed.</li>
 * <li>The headset cell shows the headset icon when one is carried, and an
 * empty frame otherwise, so the operator can tell at a glance whether
 * their microphone bleed is suppressed.</li>
 * <li>The lamp reads TX while the player is keyed, RX when the radio is
 * receiving audio, ON when powered and idle, OFF when off.</li>
 * </ul>
 *
 * The strip only draws when the player carries a radio or a headset, so it
 * stays out of the way for players not using the gear. It hides while the
 * vanilla GUI is hidden. Transmit state is read from
 * {@link PttKeyHandler#isTransmitting()}; receive activity is inferred
 * from the audio bridge waveform the client is already playing for this
 * radio.
 */
public final class RadioHudOverlay extends Gui {

    private static final int COL_FRAME = 0xFF2A6E2A;
    private static final int COL_CELL_BG = 0xFF0A0F0A;
    private static final int COL_DIM = 0xA0000000;
    private static final int COL_OFF = 0xFF1A2A1A;
    private static final int COL_TX = 0xFFFF4040;
    private static final int COL_RX = 0xFF44FF66;
    private static final int COL_ON = 0xFF2A8E3A;
    private static final int COL_LABEL = 0xFFAACCAA;

    private final RenderItem itemRenderer = new RenderItem();

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        if (mc.gameSettings.hideGUI) return;

        EntityPlayer player = mc.thePlayer;
        HertzianPlayerRadio gear = HertzianPlayerRadio.get(player);
        ItemStack radio = HertzianPlayerRadio.activeRadio(player);
        boolean hasHeadset = gear != null && gear.hasHeadset();
        ItemStack headsetStack = (gear != null) ? gear.getStack(HertzianPlayerRadio.SLOT_HEADSET) : null;

        if (radio == null && !hasHeadset) return;

        boolean powered = radio != null && ItemHandheldRadio.isPowered(radio);
        boolean tx = powered && PttKeyHandler.isTransmitting();
        boolean rx = false;
        if (powered && !tx) {
            float[] wave = ClientAudioBridge.latestWaveform(ClientAudioBridge.handheldKey(player.getUniqueID()));
            if (wave != null) {
                for (float v : wave) {
                    if (Math.abs(v) > 0.02f) {
                        rx = true;
                        break;
                    }
                }
            }
        }

        ScaledResolution res = event.resolution;
        int w = res.getScaledWidth();
        int h = res.getScaledHeight();

        // Place the strip just left of the hotbar, aligned to its row.
        int barX = w / 2 - 91;
        int cellY = h - 20;
        int radioX = barX - 24;
        int headsetX = radioX - 20;

        drawCell(headsetX, cellY);
        drawCell(radioX, cellY);

        if (headsetStack != null) {
            renderIcon(mc, headsetStack, headsetX, cellY);
        }
        if (radio != null) {
            renderIcon(mc, radio, radioX, cellY);
            if (!powered) {
                // Dim the radio icon when the set is off.
                drawRect(radioX, cellY, radioX + 16, cellY + 16, COL_DIM);
            }
        }

        // Status lamp above the radio cell, with a short label.
        int lampColor;
        String label;
        if (!powered) {
            lampColor = COL_OFF;
            label = "OFF";
        } else if (tx) {
            lampColor = COL_TX;
            label = "TX";
        } else if (rx) {
            lampColor = COL_RX;
            label = "RX";
        } else {
            lampColor = COL_ON;
            label = "ON";
        }
        int lampX = radioX;
        int lampY = cellY - 8;
        drawRect(lampX - 1, lampY - 1, lampX + 7, lampY + 7, COL_FRAME);
        drawRect(lampX, lampY, lampX + 6, lampY + 6, lampColor);
        mc.fontRenderer.drawString(label, lampX + 10, lampY - 1, COL_LABEL);

        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private void drawCell(int x, int y) {
        drawRect(x - 1, y - 1, x + 17, y + 17, COL_FRAME);
        drawRect(x, y, x + 16, y + 16, COL_CELL_BG);
    }

    /**
     * Render an item icon into the HUD. Item rendering needs the standard
     * GUI item lighting set up and torn down around it, otherwise the icon
     * comes out flat-shaded or tints later draw calls.
     */
    private void renderIcon(Minecraft mc, ItemStack stack, int x, int y) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        itemRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
