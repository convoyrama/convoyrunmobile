plugins {
    kotlin("multiplatform") version "2.1.10"
    id("dev.gobley.cargo") version "0.3.7"
    id("dev.gobley.uniffi") version "0.3.7"
    id("com.android.library")
}

android {
    namespace = "com.convoyrun.mobile.ffi"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
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
            // Build for ARM64, ARM32, and x86_64 (emulator)
            targets = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

            // Use release profile for final builds
            release {
                profile = CargoProfile.Release
            }
        }
    }

    // Pass features to Cargo build
    features.addAll("default")

    // Allow external dynamic libraries if needed
    builds.android {
        dynamicLibraries.addAll("c++_shared")
    }
}

uniffi {
    generateFromLibrary {
        namespace = "convoyrun_mobile_ffi"
        // The Rust target to use for generating bindings
        // For Android, use the host machine's target
    }
}

// Ensure Cargo builds before UniFFI binding generation
tasks.withType<dev.gobley.gradle.rust.tasks.RustBuildTask>().configureEach {
    dependsOn(tasks.matching { it.name.contains("cargo") })
}
