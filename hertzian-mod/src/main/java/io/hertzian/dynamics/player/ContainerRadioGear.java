package io.hertzian.dynamics.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.item.ItemHeadset;
import io.hertzian.dynamics.net.PacketRadioGearSync;
import io.hertzian.dynamics.player.HertzianPlayerRadio;
import io.hertzian.dynamics.player.InventoryRadioGear;

/**
 * Container for the radio gear screen. Exposes the two gear slots plus the
 * player inventory and hotbar so items move in and out. The gear slots
 * write straight through to the player's {@link HertzianPlayerRadio}
 * property, which is the authoritative store on the server.
 *
 * Button handling reuses {@code enchantItem}, the standard path for a GUI
 * button press without a dedicated packet. Button zero toggles the power
 * of the radio in the radio slot.
 *
 * Closing the container pushes the gear state to the owning client so the
 * standalone property copy used by push-to-talk stays current. While the
 * container is open the per-slot syncs already keep the client copy fresh,
 * because the slots are backed by the client's own property.
 */
public final class ContainerRadioGear extends Container {

    private final HertzianPlayerRadio gear;
    private final InventoryRadioGear inventory;

    public ContainerRadioGear(EntityPlayer player, HertzianPlayerRadio gear) {
        // Defensive: if the property is somehow not attached, use a
        // transient one so the screen does not crash. Attachment is done
        // in RadioGearEvents on entity construction, so this is a guard
        // rather than an expected path.
        this.gear = (gear != null) ? gear : new HertzianPlayerRadio();
        this.inventory = new InventoryRadioGear(this.gear);

        addSlotToContainer(new SlotFiltered(inventory, HertzianPlayerRadio.SLOT_RADIO, 44, 18));
        addSlotToContainer(new SlotFiltered(inventory, HertzianPlayerRadio.SLOT_HEADSET, 116, 18));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(player.inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(player.inventory, col, 8 + col * 18, 142));
        }
    }

    @Override
    public boolean enchantItem(EntityPlayer p, int buttonId) {
        if (buttonId == 0) {
            ItemStack radio = gear.radio();
            if (radio != null) {
                ItemHandheldRadio.setPowered(radio, !ItemHandheldRadio.isPowered(radio));
                detectAndSendChanges();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return true;
    }

    @Override
    public void onContainerClosed(EntityPlayer p) {
        super.onContainerClosed(p);
        if (!p.worldObj.isRemote && p instanceof EntityPlayerMP) {
            PacketRadioGearSync.sendTo((EntityPlayerMP) p);
        }
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        ItemStack result = null;
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot != null && slot.getHasStack()) {
            ItemStack stack = slot.getStack();
            result = stack.copy();
            int gearCount = HertzianPlayerRadio.SLOT_COUNT;
            int invEnd = inventorySlots.size();
            if (index < gearCount) {
                // Gear slot to player inventory.
                if (!mergeItemStack(stack, gearCount, invEnd, true)) return null;
            } else {
                // Player inventory to a matching gear slot.
                int target = -1;
                if (stack.getItem() instanceof ItemHandheldRadio) target = HertzianPlayerRadio.SLOT_RADIO;
                else if (stack.getItem() instanceof ItemHeadset) target = HertzianPlayerRadio.SLOT_HEADSET;
                if (target < 0) return null;
                if (!mergeItemStack(stack, target, target + 1, false)) return null;
            }
            if (stack.stackSize == 0) slot.putStack(null);
            else slot.onSlotChanged();
            if (stack.stackSize == result.stackSize) return null;
            slot.onPickupFromSlot(p, stack);
        }
        return result;
    }

    /** Gear slot limited to one item and filtered by its accepted kind. */
    private static final class SlotFiltered extends Slot {

        private final int gearSlot;

        SlotFiltered(InventoryRadioGear inv, int index, int x, int y) {
            super(inv, index, x, y);
            this.gearSlot = index;
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return HertzianPlayerRadio.isValidForSlot(gearSlot, stack);
        }

        @Override
        public int getSlotStackLimit() {
            return 1;
        }
    }
}
