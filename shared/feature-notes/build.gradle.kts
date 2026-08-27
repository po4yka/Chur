// The public Notes shell.
//
// `docs/product/DISCREET_MODE.md` requires a shell that is a real application
// on its own merits, and `docs/security/PLAINTEXT_LIFECYCLE.md` §1 forbids
// private data in a public store. This module therefore depends on nothing
// private: it does not see `:shared:core-ffi`, so it cannot reach the vault
// even by accident.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "dev.po4yka.chur.notes"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
