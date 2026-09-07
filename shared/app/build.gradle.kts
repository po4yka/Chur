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

/**
 * Each name `apps/iosApp/README.md` gives the Xcode project that an `export()`
 * decides. `PrivacyCover`, `ExportSink` and `DeviceUnlock`, which the
 * `ChurController` initializer also takes, are declared by `:shared:app`
 * itself and reach the header whatever this list holds.
 *
 * The Objective-C header gives each Kotlin declaration a `swift_name`, and
 * that name is what Swift writes. A declaration of a module the framework does
 * not export keeps its module prefix, so `SyncCoordinator` becomes
 * `Core_syncSyncCoordinator` and the line the README gives does not compile.
 * A declaration no exported signature refers to is absent from the header.
 */
val iosHostSymbols =
    listOf(
        // Step 1: the controller, its two stores, and its device-unlock binding.
        "ChurController",
        "churStorageRoot",
        "churNoteStore",
        "NoteStore",
        "LockPolicy",
        "NoDeviceUnlock",
        // Step 2: the entry point and the gate verdict it takes.
        "ChurViewController",
        "GateResult",
        // Step 3: the privacy cover.
        "IosPrivacyCover",
        // Step 5: the media codec.
        "IosMediaCodec",
        // Step 6: the background task and the sync engine it drives.
        "IosSyncBackground",
        "SyncCoordinator",
        "churSyncStateStore",
        "FileSyncStateStore",
    )

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
    //
    // The link does not prove the other half of what the host consumes. A
    // Kotlin/Native framework exports its own module and the modules `export`
    // names, and nothing else: a type of any other dependency reaches the
    // header with its module prefix, or, when no exported signature refers to
    // it, does not reach the header at all. `apps/iosApp/README.md` has the
    // Xcode project write each name in `iosHostSymbols` above, and
    // `docs/IOS.md` §30.1 gives "API compatibility verified in CI" as the
    // direction for this framework.
    //
    // Kotlin accepts `export` only for a module the framework compilation
    // already has as an `api` dependency, which is why `:shared:feature-import`
    // is `api` below. `export` is also not transitive, so each module appears
    // here on its own line.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ChurApp"
            isStatic = true

            // `LockPolicy`, which the `ChurController` initializer takes. The
            // header carries no default argument, so the host writes one.
            export(project(":shared:core-vault"))
            // `NoteStore`, which `churNoteStore()` returns.
            export(project(":shared:feature-notes"))
            // `SyncCoordinator` and `FileSyncStateStore`, which step 6 pairs.
            export(project(":shared:core-sync"))
            // `IosMediaCodec`, which step 5 gives the picked file URL. No
            // exported signature refers to it, so without this line it is
            // absent from the header rather than prefixed in it.
            export(project(":shared:feature-import"))

            // The link task finishes with the check, and the workflow runs no
            // step of its own. The `kotlin-native` job already runs this link,
            // so the job checks the header by running what it ran before, and
            // the command `apps/iosApp/README.md` gives a developer checks it
            // as well. The check reads the header path from the binary, so a
            // new target or a new `baseName` cannot leave it reading a file
            // that another build wrote.
            //
            // The three values below are read here, at configuration time. A
            // task action that reads the script instead holds a reference to
            // the script, which the configuration cache refuses to store.
            val binary = linkTaskProvider
            val headerName = "$baseName.h"
            val required = iosHostSymbols
            val verifyExports =
                tasks.register("verify${linkTaskName.removePrefix("link")}Exports") {
                    description =
                        "Proves the framework header declares every name apps/iosApp/README.md gives the Xcode project."
                    // The check depends on the link as well as finalizing it.
                    // A finalizer runs after a failure too, and the check would
                    // then read the header of the previous build and report the
                    // wrong fault; a finalizer whose own dependency failed is
                    // skipped instead.
                    dependsOn(binary)
                    // The task declares no output, so Gradle runs it whenever
                    // it is in the graph: an up-to-date or a cached link cannot
                    // make a different task up to date. This states that, so an
                    // output added later does not turn the gate into one that
                    // passes because it did not run.
                    outputs.upToDateWhen { false }
                    val header = binary.flatMap { it.outputFile }
                    doLast {
                        val generated = header.get().resolve("Headers/$headerName")
                        val text = generated.readText()
                        // The whole attribute, not its start. A `swift_name`
                        // that only begins with a required name belongs to
                        // another declaration: `GateResultReason` begins with
                        // `GateResult`, so a match on the start reports a name
                        // the header does not declare as present. A class reads
                        // `swift_name("Name")` and a top-level function reads
                        // `swift_name("name(`.
                        val missing =
                            required.filterNot {
                                text.contains("swift_name(\"$it\")") ||
                                    text.contains("swift_name(\"$it(")
                            }
                        if (missing.isNotEmpty()) {
                            throw GradleException(
                                "$generated declares no ${missing.joinToString()}. " +
                                    "apps/iosApp/README.md has the Xcode project call each of these by name, " +
                                    "so the module of each one needs an export() in shared/app/build.gradle.kts.",
                            )
                        }
                    }
                }
            linkTaskProvider.configure { finalizedBy(verifyExports) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            api(project(":shared:core-vault"))
            api(project(":shared:feature-notes"))
            // The sync engine and its status are part of this shell's public
            // surface: the settings screen shows the state and the hosts bind
            // the background schedules that drive it.
            api(project(":shared:core-sync"))
            // The waveform record of `MEDIA_PIPELINE.md` §6.1 is written by the
            // import pipeline and drawn here, so the reader that parses it and
            // the accumulator that produces it stay in one module.
            // `api` rather than `implementation`: the framework exports this
            // module, and Kotlin exports only an `api` dependency.
            api(project(":shared:feature-import"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            // The player of `MEDIA_PIPELINE.md` §9. It receives plaintext ranges
            // from a vault-backed `DataSource` and never sees a container, a
            // key, or a path, which is the split §1 fixes.
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // `ChurController` launches on `Dispatchers.Main`, so a test that
            // drives one has to supply that dispatcher.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
