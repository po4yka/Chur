// The Compose Multiplatform shell.
//
// It holds the design system of `DESIGN.md` §6 to §9, the public Notes shell,
// and the four vault destinations of §10.1. Every screen is a pure function of
// a state value: nothing here reads a repository, so a screenshot test renders
// any state without a vault and a lock transition cannot leave a half-rendered
// screen behind, which §10.3 requires.
//
// It holds no private data of its own. `docs/ARCHITECTURE.md` keeps every
// private byte behind Rust, and what reaches a composable is a projection the
// boundary produced.

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

    // The iOS side consumes a framework, which `apps/iosApp` presents. It is
    // static and links `libchur_ffi.a` through the cinterop bindings of
    // `:shared:core-ffi`, so the link is where the framework and the library
    // are proved to agree on every symbol.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ChurApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            api(project(":shared:core-vault"))
            api(project(":shared:feature-notes"))
            implementation(libs.kotlinx.coroutines.core)
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
