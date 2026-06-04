package io.hertzian.dynamics.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.tile.TileRadioReceiver;
import io.hertzian.dynamics.tile.TileRadioTransmitter;

/**
 * Frequency preset item: a portable jump-list of nine carrier
 * frequencies stored in the item NBT.
 *
 * <p>
 * Rationale: the tuning knob steps at 12.5 kHz, which is the
 * correct granularity for narrow-band voice channel selection but
 * useless for moving between bands (jumping from the 145 MHz amateur
 * band down to the 4.625 MHz shortwave UVB-76 carrier needs eleven
 * thousand presses). A preset list cuts this to one click per
 * destination while keeping the same input grammar the rest of the
 * mod uses (right-click on a tile entity for the actuator, sneak
 * for the modifier).
 *
 * <p>
 * Interaction grammar:
 * <ul>
 * <li><b>Right-click on a radio block</b>: apply the currently
 * active preset to the targeted receiver or transmitter. The
 * active preset is the one whose index equals the player's
 * held hotbar slot, so the player can keep nine stations on
 * a single item stack and switch with the digit keys.</li>
 * <li><b>Sneak + right-click in the air</b>: capture the
 * frequency and modulation of the last radio you interacted
 * with into the active preset slot. The "last radio" memory
 * is per-item-stack and travels with the preset, so dropping
 * and picking up the stack does not lose the captured state.</li>
 * <li><b>Right-click in the air</b> (no sneak, no target):
 * prints the active preset to chat so the player can verify
 * what is about to be applied without committing.</li>
 * </ul>
 *
 * <p>
 * Default presets: a freshly crafted stack ships with two named
 * frequencies as defaults so the player can hear something
 * immediately:
 * <ol>
 * <li>Slot 0: 4.625 MHz USB (UVB-76, "The Buzzer")</li>
 * <li>Slot 1: 145.000 MHz NarrowFM (the engine's default test band)</li>
 * </ol>
 * The remaining seven slots stay empty and accept the player's own
 * captures.
 *
 * <p>
 * NBT layout:
 * 
 * <pre>
 * {
 *   "presets": [
 *     { "hz": double, "mod": int, "name": string },
 *     ...
 *   ],
 *   "lastSeenHz": double,
 *   "lastSeenMod": int
 * }
 * </pre>
 */
public final class ItemFrequencyPreset extends Item {

    private static final int SLOT_COUNT = 9;
    private static final String NBT_PRESETS = "presets";
    private static final String NBT_HZ = "hz";
    private static final String NBT_MOD = "mod";
    private static final String NBT_NAME = "name";
    private static final String NBT_LAST_HZ = "lastSeenHz";
    private static final String NBT_LAST_MOD = "lastSeenMod";

    public ItemFrequencyPreset() {
        setUnlocalizedName(HertzianRefs.MODID + ".frequency_preset");
        setTextureName(HertzianRefs.MODID + ":frequency_preset");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    /**
     * Read the active slot index from the player's hotbar position.
     * The hotbar always has nine slots, indices 0..8, which lines
     * up exactly with the preset count.
     */
    private static int activeSlotFor(EntityPlayer player) {
        int s = player.inventory.currentItem;
        if (s < 0) return 0;
        if (s >= SLOT_COUNT) return SLOT_COUNT - 1;
        return s;
    }

    /**
     * Apply the active preset to the radio at the targeted block.
     * The handler short-circuits when the player is sneaking so the
     * sneak+right-click variant (used to capture) does not also
     * write to the targeted tile entity.
     */
    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        if (player.isSneaking()) {
            // The capture path runs through onItemRightClick, not
            // here. Returning false lets the right-click bubble up
            // to onItemRightClick on the next tick instead of being
            // swallowed by this empty interaction.
            return false;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        Preset preset = readPreset(stack, activeSlotFor(player));
        if (preset == null) {
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.YELLOW + "[Preset] Slot "
                        + (activeSlotFor(player) + 1)
                        + " is empty. Sneak+right-click in the air to capture "
                        + "the last radio's tuning."));
            return false;
        }
        if (te instanceof TileRadioReceiver) {
            TileRadioReceiver rx = (TileRadioReceiver) te;
            rx.setModulation(preset.modulation);
            rx.setTunedHz(preset.hz);
            rememberRadioState(stack, rx.tunedHz(), rx.modulation());
            player.addChatMessage(
                new ChatComponentText(EnumChatFormatting.AQUA + "[Preset] Receiver -> " + formatPreset(preset)));
            return true;
        }
        if (te instanceof TileRadioTransmitter) {
            TileRadioTransmitter tx = (TileRadioTransmitter) te;
            tx.setModulation(preset.modulation);
            tx.setCarrierHz(preset.hz);
            rememberRadioState(stack, tx.carrierHz(), tx.modulation());
            player.addChatMessage(
                new ChatComponentText(EnumChatFormatting.AQUA + "[Preset] Transmitter -> " + formatPreset(preset)));
            return true;
        }
        return false;
    }

    /**
     * Mid-air right-click. Two responses depending on the sneak
     * modifier:
     * <ul>
     * <li>Plain: print the active preset so the player can preview
     * what would be applied.</li>
     * <li>Sneak: capture the last seen radio state (frequency +
     * modulation) into the active preset slot.</li>
     * </ul>
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) return stack;
        int slot = activeSlotFor(player);
        if (player.isSneaking()) {
            Double lastHz = readLastHz(stack);
            Modulation lastMod = readLastModulation(stack);
            if (lastHz == null || lastMod == null) {
                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.YELLOW
                            + "[Preset] Nothing to capture. Right-click a radio first to record its tuning."));
                return stack;
            }
            Preset captured = new Preset(lastHz, lastMod, String.format("%.4f MHz %s", lastHz / 1.0e6, lastMod.name()));
            writePreset(stack, slot, captured);
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN + "[Preset] Slot " + (slot + 1) + " captured: " + formatPreset(captured)));
        } else {
            Preset preset = readPreset(stack, slot);
            if (preset == null) {
                player.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.GRAY + "[Preset] Slot " + (slot + 1) + " is empty."));
            } else {
                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.AQUA + "[Preset] Slot " + (slot + 1) + ": " + formatPreset(preset)));
            }
        }
        return stack;
    }

    /**
     * Hotbar tooltip: show every preset slot. Empty slots render
     * dimmed so the player can immediately see where the capture
     * slots are. The currently active slot (matching the held
     * hotbar position) is highlighted.
     */
    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean detail) {
        int active = activeSlotFor(player);
        for (int i = 0; i < SLOT_COUNT; i++) {
            Preset p = readPreset(stack, i);
            EnumChatFormatting color = (i == active) ? EnumChatFormatting.GREEN
                : (p == null ? EnumChatFormatting.DARK_GRAY : EnumChatFormatting.GRAY);
            String label = p == null ? "(empty)" : formatPreset(p);
            list.add(color + "Slot " + (i + 1) + ": " + label);
        }
        list.add(EnumChatFormatting.DARK_GRAY + "Right-click on radio to apply.");
        list.add(EnumChatFormatting.DARK_GRAY + "Sneak+right-click in air to capture.");
    }

    // -----------------------------------------------------------------
    // NBT helpers. Plain reflection-free reads/writes; the layout is
    // small enough that hand-coding stays readable.
    // -----------------------------------------------------------------

    private static NBTTagList ensureList(ItemStack stack) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound root = stack.getTagCompound();
        if (!root.hasKey(NBT_PRESETS)) {
            NBTTagList list = new NBTTagList();
            seedDefaults(list);
            root.setTag(NBT_PRESETS, list);
        }
        return root.getTagList(NBT_PRESETS, 10);
    }

    /**
     * Seed the preset list with the two reference frequencies the
     * mod ships with. Called only when a fresh stack first acquires
     * its NBT compound, so a player who clears a slot does not get
     * the default forced back.
     */
    private static void seedDefaults(NBTTagList list) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            list.appendTag(new NBTTagCompound());
        }
        Preset uvb = new Preset(4_625_000.0, Modulation.USB, "UVB-76");
        Preset fm = new Preset(145_000_000.0, Modulation.NARROW_FM, "VHF test");
        writeIntoList(list, 0, uvb);
        writeIntoList(list, 1, fm);
    }

    private static Preset readPreset(ItemStack stack, int slot) {
        NBTTagList list = ensureList(stack);
        if (slot < 0 || slot >= list.tagCount()) return null;
        NBTTagCompound entry = list.getCompoundTagAt(slot);
        if (!entry.hasKey(NBT_HZ)) return null;
        double hz = entry.getDouble(NBT_HZ);
        Modulation mod;
        try {
            mod = Modulation.fromCode(entry.getInteger(NBT_MOD));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String name = entry.hasKey(NBT_NAME) ? entry.getString(NBT_NAME) : "";
        return new Preset(hz, mod, name);
    }

    private static void writePreset(ItemStack stack, int slot, Preset preset) {
        NBTTagList list = ensureList(stack);
        writeIntoList(list, slot, preset);
        stack.getTagCompound()
            .setTag(NBT_PRESETS, list);
    }

    private static void writeIntoList(NBTTagList list, int slot, Preset preset) {
        // NBTTagList in 1.7.10 has no direct setTag(int, NBTBase)
        // path on every variant; the safe approach is to rebuild
        // the list with the new entry in place. The list is at
        // most 9 entries so the cost is negligible.
        NBTTagList rebuilt = new NBTTagList();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i == slot) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setDouble(NBT_HZ, preset.hz);
                entry.setInteger(NBT_MOD, preset.modulation.code());
                if (preset.name != null) entry.setString(NBT_NAME, preset.name);
                rebuilt.appendTag(entry);
            } else if (i < list.tagCount()) {
                rebuilt.appendTag(list.getCompoundTagAt(i));
            } else {
                rebuilt.appendTag(new NBTTagCompound());
            }
        }
        // Caller assigns rebuilt back onto the root compound.
        // Achieved by replacing the in-list reference via a small
        // dance: copy contents tag by tag back into the original
        // list reference so callers that already hold it see the
        // updated state.
        while (list.tagCount() > 0) list.removeTag(0);
        for (int i = 0; i < SLOT_COUNT; i++) {
            list.appendTag(rebuilt.getCompoundTagAt(i));
        }
    }

    private static void rememberRadioState(ItemStack stack, double hz, Modulation mod) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound root = stack.getTagCompound();
        root.setDouble(NBT_LAST_HZ, hz);
        root.setInteger(NBT_LAST_MOD, mod.code());
    }

    private static Double readLastHz(ItemStack stack) {
        if (!stack.hasTagCompound()) return null;
        NBTTagCompound root = stack.getTagCompound();
        if (!root.hasKey(NBT_LAST_HZ)) return null;
        return root.getDouble(NBT_LAST_HZ);
    }

    private static Modulation readLastModulation(ItemStack stack) {
        if (!stack.hasTagCompound()) return null;
        NBTTagCompound root = stack.getTagCompound();
        if (!root.hasKey(NBT_LAST_MOD)) return null;
        try {
            return Modulation.fromCode(root.getInteger(NBT_LAST_MOD));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String formatPreset(Preset p) {
        String namePart = (p.name == null || p.name.isEmpty()) ? "" : " (" + p.name + ")";
        return String.format("%.4f MHz %s%s", p.hz / 1.0e6, p.modulation.name(), namePart);
    }

    /**
     * Plain record of a single preset entry. {@code name} is
     * optional; it shows up in tooltips and chat when present.
     */
    private static final class Preset {

        final double hz;
        final Modulation modulation;
        final String name;

        Preset(double hz, Modulation modulation, String name) {
            this.hz = hz;
            this.modulation = modulation;
            this.name = name;
        }
    }
}
