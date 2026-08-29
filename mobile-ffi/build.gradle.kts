import gobley.gradle.cargo.dsl.*

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.atomicfu")
    id("dev.gobley.cargo")
    id("dev.gobley.uniffi")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}

uniffi {
    generateFromLibrary {
        namespace = "convoyrun_mobile_ffi"
    }
}
