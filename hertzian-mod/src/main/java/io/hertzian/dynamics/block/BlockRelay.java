package io.hertzian.dynamics.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.gui.GuiIds;
import io.hertzian.dynamics.tile.TileRelay;

/**
 * Relay (repeater) block. Hosts a {@link TileRelay} that receives on one
 * frequency and re-broadcasts on another, force loading its chunk while
 * active so it keeps relaying for players who have walked away.
 */
public final class BlockRelay extends BlockContainer {

    public BlockRelay() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".relay");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileRelay();
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

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        player.openGui(io.hertzian.dynamics.HertzianDynamics.INSTANCE, GuiIds.RELAY, world, x, y, z);
        return true;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public int getRenderType() {
        return -1;
    }
}
