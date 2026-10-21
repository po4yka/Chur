import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

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
        androidResources { enable = true }
        withHostTest {}
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            val triple = if (target.name == "iosArm64") "aarch64-apple-ios" else "aarch64-apple-ios-sim"
            val archive = rootProject.layout.projectDirectory.file("rust/target/$triple/debug/libchur_ffi.a")
            linkerOpts(archive.asFile.absolutePath)
            linkTaskProvider.configure {
                inputs.file(archive)
                dependsOn(":shared:core-ffi:cargoBuildFfi${target.name.replaceFirstChar { it.uppercase() }}")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core-ffi"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.android.tesseract)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Verify the new native parser binary before Android compilation or packaging.
// This scope does not add trust exceptions for the existing dependency graph.
val ocrBinary = configurations.create("ocrBinary") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(ocrBinary.name, "cz.adaptech.tesseract4android:tesseract4android:4.9.0@aar") }
val verifiedOcrFiles = ocrBinary.incoming.artifactView {}.files
val ocrModels = layout.projectDirectory.dir("src/androidMain/assets/ocr")
val verifyOfflineOcrInputs = tasks.register("verifyOfflineOcrInputs") {
    val binaryFiles = verifiedOcrFiles
    val englishModel = ocrModels.file("eng.traineddata").asFile
    val russianModel = ocrModels.file("rus.traineddata").asFile
    group = "verification"
    inputs.files(binaryFiles)
    inputs.files(englishModel, russianModel)
    doLast {
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { source ->
                val block = ByteArray(64 * 1024)
                while (true) { val count = source.read(block); if (count < 0) break; hash.update(block, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        check(binaryFiles.files.single().let(::sha256) == "bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a") {
            "The pinned Tesseract4Android binary changed"
        }
        check(sha256(englishModel) == "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
        check(sha256(russianModel) == "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225")
    }
}
tasks.configureEach {
    if ((name.startsWith("compile") && name.contains("Android")) || name.startsWith("mergeAndroid") || name == "check") {
        dependsOn(verifyOfflineOcrInputs)
    }
}

// ImageIO and the type registry need the services of a fully booted simulator.
// Use the same explicit test device as the app's native tests.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
    val selected = providers.environmentVariable("CHUR_IOS_TEST_DEVICE")
    if (selected.isPresent) device.set(selected)
    val fixtures = rootProject.layout.projectDirectory.dir("apps/androidApp/src/androidTest/assets/media-formats")
    inputs.file(fixtures.file("camera.dng")).withPathSensitivity(PathSensitivity.RELATIVE)
    environment("CHUR_MEDIA_TEST_FIXTURES", fixtures.asFile.absolutePath)
    environment("SIMCTL_CHILD_CHUR_MEDIA_TEST_FIXTURES", fixtures.asFile.absolutePath)
    val hevcFixtures = rootProject.layout.projectDirectory.dir("tests/media/hevc-main10")
    inputs.dir(hevcFixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    environment("CHUR_HEVC_MAIN10_FIXTURES", hevcFixtures.asFile.absolutePath)
    environment("SIMCTL_CHILD_CHUR_HEVC_MAIN10_FIXTURES", hevcFixtures.asFile.absolutePath)
    val jpegXlFixtures = rootProject.layout.projectDirectory.dir("tests/media/jpeg-xl")
    inputs.dir(jpegXlFixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    environment("CHUR_JPEG_XL_FIXTURES", jpegXlFixtures.asFile.absolutePath)
    environment("SIMCTL_CHILD_CHUR_JPEG_XL_FIXTURES", jpegXlFixtures.asFile.absolutePath)
}
