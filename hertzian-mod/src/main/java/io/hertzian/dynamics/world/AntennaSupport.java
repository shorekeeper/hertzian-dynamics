package io.hertzian.dynamics.world;

import net.minecraft.world.World;

import io.hertzian.dynamics.ModBlocks;

/**
 * Resolves the antenna mast stacked above a radio block into an
 * effective height and gain. Stacking {@link ModBlocks#antenna} sections
 * directly on top of a transmitter, receiver or relay both raises its
 * effective antenna height, which extends the radio horizon through the
 * curvature model, and adds gain, which lifts the link budget. Taller
 * mast, longer reach, literally built out of blocks.
 */
public final class AntennaSupport {

    private AntennaSupport() {}

    /** Maximum mast height counted, in sections. */
    public static final int MAX_SECTIONS = 32;

    /** Count contiguous antenna sections directly above (x, y, z). */
    public static int countAbove(World world, int x, int y, int z) {
        if (world == null) return 0;
        int n = 0;
        for (int i = 1; i <= MAX_SECTIONS; i++) {
            if (world.getBlock(x, y + i, z) == ModBlocks.antenna) n++;
            else break;
        }
        return n;
    }

    /**
     * Linear antenna gain from the section count. Mirrors the rule in
     * BlockAntenna's documentation: 1.5 dB per section up to a 12 dB cap
     * at eight sections. Returns 1.0 (0 dB) for a bare radio with no mast.
     */
    public static float gainFromSections(int sections) {
        int s = Math.min(8, sections);
        return (float) Math.pow(10.0, (s * 1.5) / 10.0);
    }
}
