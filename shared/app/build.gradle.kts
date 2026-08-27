// The Compose Multiplatform shell.
//
// ROADMAP Phase 0 scopes the CMP workspace; the Notes public shell and every
// private screen are Phase 1. What lives here is the one screen Phase 0 owns:
// the ABI gate of `docs/interop/FFI_CONTRACT.md` §2, which a host evaluates
// before a vault can be opened at all.
//
// The module holds no private data and calls no vault API. It cannot: the
// control plane does not exist yet, and `docs/ARCHITECTURE.md` keeps every
// private byte behind Rust.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "dev.po4yka.chur.app"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    // The iOS side consumes a framework. The Xcode project that embeds it
    // lands with the Phase 1 shell; the framework itself links today, which is
    // what keeps the target honest.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ChurApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
