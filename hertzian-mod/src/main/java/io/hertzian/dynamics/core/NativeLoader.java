package io.hertzian.dynamics.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Resolves and extracts the rf-jni cdylib from the mod jar to a
 * temporary location, then returns the resolved {@link Path}. The
 * caller passes that path to {@link io.hertzian.dynamics.core.internal.Native#ensureLoaded}.
 *
 * <p>
 * Library file layout inside the jar resources:
 * 
 * <pre>
 *   /native/linux-x86_64/librf_jni.so
 *   /native/macos-x86_64/librf_jni.dylib
 *   /native/macos-aarch64/librf_jni.dylib
 *   /native/windows-x86_64/rf_jni.dll
 * </pre>
 *
 * The selection rules read {@code os.name} and {@code os.arch} and
 * pick the matching resource. Unsupported combinations throw an
 * exception with a clear message.
 *
 * <p>
 * Why "rf_jni" rather than "rf_core"? The cdylib is the FFI
 * surface, not the engine itself. Cargo's rlib resolver collides
 * if the cdylib uses the same name as the path-dependency that
 * supplies the rlib; renaming the cdylib avoids the collision and
 * keeps the engine package free of FFI-specific naming concerns.
 */
public final class NativeLoader {

    private NativeLoader() {}

    /**
     * Extract the embedded cdylib to a fresh temp file and return
     * its path. The temp file is marked for deletion on JVM exit;
     * the underlying file system is responsible for actually
     * removing it. The JVM keeps the file mapped for the lifetime
     * of the SymbolLookup, so an early delete is harmless.
     */
    public static Path extractEmbeddedLibrary() {
        String resource = chooseResource();
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("native library not in jar: " + resource);
            }
            String fileName = resource.substring(resource.lastIndexOf('/') + 1);
            Path tmp = Files.createTempFile("rf_jni_", "_" + fileName);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile()
                .deleteOnExit();
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException("failed to extract native library", e);
        }
    }

    private static String chooseResource() {
        String os = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "")
            .toLowerCase(Locale.ROOT);
        String osTag;
        String libName;
        if (os.contains("win")) {
            osTag = "windows";
            libName = "rf_jni.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osTag = "macos";
            libName = "librf_jni.dylib";
        } else if (os.contains("nux") || os.contains("nix")) {
            osTag = "linux";
            libName = "librf_jni.so";
        } else {
            throw new IllegalStateException("unsupported os: " + os);
        }
        String archTag;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archTag = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archTag = "aarch64";
        } else {
            throw new IllegalStateException("unsupported arch: " + arch);
        }
        return "/native/" + osTag + "-" + archTag + "/" + libName;
    }
}
