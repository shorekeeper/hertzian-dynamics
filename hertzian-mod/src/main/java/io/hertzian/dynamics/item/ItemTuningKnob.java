package io.hertzian.dynamics.item;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.tile.TileRadioReceiver;
import io.hertzian.dynamics.tile.TileRadioTransmitter;

/**
 * Tuning knob: shifts the carrier/tuned frequency of the targeted
 * radio block by one channel step. Plain right-click increments;
 * sneak + right-click decrements. Every press prints the new
 * frequency in chat so the player has direct feedback.
 *
 * <p>
 * The 12.5 kHz step matches the canonical NarrowFM channel
 * spacing; on broadcast bands a future variant of this item could
 * use a 200 kHz step.
 */
public final class ItemTuningKnob extends Item {

    private static final double STEP_HZ = 12_500.0;

    public ItemTuningKnob() {
        setUnlocalizedName(HertzianRefs.MODID + ".tuning_knob");
        setTextureName(HertzianRefs.MODID + ":tuning_knob");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        TileEntity te = world.getTileEntity(x, y, z);
        boolean shift = player.isSneaking();
        double delta = shift ? -STEP_HZ : STEP_HZ;

        if (te instanceof TileRadioReceiver) {
            TileRadioReceiver rx = (TileRadioReceiver) te;
            rx.setTunedHz(rx.tunedHz() + delta);
            sendKnobFeedback(
                player,
                "Rx",
                rx.tunedHz(),
                rx.modulation()
                    .name(),
                shift);
            return true;
        }
        if (te instanceof TileRadioTransmitter) {
            TileRadioTransmitter tx = (TileRadioTransmitter) te;
            tx.setCarrierHz(tx.carrierHz() + delta);
            sendKnobFeedback(
                player,
                "Tx",
                tx.carrierHz(),
                tx.modulation()
                    .name(),
                shift);
            return true;
        }
        return false;
    }

    /**
     * Send a single-line chat update showing the resulting
     * frequency, the modulation, the direction of the change, and a
     * persistent hint about the sneak modifier so the player can
     * actually find the reverse direction without reading the wiki.
     */
    private static void sendKnobFeedback(EntityPlayer player, String label, double hz, String modulation,
        boolean wasSneak) {
        String arrow = wasSneak ? EnumChatFormatting.RED + " -" + (int) STEP_HZ + " Hz"
            : EnumChatFormatting.GREEN + " +" + (int) STEP_HZ + " Hz";
        player.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.AQUA + "["
                    + label
                    + "] "
                    + String.format("%.4f", hz / 1.0e6)
                    + " MHz "
                    + modulation
                    + arrow
                    + EnumChatFormatting.DARK_GRAY
                    + " (sneak+right-click to decrease)"));
    }
}
