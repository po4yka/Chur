// Ciphertext-only Phase 3 transport, plus the engine that drives it: the
// coordinator's bootstrap, pull, and bounded backoff. Canonical protocol
// parsing remains Rust's, so every record this module moves is opaque bytes.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "dev.po4yka.chur.sync"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            implementation(project(":shared:core-ffi"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
