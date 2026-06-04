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
import io.hertzian.dynamics.tile.TileRadioTransmitter;

/**
 * Radio transmitter block. Houses a {@link TileRadioTransmitter}.
 * Prints its current parameters on placement so the player can
 * verify they match the intended receiver tuning.
 */
public final class BlockRadioTransmitter extends BlockContainer {

    public BlockRadioTransmitter() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".radio_transmitter");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileRadioTransmitter();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        BlockFacing.applyFacing(world, x, y, z, placer);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileRadioTransmitter)) return;
        TileRadioTransmitter tx = (TileRadioTransmitter) te;
        ((EntityPlayer) placer).addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN + "[Transmitter] On "
                    + String.format("%.3f", tx.carrierHz() / 1.0e6)
                    + " MHz "
                    + tx.modulation()
                        .name()
                    + " at "
                    + tx.txPowerW()
                    + " W"));
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        player.openGui(io.hertzian.dynamics.HertzianDynamics.INSTANCE, GuiIds.TRANSMITTER, world, x, y, z);
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
