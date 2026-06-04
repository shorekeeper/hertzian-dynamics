package io.hertzian.dynamics.item;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.hertzian.dynamics.HertzianRefs;
import io.hertzian.dynamics.core.Modulation;
import io.hertzian.dynamics.gui.GuiIds;

/**
 * Handheld Radio item. Persistent state lives in the stack's NBT;
 * each tick the server-side {@link io.hertzian.dynamics.world.HandheldRadioRegistry}
 * reads these fields and maintains rf-core slots for the player.
 *
 * <p>
 * The stack carries the controls a real portable radio has on its front
 * panel: a power switch, a tuned frequency and mode, a transmit power
 * level, a volume control for the recovered audio, a squelch level that
 * mutes the speaker until a signal of sufficient strength arrives, and a
 * monitor flag that forces the squelch open so a weak or marginal channel
 * can be checked by ear. The registry reads all of these every tick, so
 * the radio responds the moment a control changes.
 *
 * <p>
 * Self-monitoring (sidetone) is a separate flag. When off, the radio is a
 * plain half-duplex set: while the operator transmits the receiver passes
 * no audio, so they hear nothing of their own transmission. When on, the
 * operator hears their own audio while keyed, the confirmation tone real
 * sets can be configured to provide. The flag is read on the transmit
 * path in the handheld registry.
 *
 * <p>
 * Models. The item metadata selects a {@link RadioModel}, a class of real
 * hardware that bounds the radio: its band, whether it tunes freely or on
 * a fixed channel grid, the selectable power steps, the supported
 * modulations, the receiver noise figure, and whether the courtesy beep
 * can be turned off. Every setter clamps its value to the model, and the
 * getters fall back to the model defaults when the NBT field is absent, so
 * a fresh radio of a model starts configured the way that hardware would.
 * The model is also what gives each variant its own icon and name.
 *
 * <p>
 * The radio can be carried hands-free in the dedicated radio gear slot
 * (see {@link io.hertzian.dynamics.player.HertzianPlayerRadio}). When
 * slotted it runs from its stored NBT and push-to-talk works regardless of
 * what the player holds. Settings are edited by holding the radio and
 * opening its panel.
 */
public final class ItemHandheldRadio extends Item {

    @SideOnly(Side.CLIENT)
    private IIcon[] icons;

    public ItemHandheldRadio() {
        setUnlocalizedName(HertzianRefs.MODID + ".handheld_radio");
        setMaxStackSize(1);
        setHasSubtypes(true);
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    /** Model of a radio stack, resolved from its metadata. */
    public static RadioModel model(ItemStack stack) {
        if (stack == null) return RadioModel.byIndex(0);
        return RadioModel.byIndex(stack.getItemDamage());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister reg) {
        RadioModel[] all = RadioModel.values();
        icons = new IIcon[all.length];
        for (int i = 0; i < all.length; i++) {
            icons[i] = reg.registerIcon(HertzianRefs.MODID + ":" + all[i].textureName());
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        if (icons == null) return super.getIconFromDamage(meta);
        return icons[RadioModel.byIndex(meta)
                .index()];
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (RadioModel m : RadioModel.values()) {
            list.add(new ItemStack(item, 1, m.index()));
        }
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        return super.getUnlocalizedName() + "." + model(stack).key();
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            if (!world.isRemote) {
                setPowered(stack, !isPowered(stack));
            }
        } else {
            player.openGui(
                    io.hertzian.dynamics.HertzianDynamics.INSTANCE,
                    GuiIds.HANDHELD_RADIO,
                    world,
                    player.getEntityId(),
                    0,
                    0);
        }
        return stack;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean detail) {
        RadioModel m = model(stack);
        list.add(m.displayName() + " (T" + m.tier() + ")");
        list.add(isPowered(stack) ? "Powered" : "Off");
        list.add(String.format("%.4f MHz %s", tunedHz(stack) / 1.0e6, modulation(stack).name()));
        list.add("Sidetone: " + (selfMonitor(stack) ? "on" : "off"));
        list.add("Sneak + right-click: power");
        list.add("Right-click: open GUI");
        list.add("Hold V: push-to-talk (when powered)");
        list.add("Carry in the radio gear slot for hands-free use");
    }

    private static NBTTagCompound nbt(ItemStack stack) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        return stack.getTagCompound();
    }

    public static boolean isPowered(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound()
                .getBoolean("powered");
    }

    public static void setPowered(ItemStack stack, boolean v) {
        nbt(stack).setBoolean("powered", v);
    }

    public static double tunedHz(ItemStack stack) {
        NBTTagCompound t = stack.getTagCompound();
        if (t != null && t.hasKey("hz")) return t.getDouble("hz");
        return model(stack).defaultHz();
    }

    public static void setTunedHz(ItemStack stack, double hz) {
        nbt(stack).setDouble("hz", model(stack).clampHz(hz));
    }

    public static float bandwidthHz(ItemStack stack) {
        NBTTagCompound t = stack.getTagCompound();
        if (t != null && t.hasKey("bw")) return t.getFloat("bw");
        return model(stack).defaultBandwidthHz();
    }

    public static void setBandwidthHz(ItemStack stack, float bw) {
        nbt(stack).setFloat("bw", bw);
    }

    public static float txPowerW(ItemStack stack) {
        NBTTagCompound t = stack.getTagCompound();
        if (t != null && t.hasKey("pwr")) return t.getFloat("pwr");
        return model(stack).defaultPower();
    }

    public static void setTxPowerW(ItemStack stack, float w) {
        nbt(stack).setFloat("pwr", model(stack).nearestPower(w));
    }

    public static Modulation modulation(ItemStack stack) {
        NBTTagCompound t = stack.getTagCompound();
        RadioModel m = model(stack);
        if (t == null || !t.hasKey("mod")) return m.defaultModulation();
        try {
            Modulation mod = Modulation.fromCode(t.getInteger("mod"));
            return m.allows(mod) ? mod : m.defaultModulation();
        } catch (IllegalArgumentException e) {
            return m.defaultModulation();
        }
    }

    public static void setModulation(ItemStack stack, Modulation requested) {
        RadioModel m = model(stack);
        Modulation use = m.allows(requested) ? requested : m.defaultModulation();
        nbt(stack).setInteger("mod", use.code());
    }

    /** Speaker volume, 0 mute to 1 full. Applied to the recovered audio. */
    public static float volume(ItemStack stack) {
        NBTTagCompound t = stack.getTagCompound();
        return t != null && t.hasKey("vol") ? t.getFloat("vol") : 0.8f;
    }

    public static void setVolume(ItemStack stack, float v) {
        nbt(stack).setFloat("vol", clamp01(v));
    }

    /**
     * Squelch level, 0 fully open to 1 tight. Mapped by the registry to a
     * signal to noise threshold the channel must clear before the speaker
     * is unmuted, so an empty channel stays silent instead of hissing.
     */
    public static float squelch(ItemStack stack) {
        NBTTagCompound t = stack.getTagCompound();
        return t != null && t.hasKey("sql") ? t.getFloat("sql") : 0.25f;
    }

    public static void setSquelch(ItemStack stack, float v) {
        nbt(stack).setFloat("sql", clamp01(v));
    }

    /**
     * Roger beep flag. When set, the radio appends a short tone burst to
     * the end of every transmission, the courtesy beep a real handheld
     * sends so the far operator hears that the over has ended. The tone
     * itself is fixed; only the on/off state is a control, matching the
     * way most real radios expose this feature. A model whose beep is not
     * removable reports the beep as always on and ignores attempts to
     * clear it.
     */
    public static boolean rogerBeep(ItemStack stack) {
        if (!model(stack).beepRemovable()) return true;
        return stack.hasTagCompound() && stack.getTagCompound()
                .getBoolean("roger");
    }

    public static void setRogerBeep(ItemStack stack, boolean v) {
        if (!model(stack).beepRemovable()) return;
        nbt(stack).setBoolean("roger", v);
    }

    /** Monitor flag. While set the squelch is forced open. */
    public static boolean monitor(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound()
                .getBoolean("mon");
    }

    public static void setMonitor(ItemStack stack, boolean v) {
        nbt(stack).setBoolean("mon", v);
    }

    /**
     * Self-monitoring (sidetone) flag. When set, the operator hears their
     * own transmitted audio while keyed. When clear, the set behaves as a
     * plain half-duplex radio and is silent to the operator while talking.
     * Defaults to clear so a fresh radio does not echo the operator back
     * to themselves.
     */
    public static boolean selfMonitor(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound()
                .getBoolean("self");
    }

    public static void setSelfMonitor(ItemStack stack, boolean v) {
        nbt(stack).setBoolean("self", v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}