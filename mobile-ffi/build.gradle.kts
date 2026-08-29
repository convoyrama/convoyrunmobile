import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.*

plugins {
    kotlin("multiplatform") version "2.1.10"
    id("org.jetbrains.kotlin.plugin.atomicfu") version "2.1.10"
    id("dev.gobley.cargo") version "0.3.7"
    id("dev.gobley.uniffi") version "0.3.7"
    id("com.android.library")
}

android {
    namespace = "com.convoyrun.mobile.ffi"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation("net.java.dev.jna:jna:5.18.1@aar")
            }
        }
    }
}

cargo {
    packageDirectory = layout.projectDirectory
    builds {
        android {
            dynamicLibraries.addAll("c++_shared")
        }
    }
    variants {
        features.addAll("default")
    }
}

uniffi {
    generateFromLibrary {
        namespace = "convoyrun_mobile_ffi"
    }
}
