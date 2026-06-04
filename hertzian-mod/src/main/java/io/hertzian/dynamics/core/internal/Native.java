package io.hertzian.dynamics.core.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import io.hertzian.dynamics.core.NativeLoader;

/**
 * One-time bound table of {@link MethodHandle} for every rf-core
 * C symbol. Loaded once per JVM by {@link #ensureLoaded(Path)};
 * subsequent calls are no-ops. The class deliberately avoids any
 * static initialiser side effect so the consumer controls when
 * the cdylib is opened.
 *
 * <p>
 * Method handles are stored in {@code public static final}
 * fields so the JIT inlines them aggressively. Each handle is
 * invoked through {@code invokeExact}; any signature drift
 * surfaces as a {@code WrongMethodTypeException} on the first
 * call rather than as silent memory corruption.
 */
public final class Native {

    private Native() {}

    private static volatile boolean loaded = false;
    private static volatile Arena libraryArena;
    private static volatile SymbolLookup lookup;

    public static MethodHandle HD_ABI_VERSION;
    public static MethodHandle HD_VERSION_STRING;

    public static MethodHandle HD_CORE_CREATE;
    public static MethodHandle HD_CORE_CREATE_EX;
    public static MethodHandle HD_CORE_SET_COMPUTE_POLICY;
    public static MethodHandle HD_CORE_COMPUTE_STATS;
    public static MethodHandle HD_CORE_DESTROY;

    public static MethodHandle HD_RAYCAST_BATCH;

    public static MethodHandle HD_GRID_CREATE;
    public static MethodHandle HD_GRID_DESTROY;
    public static MethodHandle HD_GRID_SET_VOXEL;

    public static MethodHandle HD_MATERIALS_CREATE_DEFAULTS;
    public static MethodHandle HD_MATERIALS_DESTROY;
    public static MethodHandle HD_MATERIALS_REGISTER;

    public static MethodHandle HD_IONO_CREATE;
    public static MethodHandle HD_IONO_DESTROY;

    public static MethodHandle HD_MANAGER_CREATE;
    public static MethodHandle HD_MANAGER_DESTROY;
    public static MethodHandle HD_MANAGER_SET_CURVATURE;

    public static MethodHandle HD_MANAGER_REGISTER_EMISSION;
    public static MethodHandle HD_MANAGER_UNREGISTER_EMISSION;
    public static MethodHandle HD_MANAGER_PUSH_AUDIO;
    public static MethodHandle HD_MANAGER_SET_EMISSION_POSITION;

    public static MethodHandle HD_MANAGER_REGISTER_RECEIVER;
    public static MethodHandle HD_MANAGER_UNREGISTER_RECEIVER;
    public static MethodHandle HD_MANAGER_SET_RECEIVER_POSITION;
    public static MethodHandle HD_MANAGER_SET_RECEIVER_TUNING;

    public static MethodHandle HD_MANAGER_MIX_CHUNK;
    public static MethodHandle HD_ZOOM_DFT;

    /**
     * Load the cdylib if it has not been loaded yet. Idempotent
     * and thread safe. The {@code libraryPath} either points at
     * a previously extracted cdylib file or is null, in which
     * case {@link NativeLoader} extracts the platform-specific
     * variant from the JAR.
     *
     * @param libraryPath explicit cdylib path; pass null to use
     *                    the resource-embedded variant.
     */
    public static synchronized void ensureLoaded(Path libraryPath) {
        if (loaded) return;

        // FFM is preview in Java 21. Verify the launch JVM has the
        // flag before we attempt any linker work; the failure mode
        // without the flag is a confusing UnsupportedClassVersion or
        // a NoClassDefFoundError on java.lang.foreign.Linker. Catching
        // it here surfaces a directly actionable message.
        if (Runtime.version()
            .feature() < 22) {
            try {
                Class.forName("java.lang.foreign.Linker");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "Hertzian Dynamics requires Java 21 with --enable-preview or Java 22+. " + "Detected Java "
                        + Runtime.version()
                            .feature()
                        + " without java.lang.foreign on the classpath. "
                        + "Launch the JVM with --enable-preview --enable-native-access=ALL-UNNAMED.",
                    e);
            }
        }

        Path resolved = libraryPath != null ? libraryPath : NativeLoader.extractEmbeddedLibrary();
        libraryArena = Arena.ofShared();
        lookup = SymbolLookup.libraryLookup(resolved, libraryArena);

        Linker linker = Linker.nativeLinker();

        HD_ABI_VERSION = bind(
            linker,
            "hd_abi_version",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_VERSION_STRING = bind(linker, "hd_version_string", FunctionDescriptor.of(ValueLayout.ADDRESS));

        HD_CORE_CREATE = bind(
            linker,
            "hd_core_create",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_CORE_CREATE_EX = bind(
            linker,
            "hd_core_create_ex",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_CORE_SET_COMPUTE_POLICY = bind(
            linker,
            "hd_core_set_compute_policy",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG));
        HD_CORE_COMPUTE_STATS = bind(
            linker,
            "hd_core_compute_stats",
            FunctionDescriptor
                .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_CORE_DESTROY = bind(
            linker,
            "hd_core_destroy",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        HD_GRID_CREATE = bind(
            linker,
            "hd_grid_create",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS));
        HD_GRID_DESTROY = bind(
            linker,
            "hd_grid_destroy",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_GRID_SET_VOXEL = bind(
            linker,
            "hd_grid_set_voxel",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_SHORT));

        HD_MATERIALS_CREATE_DEFAULTS = bind(
            linker,
            "hd_materials_create_defaults",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_MATERIALS_DESTROY = bind(
            linker,
            "hd_materials_destroy",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_MATERIALS_REGISTER = bind(
            linker,
            "hd_materials_register",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT));

        HD_IONO_CREATE = bind(
            linker,
            "hd_iono_create",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        HD_IONO_DESTROY = bind(
            linker,
            "hd_iono_destroy",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        HD_MANAGER_CREATE = bind(
            linker,
            "hd_manager_create",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        HD_MANAGER_DESTROY = bind(
            linker,
            "hd_manager_destroy",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        HD_MANAGER_SET_CURVATURE = bind(
            linker,
            "hd_manager_set_curvature",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT));

        HD_MANAGER_REGISTER_EMISSION = bind(
            linker,
            "hd_manager_register_emission",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        HD_MANAGER_UNREGISTER_EMISSION = bind(
            linker,
            "hd_manager_unregister_emission",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        HD_MANAGER_PUSH_AUDIO = bind(
            linker,
            "hd_manager_push_audio",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG));
        HD_MANAGER_SET_EMISSION_POSITION = bind(
            linker,
            "hd_manager_set_emission_position",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT));

        HD_MANAGER_REGISTER_RECEIVER = bind(
            linker,
            "hd_manager_register_receiver",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        HD_MANAGER_UNREGISTER_RECEIVER = bind(
            linker,
            "hd_manager_unregister_receiver",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        HD_MANAGER_SET_RECEIVER_POSITION = bind(
            linker,
            "hd_manager_set_receiver_position",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT));
        HD_MANAGER_SET_RECEIVER_TUNING = bind(
            linker,
            "hd_manager_set_receiver_tuning",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_FLOAT));

        HD_MANAGER_MIX_CHUNK = bind(
            linker,
            "hd_manager_mix_chunk",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS));
        HD_ZOOM_DFT = bind(
            linker,
            "hd_zoom_dft",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS));
        HD_RAYCAST_BATCH = bind(
            linker,
            "hd_raycast_batch",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_DOUBLE,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.ADDRESS));
        loaded = true;
        verifyAbiOrThrow();
    }

    private static MethodHandle bind(Linker linker, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("symbol not found: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static void verifyAbiOrThrow() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            int rc = (int) HD_ABI_VERSION.invokeExact(out);
            if (rc != 0) throw new IllegalStateException("hd_abi_version returned " + rc);
            int v = out.get(ValueLayout.JAVA_INT, 0L);
            if (v != 1) {
                throw new IllegalStateException("rf-core ABI mismatch: native=" + v + ", java=" + 1);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("ABI verification failed", t);
        }
    }
}
