package io.hertzian.dynamics.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import io.hertzian.dynamics.HertzianRefs;

/**
 * Headset accessory. Worn in the headset slot of the radio gear
 * inventory. A headset is a close-talk microphone plus an earpiece. It
 * carries no per-stack state; its presence in the gear slot is read by
 * the transmit path to change how the operator audio is captured.
 *
 * Transmit effect: a close-talk microphone rejects ambient sound, so the
 * environment bleed that an open speaker microphone picks up is strongly
 * attenuated. The push-to-talk handler reads the headset presence and
 * scales the captured environment chunk down.
 *
 * The item is intentionally minimal. Future variants may carry their own
 * quality fields the way the radio models do.
 */
public final class ItemHeadset extends Item {

    public ItemHeadset() {
        setUnlocalizedName(HertzianRefs.MODID + ".headset");
        setTextureName(HertzianRefs.MODID + ":headset");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean detail) {
        list.add("Close-talk headset");
        list.add("Place in the radio gear headset slot");
        list.add("Rejects ambient noise on transmit");
    }
}
