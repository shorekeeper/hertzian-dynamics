package io.hertzian.dynamics;

import net.minecraft.item.Item;

import cpw.mods.fml.common.registry.GameRegistry;
import io.hertzian.dynamics.item.ItemFrequencyPreset;
import io.hertzian.dynamics.item.ItemHandheldRadio;
import io.hertzian.dynamics.item.ItemHeadset;
import io.hertzian.dynamics.item.ItemTuningKnob;

/**
 * Item registration: the handheld radio, the tuning knob accessory, the
 * frequency preset, and the headset. The radio and the headset are carried
 * in the dedicated radio gear slots; the others live on the hotbar and act
 * on radio blocks in the world.
 */
public final class ModItems {

    public static Item handheldRadio;
    public static Item tuningKnob;
    public static Item frequencyPreset;
    public static Item headset;

    private ModItems() {}

    public static void register() {
        handheldRadio = new ItemHandheldRadio();
        tuningKnob = new ItemTuningKnob();
        frequencyPreset = new ItemFrequencyPreset();
        headset = new ItemHeadset();

        GameRegistry.registerItem(handheldRadio, "handheld_radio");
        GameRegistry.registerItem(tuningKnob, "tuning_knob");
        GameRegistry.registerItem(frequencyPreset, "frequency_preset");
        GameRegistry.registerItem(headset, "headset");
    }
}