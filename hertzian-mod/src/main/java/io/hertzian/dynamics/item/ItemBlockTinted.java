package io.hertzian.dynamics.item;

import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * Generic ItemBlock for every block in this mod. It exists as a
 * dedicated subclass so the creative-tab path can attach a small
 * NBT-driven tint to distinguish variants of one block at a glance
 * (a transmitter on 145 MHz against the same block on 7 MHz).
 * Without that tint it behaves identically to {@link ItemBlock}.
 */
public final class ItemBlockTinted extends ItemBlock {

    public ItemBlockTinted(Block block) {
        super(block);
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        return super.getUnlocalizedName(stack);
    }
}
