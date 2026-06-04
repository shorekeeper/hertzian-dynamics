//! Build script: compile every .comp file under shaders/ to SPIR-V.
//!
//! We try `glslc` (Google shaderc, the LunarG SDK default) first and
//! fall back to `glslangValidator` (Khronos reference compiler). Both
//! emit Vulkan 1.2 SPIR-V from GLSL 450 sources. The resulting blobs
//! land in OUT_DIR with the same stem as the source. Rust modules
//! embed them via `include_bytes!(concat!(env!("OUT_DIR"), "/<name>.spv"))`.
//!
//! Cargo only reruns this script when something under shaders/ changes
//! (or when the script itself changes). The script does no caching of
//! its own; the upstream compilers are fast enough.
//!
//! If neither compiler is on PATH the script fails the build with an
//! actionable message.

use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let shaders_dir = manifest.join("shaders");
    let out_dir = PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR"));

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed={}", shaders_dir.display());

    if !shaders_dir.is_dir() {
        // Nothing to do. Earlier iterations may have an empty dir.
        return;
    }

    let entries = std::fs::read_dir(&shaders_dir).expect("read shaders dir");
    for entry in entries {
        let entry = entry.expect("dir entry");
        let path = entry.path();
        let ext = path.extension().and_then(|s| s.to_str());
        if ext != Some("comp") {
            continue;
        }
        println!("cargo:rerun-if-changed={}", path.display());

        let stem = path.file_stem().and_then(|s| s.to_str()).expect("utf8 stem");
        let spv_path = out_dir.join(format!("{stem}.spv"));
        compile_one(&path, &spv_path);
    }
}

fn compile_one(src: &Path, dst: &Path) {
    // Attempt glslc first.
    let glslc_status = Command::new("glslc")
        .arg("--target-env=vulkan1.2")
        .arg("-O")
        .arg("-o")
        .arg(dst)
        .arg(src)
        .status();
    if let Ok(s) = glslc_status {
        if s.success() {
            return;
        }
    }

    // Fall back to glslangValidator.
    let glslang_status = Command::new("glslangValidator")
        .arg("-V")
        .arg("--target-env")
        .arg("vulkan1.2")
        .arg("-o")
        .arg(dst)
        .arg(src)
        .status();
    if let Ok(s) = glslang_status {
        if s.success() {
            return;
        }
    }

    panic!(
        "Could not compile shader {}: neither glslc nor glslangValidator \
         is available on PATH. Install the LunarG Vulkan SDK or the \
         platform glslang package.",
        src.display()
    );
}