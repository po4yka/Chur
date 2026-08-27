// The Gradle build of the Kotlin Multiplatform and Compose Multiplatform side.
//
// `docs/ARCHITECTURE.md` puts every private byte behind Rust; these modules are
// the public shell, the platform key slots, and the error and vector contracts
// that cross the FFI boundary. Nothing here defines a canonical encoder:
// `docs/format/CANONICAL_ENCODING_V1.md` section 13 forbids it.

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // A module may not declare a repository of its own. One resolution surface
    // is what DEPENDENCY_POLICY.md "Provenance and releases" needs to be
    // auditable.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "chur"

include(":shared:app")
include(":shared:core-ffi")
include(":shared:core-model")
include(":shared:core-platform-keys")
include(":shared:core-vault")
include(":shared:feature-import")
include(":shared:feature-notes")
