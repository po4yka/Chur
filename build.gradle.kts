// The root build declares plugins without applying them, so each module opts in
// and a plugin never reaches a module that does not need it.
//
// It also owns the native library the JVM host tests load. Any module whose
// tests reach the vault needs `libchur_jni` built and on `java.library.path`,
// and a module that had to remember to wire that itself would eventually fail
// with `UnsatisfiedLinkError`, which says nothing about why.

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val rustDirectory = layout.projectDirectory.dir("rust")
val cargoTargetDirectory = rustDirectory.dir("target")

/** The host triple, which the JVM host tests load a library for. */
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

val hostLibraryDirectory = cargoTargetDirectory.dir("$hostTarget/debug")

/**
 * Builds `libchur_jni` for the host, ADR-0040.
 *
 * The host tests are what make the Kotlin adapter testable at all: a decoder
 * there that disagrees with the encoder in Rust is a defect neither side's own
 * tests find, and only a test that runs both catches it. It has already caught
 * two.
 */
val cargoBuildHostJni = tasks.register<Exec>("cargoBuildHostJni") {
    group = "build"
    description = "Builds libchur_jni for the host, for the JVM host tests"
    workingDir = rustDirectory.asFile
    commandLine("cargo", "build", "-p", "chur-jni", "--target", hostTarget)
    environment("CARGO_TARGET_DIR", cargoTargetDirectory.asFile.absolutePath)
    environment("IPHONEOS_DEPLOYMENT_TARGET", libs.versions.iosDeploymentTarget.get())
    // The `cc` crate treats an sccache RUSTC_WRAPPER as a C compiler wrapper,
    // and openssl-sys probes its headers with `cc -E`, which sccache refuses.
    // DEVELOPMENT.md records this; setting CC keeps the wrapper off the probe.
    if (System.getenv("CC") == null) {
        environment("CC", "cc")
    }
    inputs.dir(rustDirectory.dir("crates"))
    inputs.file(rustDirectory.file("Cargo.toml"))
    outputs.dir(hostLibraryDirectory)
}

subprojects {
    tasks.withType<Test>().configureEach {
        dependsOn(cargoBuildHostJni)
        systemProperty("java.library.path", hostLibraryDirectory.asFile.absolutePath)
    }
}
