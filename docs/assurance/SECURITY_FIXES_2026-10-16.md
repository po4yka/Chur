# Security correction evidence — 2026-10-16

> **Status:** Accepted historical evidence record; not release approval

Scope: all 15 findings in the 2026-10-16 audit of Chur, plus defects reproduced while testing the corrections. All media in these tests is synthetic. The application has no active clients. The audit corrections change the current schema and wire formats directly; no migration or new format version is required for them. Existing user-owned schema and ABI changes are preserved.

## Published corrections

| Audit finding | Correction | Commit |
| --- | --- | --- |
| P1: backup can report success for a corrupt source | Verify each source container before publishing the backup. Reject known corrupt objects. Test an actual restore. | `abade26` |
| P1: Android can delete a different source with the same name and size | Accept only a trusted provider identity. Remove the name-and-size fallback. | `e8ab09f` |
| P1: a normal local sync identity prevents backup | Put a separate portable recovery identity in the backup snapshot. Keep the active identity in the live catalog. | `6e59c4f` |
| P1: retained signed history can reopen a deleted object key | Remove content-key envelopes from signed logs. Use exact-object disposable prekeys and grants. Erase private requests and local envelopes before the WAL barrier. Isolate unavailable sources and bad grants. Test old-backup recovery. | `299d7dc` |
| P2: own devices cannot enroll and exchange original media | Add fingerprint approval, native membership discovery and encrypted own-device media transfer. Preserve the user's import and sync corrections. | `43a9e0a`, `d180224` |
| P2: verified corrupt files cannot be removed | Add an explicit destructive cleanup action and allow deletion from the corrupt state. Preserve the healthy neighbor and backup behavior. | `cae2216` |
| P2: share scratch and URI grants survive a return to the app | End the share lease and remove temporary files when the app resumes. | `9b214d3` |
| P2: a container directory sync error is ignored | Require a successful durable directory write after rename. | `b19ea99` |
| P2: one revoked collection blocks unrelated packages | Handle revocation per package. Continue the other packages. | `8e260e1` |
| P2: the Photos permission alert can cancel its own export | Keep the host activity lease during the first permission request. | `3deb9e5` |
| P2: plaintext scratch is not bounded | Enforce file count and byte limits. Bound copies and support cancellation. | `956dcab` |
| P2: iOS playback starts automatically and has no controls | Require explicit Play. Add pause, seek and replay. Disable external playback. | `af45dd3` |
| P2: a failed derivative activation can leave an orphan | Journal derivative activation and recover incomplete writes. | `5c94c38` |
| P2: locked sync can lose records after advancing a cursor | Advance cursors from verified native heads. Retain records until native acceptance. | `58cc43b` |
| P2: decoded previews have no memory budget | Limit decoded bytes and retain only visible and nearby thumbnails. The user approved inclusion of the existing gallery changes in this commit. | `b713b02` |
| Follow-up: newer accepted checkpoint evidence can be replaced by older evidence | Keep the newest authenticated evidence and restore it after restart. | `43fc96b` |
| Follow-up: privacy copy omits user-controlled network transfer | Describe optional encrypted sync and explicit exports. | `f93b6f2` |
| Follow-up: an open media reader retains a deleted key | Serialize reader publication and deletion. Wait for in-flight reads, drop cached plaintext and keys, and reject publication after owner shutdown. | `5fa85e9` |
| Follow-up: creation or descriptor replacement can admit a second session | Lock each replacement before rename and bind unlock to the locked inode and descriptor bytes. Preserve custody when directory sync fails. | `f112d30` |
| Follow-up: revocation can block healthy descendants or leave deferred purge custody | Respect signed cutoffs, keep healthy active chains and project terminal deletion in the resolving transaction. Recover control-operation prefixes before approval. Encode native pending counts for the host. | `d180224` |
| Follow-up: collections share temporary request custody or replace active ciphertext | Bind private requests and pending grants to collection plus object. Reject an active collision before writing a container. | `8f91bf5` |
| Follow-up: host tests can reuse stale results after a native-only edit | Track the loaded JNI bytes, Cargo lockfile and pinned toolchain. Use locked Cargo builds. | `afccdc9` |
| Follow-up: cancelled startup leaves a live decoder, and cleanup errors can become successful imports | Complete the PID handshake even during cancellation. Check the owned kernel task before closing descriptors. Quarantine one unconfirmed owner and reject reuse. Propagate fatal cleanup separately from an unavailable preview. | `02336eb` |
| Test correction: delayed host snapshots give a false retention result after Cancel | Compare native object IDs and original bytes before and after corrupt cleanup and stop-sync confirmation. Keep the existing UI and server checks. | `259920a` |
| Test correction: input-transaction waits break a double-tap regression | Dispatch the test pair through the focused decor view. Keep timed waits off the main thread and retain pan, pinch and page assertions. | `a40b6f9` |

## Additional platform evidence

The current Swift package passed all 30 tests, including the added public-camera storage tests. These tests cover bounded scratch copies, document import and directory export. Three iOS approval-file tests cover exact-limit transfer, byte equality, private file permissions, overflow rejection and cleanup; they are also part of the full app iOS suite.

Native pairing tests use a real backup, restored recovery identity, fresh device approval, encrypted media transfer and a catalog restart. Host tests use the real JNI boundary and HTTP transport test engine. Earlier instrumented runs passed 85 and 91 tests, but they predate the final protocol integration and are not evidence for the final APK.

## Final native and platform integration

The tested combined source includes the user's private notes, selective encrypted archive, hierarchical album navigation and isolated Android media decoder changes. User commits `43a9e0a`, `d77eab41`, `aa454854`, `01579735`, `d9f25c8d` and `3b6c0f78` are preserved, including encrypted audio artwork and the Quick Settings lock tile. Its new sync pagination and local-source failure handling remain in place with the causal dependency warnings and ordered approval publication. The full Rust workspace at published commit `fc5cd6586828bfd3162e1a7b91008d7562291f89` passed 769 tests in 57 targets, with no failed or ignored tests. The full workspace Clippy check passed with warnings denied. Formatting passed. The CLI verified 106 vectors and two fixtures. These checks include the later external-file encryption feature; no test was filtered or ignored. Tests use actual encrypted containers, native key erasure, pending requests, old-backup recovery, own-device pairing, terminal remote deletion, restart, unavailable peers and bad grants.

Run native commands from `rust/` to use the pinned toolchain:

```sh
cargo test --locked --workspace
cargo clippy --locked --workspace --all-targets -- -D warnings
cargo fmt --all -- --check
cargo run --locked -p chur-cli -- vectors verify --dir ../test-vectors/v1
```

The final unfiltered Kotlin integration command passed. Its 17 Android host, JVM and iOS simulator result groups contain 814 passed tests, with no failures, errors or skips. The affected app suites contain 258 host tests and 189 iOS simulator tests. Unchanged tasks can reuse valid up-to-date results; the native library is now an explicit test input. The public-shell isolation task passed. The final Debug iOS simulator app build at `fc5cd658` passed with signing disabled. The native, Kotlin and iOS build checks use a clean detached checkout of that commit. Android backup-rule validation passed for both rule files. The vendored-skill check passed for all 46 locked skills.

```sh
./gradlew --no-daemon --no-build-cache jvmTest testAndroidHostTest iosSimulatorArm64Test :shared:feature-notes:churPublicShellIsolation --max-workers=2
python3 scripts/check-backup-rules.py
```

The [GitHub Actions run for `fc5cd658`](https://github.com/po4yka/Chur/actions/runs/38935921164) completed successfully with all 14 jobs green. This includes Cargo test, Clippy, formatting, dependency policy, vectors, fuzz smoke, the C ABI harness, Kotlin/native tests, Gradle checks, Android application checks, native Apple and Android targets, backup rules and vendored skills. This CI result is separate from the local emulator execution below.

Four native vault tests cover concurrent revocation and active-device descendants. One test starts with three distinct states: a private request, a pending grant envelope and an active content-key envelope. An authenticated generation-two purge remains unresolved until an independent valid restore supplies the missing lifecycle state. Resolution removes all three custody states and writes terminal tombstones. Retained grants and new requests fail. A healthy neighbor exports after restart, and encrypted backup creation succeeds. Separate causal and permanent-delete tests cover backup/restore. Disabling only the transaction's resolved-purge callback makes this test fail because the private request survives. Both the uncached functional commit and the final cached source passed all four tests.

The pending-dependency warning has three separate checks. Real native vaults produce the count while a healthy object can still download and export. A native FFI test independently decodes a nonzero count and a healthy object from the existing 98-byte response. A host test injects only the pending signal into the boundary; media import, key grant, download, verification and export still use JNI. That host test proves warning selection and clearing, not native causal creation.

Native request-custody tests cover both collection arrival orders. Each collection retains its own private prekey and can decrypt its original response. A stale foreign download plan cannot replace an already active container. The valid row, ciphertext, key and exported bytes remain unchanged. Active object IDs remain globally unique: an active collision reports an explicit conflict rather than creating a second active object with that ID.

## Backup and key-erasure regression coverage

| Suite | Observed property |
| --- | --- |
| `chur-media/tests/backup_flow.rs` | A real encrypted package restores original bytes. Corrupt source/package, cancellation and incomplete restore do not report a usable backup. |
| `chur-media/tests/backup_identity.rs` | Backup works after ordinary local identity creation. Recovery uses a separate portable identity and preserves the live signer. |
| `chur-media/tests/permanent_sync_delete.rs` | Terminal source deletion erases sibling custody through restart and backup/restore, preserves a healthy neighbor, and rejects foreign collection collisions. |
| `chur-media/tests/revocation_causality.rs` | Four real vaults preserve signed revocation bounds. Deferred terminal purge removes private requests, pending grants and active envelopes when its cause resolves. |
| `chur-ffi/src/registry.rs` reader teardown tests | Permanent deletion and owner shutdown revoke existing readers and reject new publication. In-flight reads release their plaintext and cached keys. |

## Native test cache correction

The original Gradle host-test tasks depended on the JNI build but did not declare the loaded JNI library as an input. A native-only edit could therefore leave those tests up to date. The correction tracks the exact library bytes as a named input, adds the Cargo lockfile and pinned toolchain to both host and Apple Cargo tasks, and builds with `--locked`.

The cache test used a real, valid Cargo rebuild. An unchanged rerun stayed up to date. Two temporary blank lines before a JNI export changed valid debug metadata and the library hash; Gradle then reported that `churHostJniLibrary` changed and executed the JVM test worker. The source was restored exactly. All 28 tests in the affected core-ffi host module passed without skips. The two-file change passed independent review with no P0/P1 finding. The complete unfiltered Kotlin run then passed all 763 tests. Every host/JVM test task executed; none reused an up-to-date test result. The change was pushed separately as `afccdc9`.

## Review decisions

The focused checkpoint review requested an upgrade migration for the removed same-sequence uniqueness constraint. The focused collection-isolation review requested an upgrade migration for scoped tombstones. Both requests apply to existing deployed databases. They do not apply to this development build: the user explicitly confirmed that there are no active clients and approved direct schema changes without migrations or new versions. The separate request-custody isolation finding was reproduced and fixed. The focused reviews of causal revocation, scoped custody, container protection and FFI pending-count encoding reported no P0 or P1 findings. The final residual sync review also reported no P0 or P1 findings after the combined source was frozen. Both new functional commits were pushed separately. The first commit also passed a full workspace/all-targets compile before the custody commit was applied.

A later review reported a possible rollback when an unseen older checkpoint has the same authenticated head frontier but a different collection-epoch digest. This changes the selected signed metadata, but no production reader uses that digest to install a key, epoch or freshness floor. Signed operations and grants set epoch state; head floors only rise. Enrollment and backup pin exact checkpoint commitments, and the relay retains those exact records. The reported key/epoch rollback was therefore not reproduced. The inline invariant and rollback specification now state that same-frontier opaque claims are unordered.

The approval-ordering review claimed that a cached approval behind the saved ACK cursor enters the publication loop and fails. The frozen code instead starts its search at that cursor and returns the unchanged state when the old operation is absent from the later pages. A real JNI test covers a successful approval, later native media edits, restart and the same cached approval. This finding does not match the executed code path.

Device approval and revocation publish their missing ordinary operation prefix before their cached checkpoint and control endpoint. Native cached revocations are recovered before any own-operation ACK cursor can cover them. The host retains pending revocations until the remaining ordinary operations and current checkpoint are accepted. Real JNI tests cover lost host saves, restart, several lost revocations, later offline edits, cached approval retry and a final checkpoint failure.

## Current parallel feature integration

The user added external-file encryption in `3b6c0f78` after the earlier correction snapshot. It is preserved. A separate focused source check of the new native path and its immediate FD/password boundaries found no confirmed new P1/P2 defect. This check is distinct from the original 15 findings. It covered encrypted staging, secret-buffer clearing, source bounds, descriptor ownership, path checks, cancellation and publication status.

External provider reads and output synchronization can block. Cancellation waits for those calls to return; this path does not promise a bounded wall-clock cancel. A process failure can leave incomplete encrypted output before host cleanup. It does not make that output a completed archive. These limits are also stated in the external-file feature contract.

After the full validation source was frozen, the user added local OCR and encrypted content search in `51ca048346ef2ae6a93064b7c60e934f6a6b8d43`. That feature, its schema V10 and ABI changes, and the remote fuzz dependency update `15d12b51` are preserved. The 769 native, 814 Kotlin and 157 Android test counts above apply to `fc5cd658`; they do not include this later feature.

Two separate source comparisons checked only its effect on the corrected backup, deletion and decoder paths. The corrupt-source backup guards and portable identity are unchanged. OCR index removal uses the existing deletion transaction before its WAL barrier, and a late index write rejects a non-active object. OCR uses the same private decoder service and mutex; it retains the PID handshake, descriptor barrier, failed-owner gate and fatal cleanup propagation. No regression was found in these checked paths. This is not a full OCR audit or a claim about OCR accuracy or device behavior. The [build for `51ca0483`](https://github.com/po4yka/Chur/actions/runs/38939375430) is separate from the completed 14-job `fc5cd658` run.

## Verification boundaries

A source review, host test, simulator test, CI result and physical-device result are separate evidence. None proves the others. The corrections do not erase an older backup, a previously exported plaintext file, or a copy already saved by an authorized recipient.

Physical-device extraction, OS backup inspection, power-loss injection, real AirPlay behavior and the first-use Photos alert still require device evidence. A simulator attribute check cannot prove hardware file protection. Release signing, deployment and production acceptance are outside this work.

An active device's payload can still require content that a signed revocation cutoff excludes. That private effect stays unresolved and produces a dependency warning. Independent operations, their authenticated outer chain and healthy downloads continue. The receiver does not restore the excluded device's authority or accept its above-cutoff content.

## Development fixture diagnosis

An initial final Android run failed on a vault made by an earlier development build. A copied synthetic vault reproduced the exact native error: its old `tombstones` table has no `collection_id` column, while the current deletion insert requires that column. Native password unlock succeeded; the pending-deletion sweep failed. The archived fixture contained two unfinished deletion rows. Both the original fixture and a separate archive were preserved. The same import, viewer swipe, permanent deletion and restart scenario passed twice on a freshly created vault. No authentication check or test assertion was removed. No migration was added, as the user approved direct schema changes for this development application.

## Android environment diagnosis

Repeated missing-node failures were traced before test cleanup. Chur remained unlocked and resumed, but its window had no focus and the active accessibility window belonged to Android. The visible dialog said that Pixel Launcher was not responding. The system ANR report named `com.google.android.apps.nexuslauncher` and an input-focus timeout. After closing that launcher dialog, the unchanged navigation, video-poster and recovery cases passed. This is not evidence of a Chur ANR. The photo zoom case then reached a separate pan assertion. Input timing showed that synchronous acknowledgements stretched its two taps to about 1.27 seconds; the application did not receive a recognized double tap. A first asynchronous-injection attempt still incurred system animation waits and did not pass normal validation. The final test sends timed touch events through the actual decor view, after checking the active Chur window, resumed lifecycle, attachment, focus and bounds. It restores actual 1× → 2.5× → 1× transitions and preserves the original pan and pinch assertions. This verifies application gesture handling; it is not a separate claim about OS-level double-tap dispatch. The production gesture implementation did not change. Temporary diagnostics were removed before final validation. No security flag, authentication check or ANR watchdog was disabled. A later seven-case run passed album navigation, video-poster display and photo zoom, then lost its application window. System logs recorded a native RenderThread crash in Android SystemUI, followed by a SystemUI restart and keyguard activation. AC power and stay-awake settings were still enabled. This failed run is not a passing suite. The emulator graphics backend was changed from SwiftShader to host graphics. Later runs use their recorded APK hashes; a passing diagnostic subset is not counted as a passing full suite.

The first full main-process run completed all 141 cases with four failures. The recovery case found a phrase left by the SystemUI-interrupted run; the failed fixture, preferences and sync state were preserved before a fresh vault was created. Three other failures were test synchronization defects corrected by the user in `182b9c49`: an empty album need not expose the old Android Back node; a fading photo toolbar can satisfy the Export check before the document transition completes; and Compose SetSelection can return false when the requested range is already selected. The corrected tests wait for the current album semantics, actual document presentation, and actual full selection range. Independent source checks confirmed that the document and private-note security assertions remain intact. Production source is unchanged between `a40b6f9` and `182b9c49`. The new test APK build and Android lint passed. The failed run is retained as diagnostic evidence and is not counted as a pass.

## Decoder cancellation correction

The first 182-based full execution covered 144 tests across the three process groups: 143 passed and one decoder-cancellation test failed. The child remained a real sleeping kernel task with 13 threads; it was neither a zombie nor a stale ActivityManager record. A new test delays the real service callback, cancels before the PID handshake, then releases that callback. It reproduced the original failure with a live child. The corrected full decoder class passed all four tests: delayed handshake, the real 30-second deadline, user cancellation and coroutine cancellation. Immediate process checks and a subsequent healthy import remain enabled.

A separate real-JNI regression injects a typed decoder cleanup failure and reproduces the swallowed-error path: removing only the typed cleanup catch returned Imported with no derivative. Restoring that catch made the test pass. The committed original remains readable before and after vault lock/unlock, and the caller source closes once. The controller maps the explicit error to INTERNAL_FAILURE.

Normal cleanup now waits for the captured kernel task to disappear or stop executing. If shutdown cannot be confirmed within the cleanup checks, it reports a fatal error, retains at most one unresolved owner and its duplicated descriptors, and prevents further decoder use until the app process ends. This exceptional path does not claim that the child exited. The observed early-cancellation leak occurred before source descriptor handoff; it is not evidence that photo bytes escaped.

A review raised the generic gap between checking a PID start time and signalling its numeric PID. Those calls are not an atomic kernel handle. The actual merged manifest has one secondary app-UID process, the private decoder service, and no shared UID. The decoder mutex excludes another operation through cleanup; the main process retains its distinct PID; signalling an unrelated UID fails permission checks. A failed unbind can restart only the owned service. Independent source inspection found no production path to the review's other same-UID victim. The current P1 classification was rejected, and these ownership assumptions are documented beside the signal. Adding another same-UID process or decoder binding path requires a new process-handle review. The final five-file change passed structured review with no accepted P0/P1 finding and was pushed separately as `02336eb`.

## Player and retention test diagnosis

A later full run executed all 155 then-current test IDs: 152 passed and three failed, with no skips. The failed run remains diagnostic evidence. The corrupt-cleanup test read a host page that can be cleared before its asynchronous refresh completes. Cancel only closes the dialog. The corrected test reads the actual native catalog and original bytes before and after both Cancel and confirmation. The related stop-sync test now uses the same stronger retention check. Both original scenarios passed together. The test correction was reviewed and pushed as `259920a`; the user's relocation of the cleanup test remains uncommitted.

The player failure was initially covered by Android's first-use immersive-mode dialog. Chur was resumed and playback continued, but SystemUI held the focused window and received the test's touch. The actual "Got it" button was accepted through its normal accessibility action. That setup run still reported its original failure and is not counted as a pass. Further traces showed stable native player objects and controller hiding more than five seconds after the Favourite touch. They did not reproduce a production timer defect. All trace probes were removed. The unchanged two player tests then passed together. No production timer or timeout assertion was changed.

## Complete Android execution groups

The frozen Android validation source is published commit `fc5cd6586828bfd3162e1a7b91008d7562291f89` (tree `a57611c1983d650a2e5b4cd9fa81836c765a55ae`). It includes `259920a` and the user's external-file encryption. The remote fuzz-lockfile update is preserved through a normal merge. The production APK SHA-256 is `bc0b669321b6b3d2b6cb59d2a8c6f3fd8a1b7a55eef20486fdd56a2af5ec8683`; the test APK SHA-256 is `011846c0b112b45c6098e37dc0edb684495a5a0918f1f81f7c5a343bf3a12533`. Both APKs pass 16 KiB ZIP alignment. All four packaged native libraries across arm64 and x86_64 have LOAD alignment of at least 16 KiB. The owned API 37 emulator reports a 16,384-byte page size. Runtime discovery found 157 unique test IDs in 32 classes, including the new decoder regression and the user's public-camera, public-shell, recently-added and external-archive tests.

The first `fc5cd658` remaining-group attempt was stopped after a navigation failure with a retained Pixel Launcher ANR dialog. The ANR named the old Launcher PID. A service binding had restarted Launcher with a new PID, but the old system ANR handler still presented its dialog. A second force-stop did not remove that retained dialog. There was no evidence of a new Chur ANR. The failed run and both process records were preserved. Only the owned emulator was restarted, without a data wipe or snapshot reset, before a fresh remaining-group run.

The two new tile test classes require a fresh instrumentation process. The host singleton is intentionally process-scoped; the tile fixture shuts down its controller but does not reset that singleton. A single shared-process launch cannot satisfy both those cold-start preconditions and the other UI tests.

The executed plan ran `ChurLockTileColdStartTest` alone, then `ChurLockTileTest` alone, then every remaining test with `notClass` naming only those two already executed classes. The target process was stopped before each invocation, with the same test-device PIN argument. This is the complete suite in three process groups, not a selection of smoke tests. Runtime discovery, JUnit outcomes and skip counts determine completion; the shell exit code alone is insufficient. The runner's [class and notClass parameters](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner) support this execution plan. No dependency or production reset hook is needed.

The final result contains all 157 discovered test IDs in 32 classes: 157 passed, zero failed, zero ignored and zero skipped. The union has no missing, extra or duplicate IDs. The normal APK hashes were checked again after execution, and the runtime checkout remained clean at `fc5cd658`.

| Process group | Passed | Failed or skipped | JUnit time |
| --- | ---: | ---: | ---: |
| Cold-start tile | 1 | 0 | 0.028 s |
| Tile with a host | 2 | 0 | 2.802 s |
| All remaining tests, after emulator restart | 154 | 0 | 545.569 s |

The successful remaining-group run required no manual focus or system-dialog intervention. Android reported no ANR since that boot. It includes the four real decoder shutdown tests, trusted-original deletion, share leases and scratch bounds, native retention after confirmation or cancellation, private notes, playback, public shells and external archives. No trace probe or weakened assertion was present. The owned emulator was then stopped without a RAM snapshot or data removal; the earlier failed fixtures and logs remain preserved.
