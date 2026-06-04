package io.hertzian.dynamics;

/**
 * Compile-time constants shared across the mod. Pinned in one place
 * so a typo in any of these strings fails to compile rather than
 * causing a registration mismatch at runtime.
 */
public final class HertzianRefs {

    private HertzianRefs() {}

    public static final String MODID = "hertzian";
    public static final String NAME = "Hertzian Dynamics";
    public static final String VERSION = "0.1.0";

    /** Forge dependency string declared in the @Mod annotation. */
    public static final String DEPENDENCIES = "required-after:Forge;required-after:lwjgl3ify";

    /** Proxy class name as Forge expects it (server first, client second). */
    public static final String SERVER_PROXY = "io.hertzian.dynamics.proxy.CommonProxy";
    public static final String CLIENT_PROXY = "io.hertzian.dynamics.proxy.ClientProxy";

    /** Creative tab label key. */
    public static final String CREATIVE_TAB = "hertzian.radio";

    /**
     * Engine sample rate that the Rust side and the audio pipeline
     * agree on. Mirrors {@code ENGINE_SAMPLE_RATE_HZ} in rf-core.
     */
    public static final int ENGINE_SAMPLE_RATE_HZ = 48_000;
}
