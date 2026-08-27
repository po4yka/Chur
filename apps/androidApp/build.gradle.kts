// The Android host application.
//
// `docs/ARCHITECTURE.md` §9 keeps feature modules free of platform key
// implementations and FFI symbols: features depend on interfaces, and only the
// composition root binds them. This module is that composition root and holds
// nothing else — no screen, no vault logic, and no encoder.
//
// It packages `libchur_jni` for every ABI. The library is built by Cargo
// (ADR-0040), not by CMake, so the packaging is a copy into `jniLibs` rather
// than an `externalNativeBuild`.

plugins {
    // AGP 9 carries Kotlin support itself; the kotlin-android plugin is no
    // longer applied and applying it is an error.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "dev.po4yka.chur.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.po4yka.chur"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // `docs/DEPENDENCY_POLICY.md` "Provenance and releases" wants the
        // native architecture inventory to be exactly what was built, so
        // nothing here is stripped or filtered by the packager.
        jniLibs.keepDebugSymbols += "**/libchur_jni.so"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared:app"))
    implementation(project(":shared:core-vault"))
    implementation(project(":shared:core-platform-keys"))
    implementation(project(":shared:feature-import"))
    implementation(project(":shared:feature-notes"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.core)
}

// ---------------------------------------------------------------------------
// The native libraries
// ---------------------------------------------------------------------------

val rustDirectory = rootProject.layout.projectDirectory.dir("rust")
val cargoTargetDirectory = rustDirectory.dir("target")

/** The Android ABIs and the Rust triple each one is built from. */
val androidAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

/**
 * Builds `libchur_jni` for every Android ABI.
 *
 * It shells out to the script rather than to Cargo directly, because the script
 * is where the NDK discovery, the linker variables, the `PATH` the vendored
 * OpenSSL needs, and the symbol check already live; duplicating them here would
 * mean two places to fix when an NDK layout changes.
 */
val cargoBuildAndroid = tasks.register<Exec>("cargoBuildAndroid") {
    group = "build"
    description = "Builds libchur_jni for every Android ABI"
    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("scripts/build-native-targets.sh", "android")
    environment("CARGO_TARGET_DIR", cargoTargetDirectory.asFile.absolutePath)
    inputs.dir(rustDirectory.dir("crates"))
    inputs.file(rustDirectory.file("Cargo.toml"))
    androidAbis.values.forEach { triple ->
        outputs.file(cargoTargetDirectory.file("$triple/release/libchur_jni.so"))
    }
}

/**
 * Stages the built libraries into the layout the packager expects.
 *
 * AGP 9 refuses a `Provider` in the source-set API, because it cannot tell a
 * generated directory from a static one and would lose the task dependency.
 * The Variant API below is the supported way to say "generated", and it carries
 * the dependency, so a build that changed a Rust export cannot package a stale
 * library.
 */
abstract class StageJniLibraries : DefaultTask() {
    /**
     * The ABI directory name of each built library, keyed by its path.
     *
     * It is one map rather than a file collection beside a list of names. A
     * `ConfigurableFileCollection` iterates in unspecified order, so pairing it
     * with a parallel list would package a library under the wrong ABI whenever
     * that order changed, and an APK whose arm64 directory holds an x86 library
     * fails at `System.loadLibrary` on a device and nowhere earlier.
     */
    @get:Input
    abstract val librariesByAbi: MapProperty<String, String>

    /** The same files, so Gradle can tell when the build is up to date. */
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    /** The staged `jniLibs` tree. */
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun stage() {
        val destination = output.get().asFile
        destination.deleteRecursively()
        librariesByAbi.get().forEach { (abi, path) ->
            val library = File(path)
            if (!library.exists()) {
                throw GradleException("no JNI adapter for $abi at $path")
            }
            val directory = destination.resolve(abi).apply { mkdirs() }
            library.copyTo(directory.resolve("libchur_jni.so"), overwrite = true)
        }
    }
}

val stageJniLibraries = tasks.register<StageJniLibraries>("stageJniLibraries") {
    dependsOn(cargoBuildAndroid)
    val byAbi = androidAbis.mapValues { (_, triple) ->
        cargoTargetDirectory.file("$triple/release/libchur_jni.so").asFile.absolutePath
    }
    librariesByAbi.set(byAbi)
    libraries.setFrom(byAbi.values)
    output.set(layout.buildDirectory.dir("chur-jni"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            stageJniLibraries,
            StageJniLibraries::output,
        )
    }
}
