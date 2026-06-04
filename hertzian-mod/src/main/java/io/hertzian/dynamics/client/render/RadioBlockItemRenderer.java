package io.hertzian.dynamics.client.render;

import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Universal item renderer for our block items. Uses the same OBJ
 * model the TESR uses, but applies view-specific transforms so
 * the model reads correctly as a 16x16 hotbar icon, a held tool
 * in first/third person, and a dropped entity on the ground.
 *
 * <p>
 * <p>
 * Fit-to-cell scaling
 * ----------------------
 * The inventory is drawn in a 2D orthographic projection scaled so
 * the current slot occupies a 16 by 16 unit region. The renderer
 * reads the model bounding box from {@link ObjModelRegistry.Entry}
 * and derives the scale from the largest extent, so a model of any
 * authored size is shrunk to sit inside the cell and recentred on
 * the bounding-box centre so it is framed rather than corner
 * anchored. A fixed scale factor would suit only a model exactly one
 * unit on a side and would draw an antenna mast or a tall instrument
 * case past the slot edges. The held and dropped views use the same
 * bounds so a large model does not dwarf the player's hand.
 */
public final class RadioBlockItemRenderer implements IItemRenderer {

    private final String modelKind;

    public RadioBlockItemRenderer(String modelKind) {
        this.modelKind = modelKind;
    }

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        return true;
    }

    @Override
    public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        // We do every transform ourselves, including the
        // inventory's iconic 3D block pose. Returning false puts us
        // in full control of the GL state.
        return false;
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        ObjModelRegistry.Entry entry = ObjModelRegistry.get(modelKind);
        if (entry.failed || entry.model == null) return;

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        boolean lightingWasOn = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        switch (type) {
            case INVENTORY:
                renderInventory(entry);
                break;
            case EQUIPPED:
            case EQUIPPED_FIRST_PERSON:
                renderEquipped(entry);
                break;
            case ENTITY:
                renderDropped(entry);
                break;
            default:
                renderEquipped(entry);
                break;
        }

        if (lightingWasOn) GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPopMatrix();
    }

    /**
     * Isometric inventory icon, scaled to fit the slot. The base
     * factor of 9 (rather than the slot's full 16) accounts for the
     * isometric projection widening the silhouette: a unit cube at
     * factor 9 reaches roughly 12 to 13 pixels across after rotation,
     * leaving a clean margin inside the 16-pixel cell. Dividing by
     * the model's largest extent makes oversized models shrink to the
     * same fit. The negative Y in the scale inverts the inventory's
     * downward Y; the final translate recentres on the model centroid.
     */
    private void renderInventory(ObjModelRegistry.Entry entry) {
        float s = 9.0f / entry.maxExtent();
        GL11.glTranslatef(8f, 8f, 0f);
        GL11.glScalef(s, -s, s);
        GL11.glRotatef(30f, 1f, 0f, 0f);
        GL11.glRotatef(-45f, 0f, 1f, 0f);
        GL11.glTranslatef(-entry.centerX(), -entry.centerY(), -entry.centerZ());

        bindTextureLocal(entry);
        entry.model.renderAll();
    }

    /**
     * Held in hand. Held-item space puts a unit cube within roughly
     * [0, 1]. A model larger than one unit is scaled down so it does
     * not dwarf the hand; a model at or below one unit keeps unit
     * scale. The model is recentred on its centroid so the grip sits
     * at its middle, and tilted slightly so the front face shows.
     */
    private void renderEquipped(ObjModelRegistry.Entry entry) {
        float s = Math.min(1.0f, 1.2f / entry.maxExtent());
        GL11.glTranslatef(0.5f, 0.5f, 0.5f);
        GL11.glScalef(s, s, s);
        GL11.glRotatef(20f, 0f, 1f, 0f);
        GL11.glTranslatef(-entry.centerX(), -entry.centerY(), -entry.centerZ());

        bindTextureLocal(entry);
        entry.model.renderAll();
    }

    /**
     * Dropped on the ground. The entity render path positions us at
     * the entity centre and spins us around world Y, so we only need
     * to fit the model to about a unit and recentre it about that
     * pivot so it spins around its own centroid.
     */
    private void renderDropped(ObjModelRegistry.Entry entry) {
        float s = Math.min(1.0f, 1.0f / entry.maxExtent());
        GL11.glScalef(s, s, s);
        GL11.glTranslatef(-entry.centerX(), -entry.centerY(), -entry.centerZ());
        bindTextureLocal(entry);
        entry.model.renderAll();
    }

    private static void bindTextureLocal(ObjModelRegistry.Entry entry) {
        net.minecraft.client.Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(entry.texture);
    }
}
