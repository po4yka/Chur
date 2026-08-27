// The platform half of `docs/interop/MEDIA_PIPELINE.md`.
//
// §1 splits the work: provider interaction, codec probing and decoding, and
// image resizing are the platform's, and identity, encryption, persistence, and
// integrity are Rust's. This module is the first half and touches none of the
// second: it produces bounded facts and bytes and hands them across.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "dev.po4yka.chur.imports"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core-ffi"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
