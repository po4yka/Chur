# Feature review corrections — 2026-10-17

> **Status:** Accepted correction record; not release approval

Scope: the five P2 findings from the review of feature code at `27a0a36d76d1cc0596621e599784721876d610a9`. Tests use synthetic files and local vaults. These corrections do not change the catalog schema, native ABI, or archive format.

## Corrections and regression coverage

| Finding | Correction | Regression evidence |
| --- | --- | --- |
| External archives reject long valid MIME types, including DOCX | Normalize MIME hints to the existing 63-byte catalog bound before encryption. Use `application/octet-stream` when a hint is too long or invalid. Preserve the name and original bytes. | `external_archive::tests` checks 63/64-byte hints, case normalization, invalid hints, and a real archive/restore with the DOCX MIME type. It checks restored bytes and unchanged sources. |
| TXT export rejects private notes in Trash | Permit export of the current revision of an unexpired note in Trash. Keep revision checks and purge expired notes before reading. | `private_notes::tests` exports exact UTF-8 text and rejects stale, expired, and permanently deleted notes without output. Both native defects failed their tests before the corrections. |
| Unconfirmed decoder shutdown hides a committed original and continues the import batch | Return the committed object ID with a distinct preview warning. Stop before opening the next source, place committed objects in the chosen album, and show a restart instruction without decoder diagnostics. | `MediaImporterHostTest` uses real native storage to check exact bytes, source closure, retained IDs, album membership, restart persistence, single-item copy, and skipped remaining sources. |
| iOS folder export cannot cancel while waiting for provider coordination | Run file and directory coordination on retained workers. End the caller's wait when Cancel or lock closes the session. Discard unclaimed results. Keep security-scoped access and delivered descriptors until their owners finish. | `DirectoryExportTests` holds a real Foundation file presenter for both creation paths. The caller returns before the presenter resumes. Existing tests retain scope through an active writer. `AlbumExportHostTest` checks the controller Cancel path and cancelled counts before file or directory creation. |
| iOS video frame extraction leaks the Copy-rule image reference | Release each `copyCGImageAtTime` reference in `finally` after `UIImage` retains it. | `IosVideoFrameTest` repeats real H.264 probing and both JPEG derivatives, with cancelled attempts between successful decodes. It checks image validity after release; it does not measure heap growth or reference counts. |

## Observed local checks

| Check | Result |
| --- | --- |
| `cargo test --locked -p chur-ffi -- --test-threads=2` | 92 tests passed; no failures, ignored tests, or filtered tests. |
| `cargo clippy --locked -p chur-ffi --all-targets -- -D warnings` | Passed. |
| `cargo fmt --all -- --check` | Passed. |
| `:shared:app:testAndroidHostTest`, `:shared:feature-import:testAndroidHostTest`, `:shared:core-vault:testAndroidHostTest` | 346 tests passed; no failures or skipped tests. The app module ran 277 tests. |
| `:shared:feature-import:iosSimulatorArm64Test`, `:shared:app:iosSimulatorArm64Test` | 233 tests passed on an owned, booted simulator; no failures or skipped tests. The import module ran 32 tests. |
| `swift test --package-path apps/iosApp --scratch-path /tmp/chur-p2-swift-tests` | 52 tests passed. The provider cancellation tests returned before the held presenter resumed. |
| `python3 scripts/check-backup-rules.py` | Both backup-rule files passed. |
| Android `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest` | Passed for arm64-v8a and x86_64 native libraries. |
| Android instrumentation on API 37 with 16 KiB pages | All 22 tests passed in `DocumentsAndroidTest`, `DocumentExportDirectoryTest`, `PrivateNotesAndroidTest`, and `MediaDerivativeIsolationTest`. Decoder tests use a real isolated process and cover cancellation before the PID handshake, user/coroutine cancellation, and the 30-second deadline. |
| Xcode Debug build for iOS Simulator | The complete `Chur` app and `ChurShare` extension passed, with local ad hoc signatures. Framework export verification passed. This checks the Swift implementation of the new cancellation callback as well as the shared framework. |

The first host compile found a nullable-list type inference error in a new assertion; the assertion now has an explicit type. The first video test used a valid H.264 file whose 16-by-8 frame Apple AVFoundation could not decode. A 64-by-32 Baseline H.264 fixture now passes the same strict probe and derivative checks, plus decoded JPEG size checks. Production behavior was not weakened to pass either test.

## Acceptance boundaries

Provider cancellation cannot force a blocked operating-system write to return. Completed plaintext exports can remain at the selected destination. Physical-device provider coverage, leak profiling, distribution signing, and release acceptance remain separate checks.

The existing backup catalog portability gap described in the backup format remains outside these corrections. This record does not change the documented archive contract or claim cross-device acceptance.
