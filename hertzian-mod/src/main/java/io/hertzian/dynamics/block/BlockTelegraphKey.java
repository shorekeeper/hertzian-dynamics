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
import io.hertzian.dynamics.tile.TileTelegraphKey;

/**
 * Telegraph key block. Hosts a {@link TileTelegraphKey} that sends Morse
 * to the nearest transmitter in CW. Right-click opens the key GUI. On
 * placement it tells the player whether a transmitter is in range, since
 * the key is useless without one to feed, the same courtesy the test tone
 * block extends.
 */
public final class BlockTelegraphKey extends BlockContainer {

    private static final int RANGE = 8;

    public BlockTelegraphKey() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(6.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".telegraph_key");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileTelegraphKey();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        BlockFacing.applyFacing(world, x, y, z, placer);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) placer;
        int count = countNearbyTransmitters(world, x, y, z);
        if (count == 0) {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.YELLOW + "[Telegraph] No transmitter within "
                        + RANGE
                        + " blocks. Place a Radio Transmitter nearby."));
        } else {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN + "[Telegraph] Feeding "
                        + count
                        + " transmitter(s). Right-click to key Morse; the nearest goes to CW."));
        }
    }

    private static int countNearbyTransmitters(World world, int x, int y, int z) {
        int count = 0;
        for (int dx = -RANGE; dx <= RANGE; dx++) {
            for (int dy = -RANGE; dy <= RANGE; dy++) {
                for (int dz = -RANGE; dz <= RANGE; dz++) {
                    if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) continue;
                    if (world.getTileEntity(x + dx, y + dy, z + dz) instanceof TileRadioTransmitter) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        player.openGui(io.hertzian.dynamics.HertzianDynamics.INSTANCE, GuiIds.TELEGRAPH_KEY, world, x, y, z);
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
