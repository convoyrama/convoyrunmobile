// Top-level build file for ConvoyRun Mobile
// See: https://developer.android.com/build

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    kotlin("android") version "2.1.10" apply false
    kotlin("multiplatform") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    id("dev.gobley.cargo") version "0.3.7" apply false
    id("dev.gobley.uniffi") version "0.3.7" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
