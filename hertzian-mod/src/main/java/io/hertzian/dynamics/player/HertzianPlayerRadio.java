package io.hertzian.dynamics.player;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.item.ItemHeadset;

/**
 * Per-player radio gear, attached as an extended entity property on both
 * sides. Holds two carry slots that are independent of the main inventory
 * and hotbar: one radio slot and one headset slot. A radio in the radio
 * slot runs hands-free, so push-to-talk works regardless of what the
 * player is currently holding (mining, shooting, anything).
 *
 * The property exists on the server as the authoritative store and on the
 * client as a synced copy. The server drives the radio simulation and
 * persistence from its copy; the client reads its copy to decide whether
 * a powered radio is carried for capture and whether a headset is present.
 * The client copy is refreshed by PacketRadioGearSync and by the open gear
 * container.
 *
 * Slot layout:
 * <ul>
 * <li>0 radio, accepts {@link ItemHandheldRadio}</li>
 * <li>1 headset, accepts {@link ItemHeadset}</li>
 * </ul>
 */
public final class HertzianPlayerRadio implements IExtendedEntityProperties {

    public static final String ID = "hertzian_radio_gear";

    public static final int SLOT_RADIO = 0;
    public static final int SLOT_HEADSET = 1;
    public static final int SLOT_COUNT = 2;

    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];

    /** Resolve the property for a player, or null if not attached yet. */
    public static HertzianPlayerRadio get(EntityPlayer player) {
        if (player == null) return null;
        IExtendedEntityProperties props = player.getExtendedProperties(ID);
        return props instanceof HertzianPlayerRadio ? (HertzianPlayerRadio) props : null;
    }

    /**
     * The active radio for a player: the gear slot radio takes priority,
     * the held item is the fallback. Returns null if neither is a handheld
     * radio. Shared by the push-to-talk handler, the server registry and
     * the HUD so they all agree on which radio responds.
     */
    public static ItemStack activeRadio(EntityPlayer player) {
        HertzianPlayerRadio gear = get(player);
        if (gear != null) {
            ItemStack slotted = gear.radio();
            if (slotted != null) return slotted;
        }
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemHandheldRadio) return held;
        return null;
    }

    public ItemStack getStack(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return null;
        return slots[slot];
    }

    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        slots[slot] = stack;
    }

    /** Radio currently carried in the radio slot, or null. */
    public ItemStack radio() {
        ItemStack s = slots[SLOT_RADIO];
        return (s != null && s.getItem() instanceof ItemHandheldRadio) ? s : null;
    }

    /** True when a headset is present in the headset slot. */
    public boolean hasHeadset() {
        ItemStack s = slots[SLOT_HEADSET];
        return s != null && s.getItem() instanceof ItemHeadset;
    }

    /** Validity rule used by the container slots and by any direct write. */
    public static boolean isValidForSlot(int slot, ItemStack stack) {
        if (stack == null) return true;
        if (slot == SLOT_RADIO) return stack.getItem() instanceof ItemHandheldRadio;
        if (slot == SLOT_HEADSET) return stack.getItem() instanceof ItemHeadset;
        return false;
    }

    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (slots[i] == null) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setByte("slot", (byte) i);
            slots[i].writeToNBT(entry);
            list.appendTag(entry);
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("slots", list);
        compound.setTag(ID, root);
    }

    @Override
    public void loadNBTData(NBTTagCompound compound) {
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = null;
        if (compound == null || !compound.hasKey(ID)) return;
        NBTTagCompound root = compound.getCompoundTag(ID);
        NBTTagList list = root.getTagList("slots", 10);
        for (int t = 0; t < list.tagCount(); t++) {
            NBTTagCompound entry = list.getCompoundTagAt(t);
            int i = entry.getByte("slot") & 0xFF;
            if (i >= 0 && i < SLOT_COUNT) {
                slots[i] = ItemStack.loadItemStackFromNBT(entry);
            }
        }
    }

    /** Snapshot the gear NBT for a sync packet or a respawn clone. */
    public NBTTagCompound writeSyncNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        saveNBTData(tag);
        return tag;
    }

    /** Apply a synced gear NBT, replacing the current contents. */
    public void readSyncNBT(NBTTagCompound tag) {
        loadNBTData(tag);
    }

    @Override
    public void init(Entity entity, World world) {
        // No per-world state to initialise.
    }
}
