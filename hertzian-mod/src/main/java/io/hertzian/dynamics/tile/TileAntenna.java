package io.hertzian.dynamics.tile;

import net.minecraft.tileentity.TileEntity;

/**
 * Tile entity for antenna sections. Holds no rf-core handle of its
 * own; the owning transmitter or receiver tile walks the vertical
 * column to count stacked sections during its own tick. Keeping
 * the antenna gain rule on the owner side avoids a tangled
 * ownership graph and matches the physical mental model: the
 * antenna is part of one radio, not a station of its own.
 */
public final class TileAntenna extends TileEntity {
    // Intentionally empty. Beam-direction state for a Yagi-style
    // antenna would attach here.
}
