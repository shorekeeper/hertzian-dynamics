package io.hertzian.dynamics.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.gui.GuiIds;
import io.hertzian.dynamics.tile.TileJammer;

/**
 * Wideband noise jammer block.
 *
 * <p>
 * State and interaction:
 * <ul>
 * <li>The jammer is active by default on placement. While active
 * it registers a noise emission with the spectrum manager
 * every tick, drowning nearby receivers tuned to its
 * carrier and bandwidth.</li>
 * <li>Right-clicking toggles the active flag. The toggle goes
 * through {@link TileJammer#setActive} which both updates
 * NBT and asks {@link io.hertzian.dynamics.world.WorldRfState}
 * to drop the emission slot, so the rf-core side mirrors
 * the UI state immediately.</li>
 * <li>A future versions may add ELN-driven activation;
 * {@code setActive(boolean)} is intentionally kept as the
 * public toggle path so a ELN hook can call it with an
 * explicit boolean without going through the "toggle"
 * semantics this block uses.</li>
 * </ul>
 */
public final class BlockJammer extends BlockContainer {

    public BlockJammer() {
        super(Material.iron);
        setHardness(2.5F);
        setResistance(15.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".jammer");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileJammer();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        ((EntityPlayer) placer).addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.RED + "[Jammer] Active. "
                    + EnumChatFormatting.GRAY
                    + "Right-click to toggle on/off."));
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        player.openGui(io.hertzian.dynamics.HertzianDynamics.INSTANCE, GuiIds.JAMMER, world, x, y, z);
        return true;
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
