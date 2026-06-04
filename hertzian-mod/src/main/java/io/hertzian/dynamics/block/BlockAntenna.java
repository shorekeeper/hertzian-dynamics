package io.hertzian.dynamics.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.tile.TileAntenna;

/**
 * Antenna section. Stacked vertically to form a multi-block
 * antenna. The owning transmitter or receiver discovers stacked
 * sections during its tick and uses the count both as an antenna
 * gain multiplier and as an effective height. The gain rule adds
 * 1.5 dB per section up to a 12 dB cap at eight sections.
 */
public final class BlockAntenna extends BlockContainer {

    public BlockAntenna() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".antenna");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileAntenna();
    }

    /**
     * Disable the vanilla cube renderer so the TESR's OBJ model
     * is the only thing drawn. Without this the standard cube
     * mesh appears underneath the model, producing a "missing
     * texture" cube visible through the gaps of any non-solid
     * OBJ geometry.
     */
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    /**
     * Render type -1 tells the vanilla block renderer "do not
     * render"; rendering is the TESR's job entirely.
     */
    @Override
    public int getRenderType() {
        return -1;
    }
}
