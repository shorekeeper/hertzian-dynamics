//! Vulkan instance and optional debug messenger.
//!
//! The instance is the root of every Vulkan object graph. We load it
//! through `ash::Entry::load`, which `dlopen`s the platform Vulkan
//! loader at runtime. This makes the resulting Minecraft mod start
//! cleanly on hosts without Vulkan, with an `RfCoreError::LoaderLoad`
//! surfaced to the Java side instead of a hard process abort.
//!
//! The debug messenger is wired only when the crate is compiled with
//! the `validation` feature and the host has the Khronos validation
//! layer installed. Failures to attach the messenger are not fatal,
//! the instance is still usable without it.

use std::ffi::{CStr, CString};

use ash::vk;

use crate::error::{vk_err, Result, RfCoreError};

/// Owning wrapper around `vk::Instance`.
///
/// Holds the `Entry` it was created from so the underlying loader
/// library stays mapped for the lifetime of the instance.
pub struct VulkanInstance {
    // Field declaration order is intentional: the debug messenger
    // must be destroyed before the instance, the instance before the
    // entry. Rust drops fields in declaration order.
    debug: Option<DebugMessenger>,
    instance: ash::Instance,
    entry: ash::Entry,
}

/// Optional Khronos validation messenger.
struct DebugMessenger {
    loader: ash::ext::debug_utils::Instance,
    handle: vk::DebugUtilsMessengerEXT,
}

impl VulkanInstance {
    /// Create a Vulkan 1.2 instance with the given application name.
    /// 1.2 is the floor: it ships in every driver from 2020 onward
    /// and gives us timeline semaphores plus 16 bit storage which
    /// code later will rely on.
    pub fn new(app_name: &str) -> Result<Self> {
        // SAFETY: Entry::load dlopens the platform Vulkan loader.
        // The unsafety is inherent to loading a native library. We
        // hold on to the resulting Entry for the lifetime of this
        // wrapper, satisfying the documented requirement that the
        // Entry outlive every handle derived from it.
        let entry = unsafe { ash::Entry::load() }
            .map_err(|e| RfCoreError::LoaderLoad(e.to_string()))?;

        let app_name_c = CString::new(app_name)
            .map_err(|_| RfCoreError::InvalidArgument("app name contains NUL byte"))?;
        let engine_name_c = CString::new("hertzian-dynamics rf-core")
            .expect("static literal has no NUL");

        let app_info = vk::ApplicationInfo::default()
            .application_name(&app_name_c)
            .application_version(vk::make_api_version(0, 0, 1, 0))
            .engine_name(&engine_name_c)
            .engine_version(vk::make_api_version(0, 0, 1, 0))
            .api_version(vk::API_VERSION_1_2);

        // Collect enabled layers and extensions. Both lists must
        // outlive the InstanceCreateInfo because the create info
        // stores raw pointers into them.
        let mut enabled_layer_ptrs: Vec<*const std::ffi::c_char> = Vec::new();
        let mut enabled_ext_ptrs: Vec<*const std::ffi::c_char> = Vec::new();

        // Borrowed CStrings stay alive until end of function thanks
        // to being held in this Option.
        #[allow(unused_assignments)]
        let mut validation_layer_cstr: Option<CString> = None;

        #[cfg(feature = "validation")]
        {
            let want = CString::new("VK_LAYER_KHRONOS_validation")
                .expect("static literal has no NUL");
            if has_layer(&entry, &want)? {
                enabled_layer_ptrs.push(want.as_ptr());
                enabled_ext_ptrs.push(ash::ext::debug_utils::NAME.as_ptr());
                validation_layer_cstr = Some(want);
            } else {
                // Validation requested but unavailable. Continue
                // without it. The user is expected to install the
                // LunarG SDK or the distro validation package.
                eprintln!("[rf-core] validation requested but VK_LAYER_KHRONOS_validation is not present");
            }
        }

        let create_info = vk::InstanceCreateInfo::default()
            .application_info(&app_info)
            .enabled_layer_names(&enabled_layer_ptrs)
            .enabled_extension_names(&enabled_ext_ptrs);

        // SAFETY: create_info pointers reference local stack data
        // that lives for the duration of this call.
        let instance = unsafe { entry.create_instance(&create_info, None) }
            .map_err(|r| vk_err("vkCreateInstance", r.into()))?;

        // Attach a debug messenger only if debug_utils is enabled.
        let debug = if enabled_ext_ptrs.iter().any(|p| {
            // SAFETY: the pointer originates from ash constants or
            // a CString we keep alive on the stack.
            let s = unsafe { CStr::from_ptr(*p) };
            s == ash::ext::debug_utils::NAME
        }) {
            attach_debug_messenger(&entry, &instance).ok()
        } else {
            None
        };

        // Tie validation_layer_cstr lifetime to function scope by
        // touching it post creation.
        drop(validation_layer_cstr);

        Ok(Self { debug, instance, entry })
    }

    /// Borrow the raw `ash::Entry`. Used by the device layer to look
    /// up instance level function pointers that depend on the entry.
    pub(crate) fn entry(&self) -> &ash::Entry {
        &self.entry
    }

    /// Borrow the raw `ash::Instance` for inner modules.
    pub(crate) fn raw(&self) -> &ash::Instance {
        &self.instance
    }

    /// Enumerate every physical device the loader reports. The caller
    /// applies its own scoring to pick one.
    pub fn physical_devices(&self) -> Result<Vec<vk::PhysicalDevice>> {
        // SAFETY: instance is alive for the duration of the call.
        unsafe { self.instance.enumerate_physical_devices() }
            .map_err(|r| vk_err("vkEnumeratePhysicalDevices", r))
    }
}

impl Drop for VulkanInstance {
    fn drop(&mut self) {
        // SAFETY: we are inside Drop. The debug messenger handle was
        // produced by the loader we still hold, the instance handle
        // is still valid, and no Vulkan calls into these handles are
        // outstanding by the documented contract on the caller.
        unsafe {
            if let Some(d) = self.debug.take() {
                d.loader.destroy_debug_utils_messenger(d.handle, None);
            }
            self.instance.destroy_instance(None);
        }
    }
}

#[cfg(feature = "validation")]
fn has_layer(entry: &ash::Entry, want: &CStr) -> Result<bool> {
    // SAFETY: entry is alive for the duration of the call.
    let layers = unsafe { entry.enumerate_instance_layer_properties() }
        .map_err(|r| vk_err("vkEnumerateInstanceLayerProperties", r))?;
    Ok(layers.iter().any(|l| {
        // SAFETY: the layer_name field is a fixed size NUL terminated
        // array provided by the driver.
        let name = unsafe { CStr::from_ptr(l.layer_name.as_ptr()) };
        name == want
    }))
}

fn attach_debug_messenger(
    entry: &ash::Entry,
    instance: &ash::Instance,
) -> Result<DebugMessenger> {
    let loader = ash::ext::debug_utils::Instance::new(entry, instance);
    let info = vk::DebugUtilsMessengerCreateInfoEXT::default()
        .message_severity(
            vk::DebugUtilsMessageSeverityFlagsEXT::WARNING
                | vk::DebugUtilsMessageSeverityFlagsEXT::ERROR,
        )
        .message_type(
            vk::DebugUtilsMessageTypeFlagsEXT::GENERAL
                | vk::DebugUtilsMessageTypeFlagsEXT::VALIDATION
                | vk::DebugUtilsMessageTypeFlagsEXT::PERFORMANCE,
        )
        .pfn_user_callback(Some(debug_callback));
    // SAFETY: info pointers reference local stack data alive for
    // the duration of the call.
    let handle = unsafe { loader.create_debug_utils_messenger(&info, None) }
        .map_err(|r| vk_err("vkCreateDebugUtilsMessengerEXT", r))?;
    Ok(DebugMessenger { loader, handle })
}

/// Bridge from Vulkan layer messages to stderr. Static function so it
/// has a stable address suitable for use as a C callback.
unsafe extern "system" fn debug_callback(
    severity: vk::DebugUtilsMessageSeverityFlagsEXT,
    _ty: vk::DebugUtilsMessageTypeFlagsEXT,
    data: *const vk::DebugUtilsMessengerCallbackDataEXT<'_>,
    _user: *mut std::ffi::c_void,
) -> vk::Bool32 {
    // SAFETY: the loader hands us a valid pointer whose pointee is
    // alive for the duration of the callback.
    let text = if data.is_null() {
        std::borrow::Cow::Borrowed("<null callback data>")
    } else {
        let msg_ptr = unsafe { (*data).p_message };
        if msg_ptr.is_null() {
            std::borrow::Cow::Borrowed("<null message>")
        } else {
            unsafe { CStr::from_ptr(msg_ptr) }.to_string_lossy()
        }
    };
    eprintln!("[vk {severity:?}] {text}");
    vk::FALSE
}