package io.hertzian.dynamics.world;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * Maps a Minecraft {@link Block} to the {@code MaterialId} index
 * used by the Rust material table. Identity-keyed because Blocks
 * are singletons in 1.7.10 and equality is intentionally reference
 * based.
 *
 * <p>
 * The default rf-core table assigns ids 0..7 to air, stone,
 * water, wood, glass, dirt, leaves, iron. We mirror that mapping
 * here for the vanilla blocks that touch radio paths. Unknown
 * blocks fall back to air (id 0), which is the safe choice: a
 * misclassified block produces no extra absorption rather than an
 * absurdly opaque wall.
 *
 * <p>
 * Mod compat: a public {@code register(Block, int)}
 * method lets other mods slot their blocks into the table at
 * postInit time without modifying this file.
 */
public final class BlockMaterialMap {

    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int WATER = 2;
    public static final int WOOD = 3;
    public static final int GLASS = 4;
    public static final int DIRT = 5;
    public static final int LEAVES = 6;
    public static final int IRON = 7;

    private static final Map<Block, Integer> MAP = new IdentityHashMap<>();

    static {
        // Stones and ores.
        put(Blocks.stone, STONE);
        put(Blocks.cobblestone, STONE);
        put(Blocks.mossy_cobblestone, STONE);
        put(Blocks.bedrock, STONE);
        put(Blocks.stonebrick, STONE);
        put(Blocks.netherrack, STONE);
        put(Blocks.end_stone, STONE);
        put(Blocks.iron_ore, IRON);
        put(Blocks.gold_ore, IRON);
        put(Blocks.coal_ore, STONE);
        put(Blocks.diamond_ore, STONE);
        put(Blocks.lapis_ore, STONE);
        put(Blocks.redstone_ore, STONE);
        put(Blocks.emerald_ore, STONE);

        // Water and ice. Ice behaves like water for our purposes.
        put(Blocks.water, WATER);
        put(Blocks.flowing_water, WATER);
        put(Blocks.ice, WATER);

        // Wood.
        put(Blocks.log, WOOD);
        put(Blocks.log2, WOOD);
        put(Blocks.planks, WOOD);
        put(Blocks.fence, WOOD);
        put(Blocks.wooden_door, WOOD);
        put(Blocks.crafting_table, WOOD);

        // Glass.
        put(Blocks.glass, GLASS);
        put(Blocks.glass_pane, GLASS);
        put(Blocks.stained_glass, GLASS);

        // Dirt.
        put(Blocks.dirt, DIRT);
        put(Blocks.grass, DIRT);
        put(Blocks.farmland, DIRT);
        put(Blocks.sand, DIRT);
        put(Blocks.gravel, DIRT);
        put(Blocks.clay, DIRT);

        // Leaves and similar.
        put(Blocks.leaves, LEAVES);
        put(Blocks.leaves2, LEAVES);
        put(Blocks.tallgrass, LEAVES);

        // Metals.
        put(Blocks.iron_block, IRON);
        put(Blocks.gold_block, IRON);
        put(Blocks.anvil, IRON);
    }

    private static void put(Block b, int id) {
        if (b != null) MAP.put(b, id);
    }

    private BlockMaterialMap() {}

    /** Public hook for cross-mod registration at postInit. */
    public static void register(Block block, int materialId) {
        if (block == null) return;
        MAP.put(block, materialId);
    }

    /** Material id for a block. Returns {@link #AIR} for null or unknown blocks. */
    public static int forBlock(Block block) {
        if (block == null || block == Blocks.air) return AIR;
        Integer id = MAP.get(block);
        return id != null ? id : AIR;
    }
}
