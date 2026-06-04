//! Vulkan smoke tests. Gated by the `gpu_tests` feature because a
//! working Vulkan device is required.

#![cfg(feature = "gpu_tests")]

use rf_core::{RfCore, RfCoreConfig};

#[test]
fn rf_core_constructs_and_drops_cleanly() {
    let cfg = RfCoreConfig::default();
    let core = RfCore::new(cfg).expect("rf-core must initialise on this host");
    core.device().wait_idle().expect("wait idle after construction");
    let info = core.device().properties();
    let limits = info.limits;
    assert!(limits.max_storage_buffer_range > 0);
    drop(core);
}

#[test]
fn pipeline_cache_serialises_to_nonempty_blob() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init");
    let blob = core
        .pipeline_cache()
        .serialize()
        .expect("serialise pipeline cache");
    assert!(
        blob.len() >= 16,
        "pipeline cache blob suspiciously small: {} bytes",
        blob.len()
    );
}

#[test]
fn descriptor_pool_reports_capacity() {
    let core = RfCore::new(RfCoreConfig::default()).expect("init");
    let cap = core.descriptor_pool().capacity();
    assert!(cap.max_sets >= 1);
    assert!(cap.max_storage_buffers >= 1);
}