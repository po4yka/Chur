// The platform key-slot module.
//
// `docs/security/KEY_SLOTS.md` §4 and §5 give Android and Apple one job each:
// gate an operation that releases or unwraps material Rust then uses. Neither
// platform holds the root secret in the clear and neither defines a record
// layout; `docs/format/KEY_SLOT_BODIES_V1.md` §5 and §6 own the bytes.
//
// This module is the Phase 0 prototype ROADMAP asks for. It exercises the
// platform APIs and the policy split, and it stops at the boundary where Rust
// takes over.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    // An `expect class` is the honest shape here: the two platforms hold
    // different material and their actuals add different members. The feature
    // is in Beta and the flag acknowledges that rather than hiding it.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "dev.po4yka.chur.core.platformkeys"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
