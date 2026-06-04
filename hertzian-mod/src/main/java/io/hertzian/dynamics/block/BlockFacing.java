package io.hertzian.dynamics.block;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Horizontal facing helper for the mod's tile-entity blocks.
 *
 * <p>
 * Every radio block renders through a TESR with vanilla render type
 * {@code -1}, which means the block's metadata nibble carries no vanilla
 * meaning and is free for us to use. We store the placement facing there:
 * a value of 0 to 3 for the four horizontal directions. The block sets it
 * in {@code onBlockPlacedBy} from the placer's yaw, and the TESR reads it
 * back to rotate the model so the device points the way it was placed
 * rather than always facing one fixed direction.
 *
 * <p>
 * The facing is metadata rather than a tile-entity field on purpose:
 * metadata is already saved with the chunk, already synced to the client,
 * and survives a tile-entity instance being recreated on chunk reload,
 * so it needs no extra NBT or description-packet plumbing. The downside,
 * that only four directions fit, is fine for desktop instruments that sit
 * on a surface and only ever face along the compass.
 */
public final class BlockFacing {

    private BlockFacing() {}

    /**
     * Quantise the placer's yaw to a horizontal facing and write it into
     * the block metadata. Server side only; on the client the call is a
     * no-op because metadata is server-authoritative and arrives through
     * the block update the notify flag triggers. Safe to call with a null
     * or non-living placer (dispenser, command), in which case the
     * default facing 0 is kept.
     */
    public static void applyFacing(World world, int x, int y, int z, EntityLivingBase placer) {
        if (world == null || world.isRemote || placer == null) return;
        // Standard vanilla yaw-to-facing quantisation. The result is the
        // compass quadrant the player was looking along when placing.
        int facing = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        world.setBlockMetadataWithNotify(x, y, z, facing, 2);
    }

    /**
     * Y-axis rotation in degrees for a stored facing metadata value. The
     * model is rotated by this amount around the block centre in the
     * TESR. The mapping is the four quadrants at 90 degree steps; if a
     * given model's authored front points the wrong way, flip
     * {@link #FRONT_OFFSET_DEGREES} rather than editing every block.
     */
    public static float yawForMeta(int meta) {
        return (meta & 3) * 90.0F + FRONT_OFFSET_DEGREES;
    }

    /**
     * Global offset applied on top of the per-meta rotation, so the
     * authored front of the OBJ models lines up with the placement
     * direction. Models in this pack are authored facing south (+Z); the
     * 180 degree offset turns that toward the player who placed the
     * block. Adjust once here if a future model batch is authored facing
     * the other way.
     */
    private static final float FRONT_OFFSET_DEGREES = 180.0F;
}
