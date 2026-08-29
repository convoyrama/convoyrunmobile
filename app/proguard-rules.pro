# ConvoyRun Mobile ProGuard Rules

# Keep Rust FFI classes
-keep class com.convoyrun.mobile.p2p.ConvoyRunP2p { *; }
-keep class com.convoyrun.mobile.ConvoyRunApplication { *; }

# Keep data classes for serialization
-keep class com.convoyrun.mobile.model.** { *; }

# Keep JNA (required for native library loading)
-keep class com.sun.jna.** { *; }

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class com.convoyrun.mobile.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.convoyrun.mobile.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep UniFFI generated classes
-keep class com.convoyrun.mobile.ffi.** { *; }

# General Android optimizations
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
