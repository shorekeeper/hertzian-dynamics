package io.hertzian.dynamics.world;

import net.minecraft.block.material.MapColor;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapData;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.core.SstvCodec;

/**
 * Reads the filled map a player is holding into a 96 by 96 RGB image for
 * the SSTV station to transmit.
 *
 * <p>
 * Why this is the server-safe image source
 * A filled map's pixels already live on the server as the
 * {@link MapData#colors} byte array, so turning a map into a transmittable
 * picture needs no image transfer from the client at all: the SSTV control
 * packet carries only the intent "use my held map", and the conversion
 * happens here, server-side, from data the server already owns.
 *
 * <p>
 * Diagnostics
 * The earlier version returned {@code null} on any failure with no trace,
 * so a player holding the wrong thing or an empty map saw nothing happen
 * and could not tell why. {@link #fromHeldMap} now logs the specific
 * reason at info level and the station relays a short message to the
 * player, so the failure is visible rather than silent.
 *
 * <p>
 * Note on the {@link MapColor} import
 * In Minecraft 1.7.10 the palette class is
 * {@code net.minecraft.block.material.MapColor}. If a deobfuscation
 * mapping in use here places it elsewhere, this single import and the one
 * in {@link TerrainImager} are the only lines that need adjusting.
 */
public final class MapImageReader {

    private MapImageReader() {}

    /**
     * Convert the player's held filled map into a 96 by 96 RGB image, or
     * return null if the player is not holding a readable filled map. The
     * reason for a null result is logged so a failed add can be explained.
     */
    public static int[][] fromHeldMap(EntityPlayer player) {
        if (player == null) {
            return null;
        }
        ItemStack stack = player.getHeldItem();
        if (stack == null) {
            HertzianDynamics.LOGGER.info("[SSTV] map add: player holds nothing");
            return null;
        }
        if (!(stack.getItem() instanceof ItemMap)) {
            HertzianDynamics.LOGGER.info("[SSTV] map add: held item is not a filled map ({})", stack.getItem());
            return null;
        }
        World world = player.worldObj;
        MapData data;
        try {
            data = ((ItemMap) stack.getItem()).getMapData(stack, world);
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("[SSTV] map add: getMapData threw", t);
            return null;
        }
        if (data == null) {
            HertzianDynamics.LOGGER.info("[SSTV] map add: map has no data yet");
            return null;
        }
        if (data.colors == null || data.colors.length < 128 * 128) {
            HertzianDynamics.LOGGER.info("[SSTV] map add: colour array empty or short");
            return null;
        }

        int[][] out = new int[SstvCodec.H][SstvCodec.W];
        for (int y = 0; y < SstvCodec.H; y++) {
            int srcY = y * 128 / SstvCodec.H;
            for (int x = 0; x < SstvCodec.W; x++) {
                int srcX = x * 128 / SstvCodec.W;
                int colorByte = data.colors[srcY * 128 + srcX] & 0xFF;
                out[y][x] = paletteToRgb(colorByte);
            }
        }
        return out;
    }

    /**
     * Resolve one map colour byte to a 0xRRGGBB value. The palette index
     * is the high six bits, the shade the low two. The shade multipliers
     * (180, 220, 255, 135) are the vanilla map shading levels.
     */
    static int paletteToRgb(int colorByte) {
        int palette = colorByte >> 2;
        int shade = colorByte & 3;
        if (palette < 0 || palette >= MapColor.mapColorArray.length) {
            return 0x000000;
        }
        MapColor mc = MapColor.mapColorArray[palette];
        if (mc == null) {
            return 0x000000;
        }
        return shadeRgb(mc.colorValue, shade);
    }

    /** Apply a vanilla map shade level (0..3) to a base 0xRRGGBB colour. */
    static int shadeRgb(int base, int shade) {
        int multiplier;
        switch (shade) {
            case 0:
                multiplier = 180;
                break;
            case 1:
                multiplier = 220;
                break;
            case 2:
                multiplier = 255;
                break;
            default:
                multiplier = 135;
                break;
        }
        int r = ((base >> 16) & 0xFF) * multiplier / 255;
        int g = ((base >> 8) & 0xFF) * multiplier / 255;
        int b = (base & 0xFF) * multiplier / 255;
        return (r << 16) | (g << 8) | b;
    }
}
