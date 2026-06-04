package io.hertzian.dynamics.client.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.AdvancedModelLoader;
import net.minecraftforge.client.model.IModelCustom;
import net.minecraftforge.client.model.obj.Vertex;
import net.minecraftforge.client.model.obj.WavefrontObject;

import io.hertzian.dynamics.HertzianDynamics;
import io.hertzian.dynamics.HertzianRefs;

/**
 * Lazy cache of Wavefront OBJ models and the {@link ResourceLocation}
 * of the matching texture. One entry per block kind; entries load
 * the OBJ file on first request and reuse the parsed model object
 * forever after.
 *
 * <p>
 * Why lazy: model parsing reads the .obj file via Minecraft's
 * resource manager, which is only safe after preInit when the
 * resource pack list is finalised. Lazy loading on first render
 * call sidesteps the question of "is the resource manager ready
 * yet" entirely.
 *
 * <p>
 * If the .obj file is missing or malformed the loader throws
 * {@code ModelFormatException}. We catch and log it, then mark
 * the entry as failed so subsequent calls are cheap. A failed
 * entry can resolve to a registered fallback kind (see
 * {@link #registerFallback}) so a block whose dedicated model has
 * not been authored yet borrows another kind's mesh instead of
 * rendering as nothing.
 *
 * <p>
 * Bounding box for inventory fit
 * Each entry computes the axis-aligned bounds of the parsed mesh.
 * The item renderer scales a model into the inventory slot from the
 * largest extent of these bounds, so a model larger than one block
 * (an antenna mast, a tall instrument case) is shrunk to sit inside
 * the 16 by 16 cell rather than spilling past its edges. The bounds
 * are read from the {@link WavefrontObject} vertex list; if that
 * cannot be read the entry falls back to a unit-block box, which
 * keeps rendering working without the precise fit.
 */
public final class ObjModelRegistry {

    /**
     * Convention name to entry. Same key is used by the TESR and
     * the item renderer for a given block kind.
     */
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    /**
     * Optional fallback kind per kind. When a kind's own model fails
     * to load (file absent or malformed) {@link #get} resolves to the
     * fallback's entry instead, so a not-yet-authored model shows a
     * stand-in mesh rather than an invisible block.
     */
    private static final Map<String, String> FALLBACK = new HashMap<>();

    private ObjModelRegistry() {}

    /**
     * Register a fallback kind for {@code kind}. If {@code kind}'s OBJ
     * cannot be loaded, {@link #get} returns the fallback kind's entry.
     * Call during client preInit before any rendering happens. The
     * fallback chain is assumed acyclic.
     */
    public static synchronized void registerFallback(String kind, String fallbackKind) {
        FALLBACK.put(kind, fallbackKind);
    }

    /**
     * One entry: the OBJ model, the texture, and the model bounds.
     * Both model and texture are resolved on the first {@link #get}
     * call; the texture ResourceLocation is just a path, the actual
     * GL texture upload is done by the texture manager when the
     * renderer binds it.
     */
    public static final class Entry {

        public final IModelCustom model;
        public final ResourceLocation texture;
        public final boolean failed;
        // Axis-aligned bounds of the mesh in model units.
        public final float minX, minY, minZ, maxX, maxY, maxZ;

        Entry(IModelCustom model, ResourceLocation texture, boolean failed, float[] bounds) {
            this.model = model;
            this.texture = texture;
            this.failed = failed;
            this.minX = bounds[0];
            this.minY = bounds[1];
            this.minZ = bounds[2];
            this.maxX = bounds[3];
            this.maxY = bounds[4];
            this.maxZ = bounds[5];
        }

        /** Extent along axis 0=X, 1=Y, 2=Z. */
        public float extent(int axis) {
            switch (axis) {
                case 0:
                    return maxX - minX;
                case 1:
                    return maxY - minY;
                default:
                    return maxZ - minZ;
            }
        }

        /** Largest extent across the three axes, floored to avoid zero. */
        public float maxExtent() {
            float m = Math.max(extent(0), Math.max(extent(1), extent(2)));
            return m <= 1.0e-3f ? 1.0f : m;
        }

        public float centerX() {
            return (minX + maxX) * 0.5f;
        }

        public float centerY() {
            return (minY + maxY) * 0.5f;
        }

        public float centerZ() {
            return (minZ + maxZ) * 0.5f;
        }
    }

    /**
     * Look up (or load) the entry for the given block kind. The
     * kind matches the unprefixed registry name of the block,
     * e.g. "radio_transmitter".
     *
     * <p>
     * Resource layout:
     * <ul>
     * <li>{@code assets/hertzian/models/block/<kind>.obj}</li>
     * <li>{@code assets/hertzian/textures/blocks/<kind>.png}</li>
     * </ul>
     *
     * <p>
     * Returns a non-null Entry. If the kind's own model failed to
     * load and a fallback is registered, the fallback's entry is
     * returned instead; check {@link Entry#failed} on the result to
     * see if even that failed.
     */
    public static synchronized Entry get(String kind) {
        Entry e = ENTRIES.get(kind);
        if (e == null) {
            e = load(kind);
            ENTRIES.put(kind, e);
        }
        if (e.failed) {
            String fb = FALLBACK.get(kind);
            if (fb != null && !fb.equals(kind)) {
                return get(fb);
            }
        }
        return e;
    }

    private static Entry load(String kind) {
        ResourceLocation modelLoc = new ResourceLocation(HertzianRefs.MODID, "models/block/" + kind + ".obj");
        ResourceLocation textureLoc = new ResourceLocation(HertzianRefs.MODID, "textures/blocks/" + kind + ".png");
        try {
            IModelCustom model = AdvancedModelLoader.loadModel(modelLoc);
            HertzianDynamics.LOGGER.info("Loaded OBJ model {}", modelLoc);
            return new Entry(model, textureLoc, false, computeBounds(model));
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.error("Failed to load OBJ model {}", modelLoc, t);
            return new Entry(null, textureLoc, true, defaultBounds());
        }
    }

    /**
     * Axis-aligned bounds of the parsed mesh. Reads the vertex list of
     * the Forge {@link WavefrontObject}. Any failure (different model
     * implementation, inaccessible field) returns the unit-block box so
     * the renderer keeps working.
     */
    private static float[] computeBounds(IModelCustom model) {
        try {
            if (model instanceof WavefrontObject) {
                WavefrontObject wo = (WavefrontObject) model;
                List<Vertex> verts = wo.vertices;
                if (verts != null && !verts.isEmpty()) {
                    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                    float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                    for (Vertex v : verts) {
                        if (v.x < minX) minX = v.x;
                        if (v.y < minY) minY = v.y;
                        if (v.z < minZ) minZ = v.z;
                        if (v.x > maxX) maxX = v.x;
                        if (v.y > maxY) maxY = v.y;
                        if (v.z > maxZ) maxZ = v.z;
                    }
                    return new float[] { minX, minY, minZ, maxX, maxY, maxZ };
                }
            }
        } catch (Throwable t) {
            HertzianDynamics.LOGGER.warn("OBJ bounds read failed; using unit box", t);
        }
        return defaultBounds();
    }

    /** Unit block centred on X and Z, standing on the floor on Y. */
    private static float[] defaultBounds() {
        return new float[] { -0.5f, 0.0f, -0.5f, 0.5f, 1.0f, 0.5f };
    }
}
