//! rf-jni build script.
//!
//! Emits a small Cargo metadata file that downstream Java tooling
//! reads at packaging time to discover the produced cdylib name per
//! platform. The mapping rules are:
//!   Linux:   librf_jni.so
//!   macOS:   librf_jni.dylib
//!   Windows: rf_jni.dll
//! The Java side ships these under /native/<os>/<arch>/ inside the
//! mod jar; NativeLoader picks the right one at runtime.
//!
//! Naming rationale: the cdylib is the JNI-facing wrapper over
//! rf-core, not rf-core itself. The earlier attempt to name it
//! librf_core.so produced a Cargo lib-name collision between this
//! crate (cdylib) and the path-dependency rf-core (rlib) at test
//! compile time, so the canonical name is now rf_jni.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let lib = match target_os.as_str() {
        "windows" => "rf_jni.dll",
        "macos" => "librf_jni.dylib",
        _ => "librf_jni.so",
    };
    println!("cargo:rustc-env=HD_CDYLIB_NAME={lib}");
}