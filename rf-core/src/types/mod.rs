//! Plain data shared between CPU code and GPU compute shaders.
//!
//! Anything in this module that may be uploaded to a Vulkan buffer
//! is marked `#[repr(C)]` with explicit field order. The matching
//! GLSL declaration must follow the same field order one to one.
//! A compile time `assert!` on `size_of` guards each layout.
//!
//! Nothing here depends on the `vulkan` module. The split is
//! intentional so unit tests that only touch types run without
//! requiring a GPU.

pub mod iq;
pub mod modulation;
pub mod emission;
pub mod spectrum;
pub mod propagation;