# Encryption security corrections — 2026-10-22

> Status: the three P2 corrections are implemented. The iOS app native suite has two unresolved video time-limit failures. This record is not release approval.

Scope: three P2 findings from the Android and iOS encryption audit.

Base: `4b2aafb6cae81aea9d860f7263997a578d5f27fe`. Three sub-agents implemented the Android provider boundary, secret cleanup, and iOS keyboard policy. Validation used a clean projection of these changes onto the base. Existing unrelated work in the main checkout was excluded from that projection and from the commit.

## Corrections

| Finding | Change | Evidence |
| --- | --- | --- |
| SEC-A1: a blocked Android provider can keep a native session and its keys alive during lock | Native I/O uses app-owned nonblocking pipes, polls for cancellation at 50 ms intervals, and fails after 30 seconds without progress. Android rejects direct FUSE descriptors before metadata access. Session teardown cancels all owned operations before it joins any worker. Bounded keyless workers own provider calls. Archive publication also has a no-progress deadline before its final delivery wait. | Rust cancellation and staging tests; real Android FUSE read/write, pipe, JNI, lock, slot-limit, archive, cancellation and timeout tests. |
| SEC-A2: HKDF/HMAC intermediates and keyed BLAKE3 state can remain after drop | Enable SHA-2 and BLAKE3 cleanup. Add narrow local patches to the existing digest 0.11.3, HMAC 0.13.0 and HKDF 0.13.0 packages. Clear owned PRKs, expansion blocks, padded keys, inner digests and keyed state. Preserve primitive outputs. | Debug and optimized crypto tests, retained upstream RFC/Wycheproof tests, frozen vectors, both dependency-policy checks and C ABI harness. |
| SEC-A3: iOS third-party keyboards can receive private text | The application delegate rejects the keyboard extension point. Other extension points keep their previous behavior. | Swift package tests and full simulator app/extension build. A physical-device third-party-keyboard acceptance test was not run. |

The provider broker bounds input workers at 32, output workers at 4, and foreign open workers at 4 per open path. Each transfer buffer is 64 KiB plus a kernel pipe. Cancellation does not join a foreign provider call. A stalled transfer worker can retain its bounded buffer and descriptor, but no vault key or archive credential. A blocked provider can consume its slot until the provider returns or the app process exits. Failed-setup deletion uses the existing four-slot open pool without a queue. If all open/delete workers are blocked, deletion is best effort and an empty unpublished URI can remain. Startup still attempts to remove abandoned app-owned MediaStore rows.

Seekable archive output uses an app-private ciphertext spool. Archive input stages untrusted package bytes only after the eight-byte `CHURBAK1` format marker is present. The marker is not authentication and does not prove that arbitrary input is encrypted. Native restore still validates the full package before it commits content. Staging never decrypts the package. Full backups have no artificial size cap; storage errors clean up the local spool.

Archive credentials register with controller lock cleanup before staging or a queued native call. Lock clears these credentials synchronously and cancels staging. A successful handoff registers the next owner before the previous owner releases its hold. Regression tests check clearing before a late provider callback and cancellation before a job starts.

Pipe setup uses `Os.open` with `O_NONBLOCK`, followed by descriptor duplication. This preserves the minimum API 29 surface; `Os.fcntlInt` requires API 30. See the official [Android Os reference](https://developer.android.com/reference/android/system/Os). API 29 runtime acceptance remains separate from lint and API-surface validation.

## Observed validation

The Rust checks ran from `rust/`, with an isolated Cargo target directory.

| Check | Result |
| --- | --- |
| `cargo test --workspace --locked` | 959 tests and doctests passed; no failures or ignored tests. |
| `cargo test --locked -p chur-crypto -p chur-format -p hmac -p hkdf --all-features` and the same command with `--release` | Each run passed 244 tests and 9 doctests. |
| `cargo fmt --all -- --check` and `cargo clippy --workspace --all-targets --locked -- -D warnings` | Passed. |
| `cargo deny check`, using repository-pinned cargo-deny 0.20.2, for root and fuzz workspaces | Advisories, bans, licenses and sources passed. |
| `cargo run --locked -p chur-cli -- vectors verify --dir ../test-vectors/v1` | 123 vectors and 7 fixtures passed. |
| `cargo run --locked -p chur-cli -- vectors digest --dir ../test-vectors/v1` | Nine-file digest: `2bc6c43744a4e82e449c14b04c9b516dacb7861bda560dc262a4639b8b725039`. |
| Release `chur-ffi` static library linked with `crates/chur-ffi/tests/handshake.c` | All 35 C ABI checks passed. |
| `swift test --package-path apps/iosApp` | 79 tests passed. This package does not compile the app delegate; the complete Xcode build covers that source. |
| Full JVM and Android host tests | 23 JVM tests and 670 Android host tests passed; no failures or skipped tests. |
| Public-shell isolation and Android app/import lint | Passed. Final Android app lint was repeated after the cleanup correction. |
| Android `assembleDebug` and `assembleDebugAndroidTest` | Passed after the final source changes. |
| Android instrumentation, API 37, 16 KiB pages | 40 tests passed in seven classes. The timeout test waits for the actual 30-second production deadline while the provider remains blocked. Cancellation and failed-setup cleanup tests retain the original assertions. |
| Full Xcode Debug simulator build | Passed for the complete app and share extension with Xcode 27.1 and SDK 27.1, signing disabled. |
| iOS app native suite on an owned iOS 26.5 Simulator | 452 tests executed: 450 passed, 2 failed, 0 skipped. The full app gate is not green. |
| iOS import native suite on the same simulator | 49 tests passed; no failures or skipped tests. This full module ran separately after the app task failed. |

The final host/lint command was `./gradlew jvmTest testAndroidHostTest :shared:feature-notes:churPublicShellIsolation :apps:androidApp:lintDebug :shared:feature-import:lint --no-watch-fs --no-parallel --max-workers=1`. The Xcode command built the complete `Chur` scheme in Debug for a generic iOS Simulator destination with `CODE_SIGNING_ALLOWED=NO`.

An earlier host run timed out while the unchanged device-slot test created its first vault. Its ten-second deadline and assertions were retained. The unchanged test then passed in 1.657 seconds in the serial run. The final result is recorded above. Earlier validation also found an API-30-only call and an outdated archive-capacity assertion; both were corrected without suppressing lint or weakening the behavioral assertions.

The release Rust toolchain reported that `rust-objcopy` could not load `libLLVM.dylib` while stripping debug data. The build continued, and the release tests and linked C harness executed successfully. This warning is not evidence of successful symbol stripping.

## Unresolved iOS runtime results

`VideoCompatibilityIosTest.vfr_copy_keeps_duration_and_distant_seek_timestamps` failed after 305.895 seconds with `RESOURCE_LIMIT_EXCEEDED` at the existing 300-second compatible-video limit. `VideoTimelineIosTest.real_vfr_timeline_has_twelve_slots_and_encrypted_bytes_without_changing_original` failed after 31.108 seconds at the existing 30-second timeline limit. Neither limit nor test assertion was changed.

A short process sample showed the compatibility test reading and decrypting video through the native object reader. It did not show a lock wait in the provider broker. The host was under heavy load, but load is only a hypothesis. The video source files are unchanged; the crypto path is changed. No same-environment baseline comparison was run, so these results do not rule out a regression. Compare the full app native suite on the base and this commit under comparable host and simulator conditions before closing this gate.

The first owned iOS 27 Simulator failed to start `launchd_sim` with `could not bind to session`. A separate owned iOS 26.5 Simulator booted and ran the tests. No other simulator or system service was stopped.

Set `SIMULATOR_UUID` to an owned, booted simulator, then re-run the native gate:

```sh
CHUR_IOS_TEST_DEVICE="$SIMULATOR_UUID" ./gradlew --no-daemon --no-parallel --max-workers=1 :shared:app:iosSimulatorArm64Test :shared:feature-import:iosSimulatorArm64Test
```

## Independent review

The final structured review returned exit status 0 and no accepted or actionable findings in either review chunk. Its secret scan was clean. Accepted findings about staging credential ownership, publication deadlines and failed-setup destination cleanup were corrected and covered by regression tests before this result.

The review helper rejected a raw binary diff. A separate review-only clone therefore put 17 unchanged upstream binary test fixtures in its baseline. Every fixture was checked byte-for-byte against the checksum-verified upstream crate archives. All changed source, textual tests, manifests, lockfiles, licenses and provenance remained in the reviewed diff. The synthetic baseline was not used for builds or publication.

## Limits

- No physical Android or iOS device acceptance, Android API 29 runtime run, distribution signing, deployment or remote CI success is claimed here.
- Application encryption cannot protect plaintext in an unlocked process from a fully compromised OS. These changes do not add such a guarantee.
- Best-effort zeroization covers the named owned buffers and states. It cannot prove removal of CPU registers, compiler copies, platform decoder memory or all transient compression values.
- A provider can retain plaintext that the user explicitly exported. Lock cannot revoke that external copy. A permanently blocked provider can retain a bounded transfer buffer and prevent cleanup of an empty external document.
- Existing device-passcode policy, public notes and camera behavior, screen capture/mirroring policy and platform parser boundaries remain outside these three corrections.
