package io.hertzian.dynamics.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.tile.TileMicrophone;

/**
 * Microphone block. Captures audio through push-to-talk by the
 * nearest player and drains it each tick into nearby transmitters.
 */
public final class BlockMicrophone extends BlockContainer {

    public BlockMicrophone() {
        super(Material.iron);
        setHardness(1.0F);
        setResistance(4.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".microphone");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileMicrophone();
    }

    /**
     * Store the placement facing so the TESR can orient the model
     * toward the way it was placed instead of a fixed direction.
     */
    @Override
    public void onBlockPlacedBy(net.minecraft.world.World world, int x, int y, int z,
        net.minecraft.entity.EntityLivingBase placer, net.minecraft.item.ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        BlockFacing.applyFacing(world, x, y, z, placer);
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
