package io.hertzian.dynamics.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.gui.GuiIds;
import io.hertzian.dynamics.tile.TileSpectrumAnalyzer;

/**
 * Spectrum analyzer (SDR-style) block. Right-click opens a GUI
 * showing the live band activity centred on a configurable
 * frequency, in waterfall + magnitude trace.
 *
 * <p>
 * The block hosts a {@link TileSpectrumAnalyzer} that runs a
 * scanning loop on the server: every 500 ms it sweeps the
 * configured span by re-tuning a hidden receiver across a number
 * of frequency bins and recording the average magnitude per bin.
 * Results travel to nearby clients via {@code PacketSpectrumData}.
 */
public final class BlockSpectrumAnalyzer extends BlockContainer {

    public BlockSpectrumAnalyzer() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".spectrum_analyzer");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileSpectrumAnalyzer();
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
        player.openGui(io.hertzian.dynamics.HertzianDynamics.INSTANCE, GuiIds.SPECTRUM_ANALYZER, world, x, y, z);
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
