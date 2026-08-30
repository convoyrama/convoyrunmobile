//! Android JNI initialization for `ndk_context`.
//!
//! iroh's DNS resolver reads `LinkProperties.getDnsServers()` through
//! `ndk_context`, which must be initialized with the process's JavaVM
//! and `Application` context before any `Endpoint` is constructed.
//!
//! The Kotlin layer calls `ConvoyRunP2p.installAndroidContext(applicationContext)`
//! once at process startup; that call lands here and stores the pointers
//! as a JNI global reference for the lifetime of the process.
//!
//! Only one initialization succeeds; subsequent calls are no-ops.

use std::sync::Once;

static INIT: Once = Once::new();

/// JNI entry point: called from Kotlin to initialize the Android context.
///
/// # Safety
///
/// The `context` parameter must be a valid JNI reference to an `Application` context.
/// This function stores it as a global reference that persists for the process lifetime.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_convoyrama_convoyrun_p2p_ConvoyRunP2p_installAndroidContext<'local>(
    mut env: jni::EnvUnowned<'local>,
    _class: jni::objects::JClass<'local>,
    context: jni::objects::JObject<'local>,
) {
    INIT.call_once(|| {
        env.with_env(|env| -> jni::errors::Result<()> {
            let java_vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(&context)?;
            unsafe {
                ndk_context::initialize_android_context(
                    java_vm.get_raw() as *mut std::ffi::c_void,
                    global_ref.as_obj().as_raw() as *mut std::ffi::c_void,
                );
            }
            // Keep the global ref alive forever; ndk_context holds the raw
            // pointer and expects it to stay valid for the rest of the process.
            std::mem::forget(global_ref);
            Ok(())
        })
        .resolve::<jni::errors::LogErrorAndDefault>();
    });
}

/// Fallback for non-Android builds (desktop testing, etc.)
#[cfg(not(target_os = "android"))]
pub fn init_android_context_fallback() {
    // No-op on non-Android platforms
}
