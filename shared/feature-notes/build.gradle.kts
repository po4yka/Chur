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
    alias(libs.plugins.kotlin.serialization)
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
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The isolation above is a claim until something checks it. This is the check:
// the public shell declares no dependency on another module of this build, so
// no later edit can give it a path to the vault without failing `check`.
//
// `SECURITY_TEST_PLAN.md` section 13 records SEC-019 as becoming a build-graph
// assertion once a Gradle build exists. It exists, and this is that assertion
// for the half of SEC-019 a build graph can see.
val churPublicShellIsolation = tasks.register("churPublicShellIsolation") {
    description = "Fails when the public Notes shell gains a dependency on another module."
    group = "verification"
    val self: String = project.path
    val declared: Set<String> = configurations
        .flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).map { it.path }
        }
        .filterNot { it == self }
        .toSortedSet()
    doLast {
        check(declared.isEmpty()) {
            "the public Notes shell must depend on no other module of this build, and it declares: " +
                declared.joinToString(", ")
        }
    }
}

tasks.named("check").configure { dependsOn(churPublicShellIsolation) }
