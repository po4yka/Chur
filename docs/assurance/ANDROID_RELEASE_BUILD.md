# Android release candidate build

The `android application` job in `.github/workflows/rust.yml` builds a release APK and AAB on every pull request with a disposable signing key. A push to `main` uses the configured release key and retains the signed packages, native symbols, R8 mapping, and checksum report as one `android-release-candidate-<commit>` Actions artifact. It does not publish to Google Play.

The release certificate SHA-256 digest is pinned in that job. The private key and password are GitHub Actions secrets `CHUR_RELEASE_KEYSTORE_BASE64` and `CHUR_RELEASE_PASSWORD`; they are not repository files. The job does not save a build cache after those secrets enter its environment.

`scripts/check-android-release.py` checks the final APK and AAB signatures against that certificate, APK ZIP alignment, matching 64-bit native inventories, 16 KiB ELF LOAD alignment, absence of debug sections in shipped libraries, matching GNU build IDs among the packaged Rust library, its unstripped build input, and its native symbol sidecar, and preservation of the JNI class by R8. It records SHA-256 digests for the APK, AAB, symbols, and mapping. The Gradle task tracks the Rust build script, lockfile, and toolchain file as inputs, and Cargo runs with `--locked` under the pinned NDK.

To check a local candidate, set `CHUR_RELEASE_KEYSTORE` to a PKCS#12 file with alias `chur-release` and set `CHUR_RELEASE_PASSWORD`. Then run:

```sh
./gradlew :apps:androidApp:assembleRelease :apps:androidApp:bundleRelease
python3 scripts/check-android-release.py \
  apps/androidApp/build/outputs/apk/release/androidApp-release.apk \
  apps/androidApp/build/outputs/bundle/release/androidApp-release.aab \
  apps/androidApp/build/outputs/native-debug-symbols/release/native-debug-symbols.zip \
  <signing-certificate-sha256>
```

A local disposable key proves packaging and runtime behavior, not the production identity. For a 16 KiB runtime smoke test, use a 16 KiB Android image, confirm `adb shell getconf PAGE_SIZE` returns `16384`, disable page-size backcompat and read the properties back, install the exact signed APK, launch `dev.po4yka.chur.android.MainActivity`, and confirm the public shell is visible with no JNI or ABI gate error. The CI archive is the commit-bound build. An AAB-derived APK set, the physical device matrix, SBOM, and independent security review still need evidence before the production gate in `RELEASE_GATES.md` can close.
