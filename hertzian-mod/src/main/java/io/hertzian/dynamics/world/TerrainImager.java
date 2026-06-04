package io.hertzian.dynamics.world;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.world.World;

import io.hertzian.dynamics.core.SstvCodec;

/**
 * Renders a top-down view of the terrain around the SSTV station into a
 * 96 by 96 RGB image, the dynamic custom-imagery source that gives the
 * mode a point beyond the built-in test patterns.
 *
 * <p>
 * Why this is the answer to "what is SSTV for"
 * The built-in patterns are only a test card. Real slow-scan television
 * exists to send pictures of something, and a map alone is a static drawn
 * source. This produces a live aerial photograph of the station's
 * surroundings: it is genuine reconnaissance imagery that changes as the
 * world does, so transmitting it over a radio link is a real act of
 * sharing information rather than a demonstration.
 *
 * <p>
 * Server-safe by construction
 * The image is built entirely from block data the server already holds, so
 * nothing is uploaded from any client. The cost is bounded and paid once,
 * at the moment the frame is queued: one height lookup and one map-colour
 * read per pixel, 96 by 96 of them, over an area that is loaded because the
 * operator is standing in it. There is no per-tick rendering.
 *
 * <p>
 * Rendering
 * Each pixel is one block column, centred on the station. The column's
 * colour is the map colour of its top block, shaded by comparing the
 * column height to its north neighbour, the same emboss trick vanilla maps
 * use to give relief a sense of slope. An unloaded or empty column reads as
 * black, which frames the imaged area cleanly.
 */
public final class TerrainImager {

    private TerrainImager() {}

    /**
     * Build a 96 by 96 aerial image of the terrain centred on the given
     * block column. One world block maps to one image pixel, so the view
     * covers a 96 by 96 block area around the station.
     */
    public static int[][] fromArea(World world, int centerX, int centerZ) {
        final int w = SstvCodec.W;
        final int h = SstvCodec.H;
        int[][] out = new int[h][w];
        int halfX = w / 2;
        int halfZ = h / 2;
        for (int iz = 0; iz < h; iz++) {
            int worldZ = centerZ - halfZ + iz;
            for (int ix = 0; ix < w; ix++) {
                int worldX = centerX - halfX + ix;
                out[iz][ix] = columnColor(world, worldX, worldZ);
            }
        }
        return out;
    }

    /**
     * Colour of one block column: the map colour of its top block, shaded
     * by the height difference to the column one step north so slopes read
     * as light and shade. Returns black for an empty or unreadable column.
     */
    private static int columnColor(World world, int worldX, int worldZ) {
        int topY = topBlockY(world, worldX, worldZ);
        if (topY <= 0) {
            return 0x000000;
        }
        MapColor mc = mapColorAt(world, worldX, topY, worldZ);
        if (mc == null) {
            return 0x000000;
        }

        // Emboss: compare to the north neighbour's height. Higher north
        // means this column faces away and reads darker; lower north means
        // it catches light and reads brighter.
        int northY = topBlockY(world, worldX, worldZ - 1);
        int shade;
        if (northY > topY) {
            shade = 0;
        } else if (northY < topY) {
            shade = 2;
        } else {
            shade = 1;
        }
        return MapImageReader.shadeRgb(mc.colorValue, shade);
    }

    /**
     * Y of the topmost solid or liquid block at the column, or 0 if the
     * column is empty or not loaded. {@link World#getTopSolidOrLiquidBlock}
     * returns the y just above the surface, so the surface block sits one
     * below.
     */
    private static int topBlockY(World world, int worldX, int worldZ) {
        int y = world.getTopSolidOrLiquidBlock(worldX, worldZ) - 1;
        return Math.max(0, y);
    }

    /** Map colour of the block at the position, with defensive fallbacks. */
    private static MapColor mapColorAt(World world, int worldX, int worldY, int worldZ) {
        Block block = world.getBlock(worldX, worldY, worldZ);
        if (block == null) {
            return null;
        }
        int meta = world.getBlockMetadata(worldX, worldY, worldZ);
        try {
            MapColor mc = block.getMapColor(meta);
            return mc != null ? mc : MapColor.airColor;
        } catch (Throwable t) {
            // Some modded blocks throw on getMapColor with an unexpected
            // meta; treat those as air rather than failing the image.
            return MapColor.airColor;
        }
    }
}
