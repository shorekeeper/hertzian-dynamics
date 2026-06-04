package io.hertzian.dynamics.block;

import java.util.ArrayList;
import java.util.List;

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
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.tile.TileRadioTransmitter;
import io.hertzian.dynamics.tile.TileTestTone;

/**
 * Test tone generator block.
 *
 * <p>
 * Right-click cycles through {@link TileTestTone.Mode}. The
 * {@code BUZZER_UVB76} mode is special: on entry it auto-tunes the
 * nearest transmitter to 4.625 MHz USB so the player does not have
 * to press the tuning knob 11 000 times to reach the shortwave
 * band from the default 145 MHz. The receiver is the player's
 * responsibility because there may be many of them and the block
 * cannot decide which one the listener wants tuned.
 */
public final class BlockTestTone extends BlockContainer {

    private static final int RANGE = 8;
    private static final double UVB76_CARRIER_HZ = 4_625_000.0;

    public BlockTestTone() {
        super(Material.iron);
        setHardness(1.0F);
        setResistance(4.0F);
        setStepSound(soundTypeMetal);
        setBlockName(HertzianRefs.MODID + ".test_tone");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileTestTone();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        BlockFacing.applyFacing(world, x, y, z, placer);
        if (world.isRemote || !(placer instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) placer;
        int transmitters = countNearbyTransmitters(world, x, y, z);
        if (transmitters == 0) {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.YELLOW + "[Test Tone] No transmitter within "
                        + RANGE
                        + " blocks. Place a Radio Transmitter nearby."));
        } else {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN + "[Test Tone] Feeding "
                        + transmitters
                        + " transmitter(s). Tune a receiver to match."));
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GRAY
                        + "[Test Tone] Right-click to cycle: MUSIC -> TONE_1KHZ -> SWEEP -> BUZZER_UVB76."));
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileTestTone)) return false;
        TileTestTone tt = (TileTestTone) te;
        tt.cycleMode();
        player.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.AQUA + "[Test Tone] Mode: "
                    + tt.mode()
                        .name()));

        // When the player switches into the UVB-76 mode, auto-tune
        // the nearest transmitter so the buzz lands on the historical
        // 4625 kHz USB and the player only has to retune the
        // receiver (the block cannot pick a receiver for them).
        if (tt.mode() == TileTestTone.Mode.BUZZER_UVB76) {
            TileRadioTransmitter nearest = findNearestTransmitter(world, x, y, z);
            if (nearest != null) {
                nearest.setCarrierHz(UVB76_CARRIER_HZ);
                nearest.setModulation(Modulation.USB);
                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.YELLOW + "[UVB-76] Transmitter at ("
                            + nearest.xCoord
                            + ", "
                            + nearest.yCoord
                            + ", "
                            + nearest.zCoord
                            + ") set to 4.625 MHz USB"));
                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.YELLOW + "[UVB-76] Tune your receiver to 4.625 MHz USB to listen."));
                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.GRAY + "[UVB-76] The Buzzer: shortwave at 4625 kHz since the late 1970s, "
                            + "1.2 s buzz every ~2.2 s, ~27 buzzes/minute."));
            } else {
                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.RED + "[UVB-76] No transmitter in range; place one within "
                            + RANGE
                            + " blocks first."));
            }
        }
        return true;
    }

    private static int countNearbyTransmitters(World world, int x, int y, int z) {
        int count = 0;
        int r = RANGE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) continue;
                    TileEntity te = world.getTileEntity(x + dx, y + dy, z + dz);
                    if (te instanceof TileRadioTransmitter) count++;
                }
            }
        }
        return count;
    }

    private static TileRadioTransmitter findNearestTransmitter(World world, int x, int y, int z) {
        TileRadioTransmitter best = null;
        double bestSq = (double) RANGE * RANGE;
        int r = RANGE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double sq = dx * dx + dy * dy + dz * dz;
                    if (sq > bestSq) continue;
                    TileEntity te = world.getTileEntity(x + dx, y + dy, z + dz);
                    if (te instanceof TileRadioTransmitter) {
                        // Collect every match and keep the closest.
                        if (sq < bestSq || best == null) {
                            bestSq = sq;
                            best = (TileRadioTransmitter) te;
                        }
                    }
                }
            }
        }
        // Fall back to listing the loaded TEs if direct scan missed
        // (e.g. if the transmitter sits at the exact edge of the
        // 8-block radius and the floor introduced a half-block
        // offset). This catch-all is cheap and avoids a UX trap.
        if (best == null) {
            @SuppressWarnings("unchecked")
            List<TileEntity> all = new ArrayList<>(world.loadedTileEntityList);
            for (TileEntity te : all) {
                if (!(te instanceof TileRadioTransmitter)) continue;
                double dx = te.xCoord - x;
                double dy = te.yCoord - y;
                double dz = te.zCoord - z;
                double sq = dx * dx + dy * dy + dz * dz;
                if (sq <= bestSq) {
                    bestSq = sq;
                    best = (TileRadioTransmitter) te;
                }
            }
        }
        return best;
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
