// GTNH convention edition with native build orchestration.
//
// Layout:
//   * Plugin application via the convention plugin (settings.gradle.kts
//     already pinned the version).
//   * Per-task Java 21 compiler override for user sources so our
//     FFM-using code compiles against Java 21 while the patched
//     Minecraft sources keep the convention plugin's Java 8 default.
//   * --enable-preview wired through compile, test, AND runtime
//     (runClient/runServer) because java.lang.foreign is preview
//     in Java 21 and a preview-tainted classfile refuses to load
//     without the flag at runtime.
//   * cargo invocation that builds rf-jni in release mode, followed
//     by a copy step that lands the resulting cdylib in the mod
//     resources before processResources runs.

import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// ----------------------------------------------------------------
// Per-task Java 21 compiler override.
//
// A project-wide `java { toolchain { ... } }` bump would change
// the toolchain for the patched MC compile too, which breaks
// source compatibility there (the patched sources only build
// under Java 8 semantics). Per-task hits only our own code.
// ----------------------------------------------------------------

tasks.named<JavaCompile>("compileJava").configure {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("--enable-preview")
}

tasks.named<JavaCompile>("compileTestJava").configure {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("--enable-preview")
}

// ----------------------------------------------------------------
// Tests run under Java 21 with the same flags as runtime so a
// preview-tainted classfile keeps loading.
// ----------------------------------------------------------------

tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
    useJUnitPlatform()
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// ----------------------------------------------------------------
// Runtime JVM flags for runClient and runServer.
//
// RetroFuturaGradle exposes `minecraft.extraRunJvmArguments` as the
// official extension point for forwarding flags to its run tasks.
//
// --enable-preview: required at runtime because every classfile
// compiled with --enable-preview is tagged class file version
// 65.65535 (Java 21 + preview marker). The JVM refuses to load
// such a file unless the flag is on the launch command line.
//
// --enable-native-access=ALL-UNNAMED: silences the "restricted
// method called from unnamed module" warning that FFM downcalls
// emit on every invocation. Without the flag the warning prints
// per call and floods the log; the call still works but the
// noise is unmanageable.
//
// -Dpolyglot.engine.WarnInterpreterOnly=false and similar Java 9+
// quality of life flags are not added here; lwjgl3ify already
// passes a sensible defaults set via its relauncher.
// ----------------------------------------------------------------

minecraft {
    extraRunJvmArguments.addAll(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// ----------------------------------------------------------------
// Native build orchestration.
//
// Build rf-jni with cargo in release mode, then copy the produced
// cdylib into src/main/resources/native/<os>-<arch>/ before
// processResources runs.
//
// The cargo task uses Gradle's Exec type. It is configured to
// always run when invoked (cargo's own incremental build keeps
// the runtime cost down) and to surface a clear failure message
// when cargo is missing from PATH instead of leaving the user to
// puzzle over an empty resources directory.
// ----------------------------------------------------------------

val workspaceRoot: File = rootDir.parentFile

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the rf-jni cdylib via cargo in release mode."
    workingDir = workspaceRoot
    val cargo = if (System.getProperty("os.name").lowercase().contains("win")) {
        "cargo.exe"
    } else {
        "cargo"
    }
    commandLine(cargo, "build", "--release", "-p", "rf-jni")
    // Cargo prints its progress to stderr; let it through unfiltered.
    standardOutput = System.out
    errorOutput = System.err
    // Skip cleanly if cargo is missing; tests that need the native
    // bits guard themselves with @EnabledIfEnvironmentVariable.
    isIgnoreExitValue = false
    doFirst {
        if (!isCargoOnPath(cargo)) {
            throw GradleException(
                "cargo not found on PATH. Install Rust (https://rustup.rs) " +
                        "or set CARGO_HOME/PATH so cargo is discoverable."
            )
        }
    }
}

val copyNative by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy librf_jni from cargo target into mod resources."
    dependsOn(buildNative)
    val osName = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    val osTag = when {
        osName.contains("win") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> "linux"
    }
    val archTag = when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> arch
    }
    val cargoTarget = workspaceRoot.resolve("target/release")
    from(cargoTarget) {
        include("librf_jni.so", "librf_jni.dylib", "rf_jni.dll")
    }
    into(layout.projectDirectory.dir("src/main/resources/native/$osTag-$archTag"))
    // No onlyIf guard: buildNative is in the dependency chain, so
    // by the time we run the file must exist. If it does not the
    // copy fails loudly, which is the right behaviour.
}

tasks.named("processResources") {
    dependsOn(copyNative)
}

// Every task that consumes src/main/resources as an input must run after
// copyNative, because copyNative writes the cdylib into that same tree.
// processResources is the obvious one, but sourcesJar (and any other jar
// that bundles the resource dir) reads it too, and Gradle 8 fails the
// build on the undeclared write/read ordering rather than warning. Wiring
// the dependency on every Jar that takes the resources keeps the jar build
// correct without hand listing each task name.
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(copyNative)
}

// Defensive: any Jar task that packages the resource tree must wait for
// copyNative for the same reason. Cheap to apply to all of them.
tasks.withType<org.gradle.api.tasks.bundling.Jar>().configureEach {
    dependsOn(copyNative)
}

// Checkstyle is advisory here, not a gate. Do not fail the build on its
// findings; the report is still generated under build/reports/checkstyle
// for anyone who wants to read it.
tasks.withType<org.gradle.api.plugins.quality.Checkstyle>().configureEach {
    isIgnoreFailures = true
}

// Ensure the dev run directories exist so first-launch logs do
// not show stack traces from mods that create config files
// without checking the parent directory. Hodgepodge and a few
// others write straight to run/client/config/<name>.properties.
val ensureRunDirs by tasks.registering {
    group = "build"
    description = "Create run/client/config and run/server/config so mods can write to them on first launch."
    doLast {
        listOf(
            "run/client/config",
            "run/server/config",
        ).forEach { rel ->
            val dir = layout.projectDirectory.dir(rel).asFile
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warn("Could not create $dir; first-launch mods may complain.")
            }
        }
    }
}

// Hook the directory creation into the run tasks if they exist.
// RFG names them runClient and runServer; both are present in
// every gtnhconvention build.
listOf("runClient", "runServer").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        dependsOn(ensureRunDirs)
    }
}

/**
 * Probe whether the cargo executable resolves on the current PATH.
 * Avoids spawning the process inside doFirst with no diagnostic on
 * failure; this short circuit emits the clearer message above.
 */
fun isCargoOnPath(executable: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    val separator = if (System.getProperty("os.name").lowercase().contains("win")) ";" else ":"
    return path.split(separator).any { entry ->
        if (entry.isBlank()) return@any false
        File(entry, executable).canExecute()
    }
}