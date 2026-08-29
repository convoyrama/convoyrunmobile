package com.convoyrun.mobile

import android.app.Application

/**
 * ConvoyRun Application class
 *
 * Initializes global state and the P2P node on startup.
 */
class ConvoyRunApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Android context for Rust FFI
        // This must be called before any iroh operations
        try {
            ConvoyRunP2p.installAndroidContext(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * JNI bridge to the Rust FFI
 */
external object ConvoyRunP2p {
    fun installAndroidContext(context: android.content.Context)
}
