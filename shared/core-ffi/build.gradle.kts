// The FFI boundary as Kotlin sees it.
//
// `docs/interop/FFI_CONTRACT.md` §1 has the KMP side reach one stable C ABI
// through an `expect`/`actual` adapter. Android reaches it through the JNI
// adapter of ADR-0040 and iOS through cinterop, so the two actuals differ in
// mechanism and in nothing else.
//
// The Apple static libraries are built by Cargo, not by this build, because a
// developer who edits a Rust export and runs a Kotlin test must not get a stale
// library: one that no longer matches the header fails at a symbol lookup,
// which is the least informative failure available. The host library the JVM
// tests load is the root build's, because every module's tests need it.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val rustDirectory = rootProject.layout.projectDirectory.dir("rust")
val cargoTargetDirectory = rustDirectory.dir("target")
val iosDeploymentTarget = libs.versions.iosDeploymentTarget.get()

/**
 * Registers a Cargo build.
 *
 * `IPHONEOS_DEPLOYMENT_TARGET` is set for every target, not only the Apple
 * ones, because it is inert elsewhere and forgetting it on one is the failure
 * it prevents: the vendored C of ADR-0038 is compiled against the installed
 * SDK, whose objects reference symbols Rust's default iOS 10 link target does
 * not provide, and the link then fails on `___chkstk_darwin`.
 */
fun registerCargo(
    name: String,
    crate: String,
    triple: String,
    artifact: String,
    release: Boolean,
) = tasks.register<Exec>(name) {
    val profile = if (release) "release" else "debug"
    group = "build"
    description = "Builds the $profile $artifact for $triple"
    workingDir = rustDirectory.asFile
    val cargoArgs = mutableListOf("build", "-p", crate, "--target", triple)
    if (release) {
        cargoArgs.add(1, "--release")
    }
    commandLine("cargo", *cargoArgs.toTypedArray())
    environment("CARGO_TARGET_DIR", cargoTargetDirectory.asFile.absolutePath)
    environment("IPHONEOS_DEPLOYMENT_TARGET", iosDeploymentTarget)
    // The `cc` crate treats an sccache RUSTC_WRAPPER as a C compiler
    // wrapper, and openssl-sys probes its headers with `cc -E`, which
    // sccache refuses. DEVELOPMENT.md records this.
    if (System.getenv("CC") == null) {
        environment("CC", "cc")
    }
    inputs.dir(rustDirectory.dir("crates"))
    inputs.file(rustDirectory.file("Cargo.toml"))
    outputs.file(cargoTargetDirectory.file("$triple/$profile/$artifact"))
}

val appleTriples =
    mapOf(
        "iosArm64" to "aarch64-apple-ios",
        "iosSimulatorArm64" to "aarch64-apple-ios-sim",
    )

val cargoBuildApple =
    appleTriples.mapValues { (name, triple) ->
        registerCargo(
            name = "cargoBuildFfi${name.replaceFirstChar { it.uppercase() }}",
            crate = "chur-ffi",
            triple = triple,
            artifact = "libchur_ffi.a",
            release = false,
        )
    }

// The release framework must link the release archive rather than the debug
// one the cinterop klib records, so the release archive is built by its own
// task and the consuming link depends on it.
val cargoBuildAppleRelease =
    appleTriples.mapValues { (name, triple) ->
        registerCargo(
            name = "cargoBuildFfi${name.replaceFirstChar { it.uppercase() }}Release",
            crate = "chur-ffi",
            triple = triple,
            artifact = "libchur_ffi.a",
            release = true,
        )
    }

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )

    android {
        namespace = "dev.po4yka.chur.ffi"
        compileSdk =
            libs.versions.androidCompileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.androidMinSdk
                .get()
                .toInt()
        withHostTest {}
    }

    // iOS reaches the C ABI through cinterop and loads no adapter. The
    // cinterop names no library: `chur.def` declares no `staticLibraries`,
    // because a library baked into the klib is the same archive for every
    // binary of the compilation, and the release framework would link the
    // debug one. The consuming build links the archive of its own build type.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("chur") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/chur.def"))
            includeDirs(rootProject.file("rust/crates/chur-ffi/include"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core-model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

appleTriples.keys.forEach { name ->
    tasks.named("cinteropChur${name.replaceFirstChar { it.uppercase() }}") {
        // cinterop tracks the `.def` file and not the headers it names, so an
        // export added to `chur.h` alone leaves the bindings up to date and the
        // new symbol unresolved. Declaring the header is what makes a change to
        // the C ABI regenerate them. No library is built here: the klib names
        // no archive any more, and the links that need one depend on the Cargo
        // task of their own build type.
        inputs
            .file(rootProject.file("rust/crates/chur-ffi/include/chur.h"))
            .withPropertyName("churHeader")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
