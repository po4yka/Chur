// The FFI boundary as Kotlin sees it.
//
// `docs/interop/FFI_CONTRACT.md` §1 has the KMP side reach one stable C ABI
// through an `expect`/`actual` adapter. Android reaches it through the JNI
// adapter of ADR-0040 and iOS through cinterop, so the two actuals differ in
// mechanism and in nothing else.
//
// The native libraries are built by Cargo, not by this build. A Gradle task
// invokes it, because a developer who edits a Rust export and runs a Kotlin
// test must not get a stale library: a library that no longer matches the
// header fails at a symbol lookup, which is the least informative failure
// available.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val rustDirectory = rootProject.layout.projectDirectory.dir("rust")
val cargoTargetDirectory = rustDirectory.dir("target")
val iosDeploymentTarget = libs.versions.iosDeploymentTarget.get()

/** The host triple, which the JVM host tests load. */
val hostTarget: String = run {
    val architecture = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "aarch64"
        else -> "x86_64"
    }
    val system = System.getProperty("os.name")
    when {
        system.startsWith("Mac") -> "$architecture-apple-darwin"
        system.startsWith("Linux") -> "$architecture-unknown-linux-gnu"
        else -> error("Chur builds its native libraries on macOS and Linux only")
    }
}

/**
 * Registers a Cargo build.
 *
 * `IPHONEOS_DEPLOYMENT_TARGET` is set for every target, not only the Apple
 * ones, because it is inert elsewhere and forgetting it on one is the failure
 * it prevents: the vendored C of ADR-0038 is compiled against the installed
 * SDK, whose objects reference symbols Rust's default iOS 10 link target does
 * not provide, and the link then fails on `___chkstk_darwin`.
 */
fun registerCargo(name: String, crate: String, triple: String, artifact: String) =
    tasks.register<Exec>(name) {
        group = "build"
        description = "Builds $artifact for $triple"
        workingDir = rustDirectory.asFile
        commandLine("cargo", "build", "-p", crate, "--target", triple)
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
        outputs.file(cargoTargetDirectory.file("$triple/debug/$artifact"))
    }

val cargoBuildHostJni = registerCargo(
    name = "cargoBuildHostJni",
    crate = "chur-jni",
    triple = hostTarget,
    artifact = if (hostTarget.contains("apple")) "libchur_jni.dylib" else "libchur_jni.so",
)

val appleTriples = mapOf(
    "iosArm64" to "aarch64-apple-ios",
    "iosSimulatorArm64" to "aarch64-apple-ios-sim",
)

val cargoBuildApple = appleTriples.mapValues { (name, triple) ->
    registerCargo(
        name = "cargoBuildFfi${name.replaceFirstChar { it.uppercase() }}",
        crate = "chur-ffi",
        triple = triple,
        artifact = "libchur_ffi.a",
    )
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "dev.po4yka.chur.ffi"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }

    // iOS reaches the C ABI through cinterop and loads no adapter.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val triple = appleTriples.getValue(target.name)
        target.compilations.getByName("main").cinterops.create("chur") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/chur.def"))
            includeDirs(rootProject.file("rust/crates/chur-ffi/include"))
            extraOpts(
                "-libraryPath",
                cargoTargetDirectory.dir("$triple/debug").asFile.absolutePath,
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

appleTriples.keys.forEach { name ->
    tasks.named("cinteropChur${name.replaceFirstChar { it.uppercase() }}") {
        dependsOn(cargoBuildApple.getValue(name))
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHostJni)
    systemProperty(
        "java.library.path",
        cargoTargetDirectory.dir("$hostTarget/debug").asFile.absolutePath,
    )
}
