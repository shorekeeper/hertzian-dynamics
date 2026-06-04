package io.hertzian.dynamics.player;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * IInventory view over a player's {@link HertzianPlayerRadio} gear, used
 * as the backing store for the gear container. Reads and writes go
 * straight to the extended property, so the property stays authoritative
 * while the gear screen is open. The container performs the standalone
 * client sync on close; this view does nothing extra on change.
 */
public final class InventoryRadioGear implements IInventory {

    private final HertzianPlayerRadio gear;

    public InventoryRadioGear(HertzianPlayerRadio gear) {
        this.gear = gear;
    }

    @Override
    public int getSizeInventory() {
        return HertzianPlayerRadio.SLOT_COUNT;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return gear.getStack(slot);
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        ItemStack s = gear.getStack(slot);
        if (s == null) return null;
        ItemStack split;
        if (s.stackSize <= amount) {
            split = s;
            gear.setStack(slot, null);
        } else {
            split = s.splitStack(amount);
            if (s.stackSize == 0) gear.setStack(slot, null);
        }
        return split;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        // Gear is persistent equipment; nothing is ejected on close.
        return null;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (stack != null && stack.stackSize > getInventoryStackLimit()) {
            stack.stackSize = getInventoryStackLimit();
        }
        gear.setStack(slot, stack);
    }

    @Override
    public String getInventoryName() {
        return "container.hertzian.radio_gear";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public void markDirty() {
        // The container handles the client sync on close.
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return HertzianPlayerRadio.isValidForSlot(slot, stack);
    }
}
