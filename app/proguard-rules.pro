# ConvoyRun Mobile ProGuard Rules

# Keep JNI entry point
-keep class com.convoyrama.convoyrun.p2p.ConvoyRunP2p { *; }

# Keep data classes for serialization
-keep class com.convoyrama.convoyrun.model.** { *; }

# Keep UniFFI generated bindings (JNA structures need field names preserved)
-keep class uniffi.convoyrun_mobile_ffi.** { *; }
-keepclassmembers class uniffi.convoyrun_mobile_ffi.** { *; }

# Keep JNA Structure field order methods
-keepclassmembers class * extends com.sun.jna.Structure {
    java.util.List getFieldOrder();
}

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class com.convoyrama.convoyrun.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.convoyrama.convoyrun.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# General Android optimizations
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
