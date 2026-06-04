package io.hertzian.dynamics.client.render;

import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import io.hertzian.dynamics.block.BlockFacing;

/**
 * Generic block TESR. One instance per block kind, parameterised
 * by the OBJ model key.
 *
 * <p>
 * Model authoring convention
 * -----------------------------
 * The model is expected to be centred on its X and Z axes (the
 * footprint spans -0.5 to +0.5 on both) and to stand on the floor
 * of its block cell on Y (the height spans 0 to 1, with Y=0 being
 * the bottom of the block and Y=1 the top). This matches the
 * natural Blender authoring workflow for a "block-sized prop
 * sitting on the ground" and removes the need for per-model
 * origin offsets.
 *
 * <p>
 * Facing
 * --------
 * The model is rotated around the block's vertical centre by the
 * placement facing stored in the block metadata (see
 * {@link BlockFacing}). Because the authored footprint is centred on
 * X and Z, a yaw rotation about that centre spins the device in
 * place without shifting it off its cell. The metadata is read from
 * the world rather than the tile entity's cached copy so a freshly
 * placed block picks up the right facing on its first frame.
 *
 * <p>
 * If a particular block needs a different convention (for
 * example a ceiling-mounted antenna that hangs from Y=1 down),
 * subclass this TESR and override {@link #applyModelTransform}.
 */
public class RadioBlockTESR extends TileEntitySpecialRenderer {

    private final String modelKind;

    public RadioBlockTESR(String modelKind) {
        this.modelKind = modelKind;
    }

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partialTick) {
        ObjModelRegistry.Entry entry = ObjModelRegistry.get(modelKind);
        if (entry.failed || entry.model == null) return;

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        applyModelTransform(te, x, y, z, partialTick);
        bindTexture(entry.texture);
        entry.model.renderAll();

        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPopMatrix();
    }

    /**
     * Place the model in world space. Default puts the model's
     * authored centre (X=0, Z=0, Y=0 floor) over the block's
     * (centre, floor, centre), then spins it by the placement
     * facing about the vertical axis.
     */
    protected void applyModelTransform(TileEntity te, double x, double y, double z, float partialTick) {
        GL11.glTranslated(x + 0.5, y, z + 0.5);
        int meta = 0;
        if (te.getWorldObj() != null) {
            // Read the live metadata so the facing is correct on the
            // first frame after placement, before the tile entity has
            // cached its block metadata.
            meta = te.getWorldObj()
                .getBlockMetadata(te.xCoord, te.yCoord, te.zCoord);
        }
        GL11.glRotatef(BlockFacing.yawForMeta(meta), 0f, 1f, 0f);
    }
}
