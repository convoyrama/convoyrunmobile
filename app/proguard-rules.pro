# ConvoyRun Mobile ProGuard Rules

# Keep JNI entry point
-keep class com.convoyrun.mobile.p2p.ConvoyRunP2p { *; }

# Keep data classes for serialization
-keep class com.convoyrun.mobile.model.** { *; }

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class com.convoyrun.mobile.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.convoyrun.mobile.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# General Android optimizations
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
