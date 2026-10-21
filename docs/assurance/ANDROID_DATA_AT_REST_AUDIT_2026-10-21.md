# Android data-at-rest audit, 2026-10-21

Static, read-only audit of the Android build at `d303ca7d`. All findings are now closed; see [Resolution](#resolution). Scope: correctness of every save path, encryption at rest, key management, decoy isolation, and exposure of private data to other apps, the OS, backups, and the network.

Five independent review passes ran. Each pass covered one area:

1. platform exposure;
2. keys and unlock;
3. container and catalog cryptography;
4. save durability;
5. plaintext lifecycle across the FFI.

A second reader checked the key lines of every High finding and of most Medium findings in the source. Those findings are marked **[checked]**.

## Result

There are no Critical findings. The core design is sound:

- The catalog uses SQLCipher with `synchronous=FULL` and `secure_delete`.
- Object containers use XChaCha20-Poly1305 chunks with full AAD binding and an authenticated final commit.
- Imports follow ADR-0012 and are crash-consistent.
- Backup rules fail closed.
- `FLAG_SECURE`, `IME_FLAG_NO_PERSONALIZED_LEARNING` and a null saveable registry protect the private routes.
- All 119 JNI exports catch panics.

There is one High finding, in decoy isolation. Most Medium findings are about lifetime: plaintext or catalog copies that stay on disk after the process is killed, and settings that do not survive a crash.

| Severity | Count |
|---|---|
| High | 1 |
| Medium | 11 |
| Low | 20 |
| Info | 8 |

## High

### H1. Device unlock opens the first identity that has a Keystore slot **[checked]**

- `rust/crates/chur-catalog/src/vault.rs:682-705` (`android_keystore_material`) returns the Keystore slots of **every** registry entry, in filename order.
- `shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/ChurController.kt:709-716` tries each entry and stops at the first success.
- Each entry carries its own alias, nonce and AAD, so the first entry always authenticates.
- The comment at `apps/androidApp/.../AndroidDeviceUnlock.kt:91-92` ("A slot that belongs to another identity fails the tag") is incorrect.
- `addKeystoreSlot` (`ChurController.kt:820-824`) has no guard against a second identity.

**Failure:** The user enrolls device unlock in the real vault and in the decoy. A random filename order then decides which identity a fingerprint opens. A coercer who forces a fingerprint can open the real vault. The device slot of the other identity can never be used.

**Fix:** Allow a Keystore slot in one identity only, and refuse enrollment when `keystoreMaterial()` contains a slot of another identity. Correct the comment. Say this in the decoy provisioning text.

## Medium

### M1. A restore can splice an older catalog into a newer backup **[checked]**

- `chur-format/src/backup.rs:801-812`: `FinalBackupCommit` covers only the counts and the container inventory.
- `chur-media/src/backup.rs:738-745`: the `CatalogExport` record is checked only against `catalog_header_commitment`. That commitment covers only the 16-byte SQLCipher salt (`vault.rs:2026-2037`), which is the same for the life of the file.
- `manifest.catalog_generation` is written but never compared during restore.

**Failure:** Two packages A and B of the same vault exist, and only catalog state changed between them (deleted notes, captions, albums, revocations). An attacker puts A's catalog record into B. The restore succeeds and reports B, but the vault holds A's state. No credential is needed to do this.

**Fix:** Commit to the descriptor, catalog and envelope payloads in the sealed final commit. On restore, compare the catalog's generation with `manifest.catalog_generation`.

### M2. Argon2id memory and AEAD key copies are not zeroized **[checked]**

`rust/Cargo.toml:40,42` enables only `alloc` for `argon2` and `chacha20poly1305`. Without the `zeroize` feature, argon2 0.6 frees its 64 MiB block array and the initial hash without wiping them, and chacha20poly1305 does not wipe its key on drop. With p=1, the KEK is derived from the final block, so a process memory image taken after a lock can recover the password KEK.

**Fix:** Add `"zeroize"` to both feature lists. Use `hash_password_into_with_memory` with a `Zeroizing` block buffer. Replace `Key::new(*root_secret.expose())` (`vault.rs:659,1272`) with a copy that is zeroized.

### M3. The unlock screen shows that another identity exists

`ChurController.kt:846` offers device unlock when `keystoreMaterial()` is not empty, and that list includes the other identity. A decoy session whose settings show no device slot still gets the device-unlock button after a lock. DECOY_VAULT.md §10 forbids this signal. The fix for H1 also removes it.

### M4. The device-control link goes to the clipboard, and any local app can connect **[checked]**

- `DeviceControlBridge.kt:72` binds `127.0.0.1`, and any app with INTERNET can connect to it.
- `ChurRoutes.kt:965,1003-1008` copies `chur://device-control/v2?port=…#code` to the clipboard, and nothing clears it.

The default IME, a clipboard-history keyboard, or an app that has focus in split screen (where Chur stays RESUMED, so the bridge does not stop) can read the code. With the code, the app can export, change and delete every item of the open vault. The UI text says "anyone with the link and ADB access", but ADB is not needed.

**Fix:** Use a `LocalServerSocket` (abstract namespace) and accept only `peerCredentials.uid == Process.SHELL_UID`. Remove the copy button, or call `clearPrimaryClip()` on stop.

### M5. The media decoder process is not isolated **[checked]**

`shared/feature-import/src/androidMain/AndroidManifest.xml` declares `:media_decoder` without `android:isolatedProcess="true"`. The process parses untrusted shared-in files and runs OCR on vault plaintext. An exploit in a codec or in Tesseract runs as the app UID with INTERNET. It can then read `cache/export-scratch/` and send data out of the device.

**Fix:** Set `isolatedProcess="true"`. Check the caller against `applicationInfo.uid`. Pass the OCR models in as a `ParcelFileDescriptor` or load them from assets.

### M6. Autofill and content capture are not turned off on private routes (not tested on a device)

No `importantForAutofill`, `importantForContentCapture` or `AutofillManager.cancel()` exists in app or shared sources. FLAG_SECURE does not block autofill, so an autofill service can receive note titles, album names, search terms and a save prompt for the vault password. ANDROID.md:905-917 forbids this.

**Fix:** In `AndroidPrivacyCover.setEnabled`, set `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` and `IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS` on the decor view, and cancel autofill.

### M7. Share-out plaintext stays on disk after the process is killed **[checked]**

- `ExportDestinations.kt:28-41`: the only cleanup of `cache/export-scratch/` is in `init` and in main-looper `postDelayed` timers.
- `ExportDestinations` is created only when `MainActivity` builds `ChurHost`.

If the process dies while a share is pending, the full plaintext file stays until the user next opens the UI, possibly for good. The recipient's grant still works, because the system starts a cold process and `FileProvider` serves the file without building `ChurHost`. `postDelayed` also counts uptime only, so deep sleep makes the 30-minute limit longer. This breaks PLAINTEXT_LIFECYCLE.md §5 ("lifetime across … a process restart: none").

**Fix:** Subclass `FileProvider` for `.exports`. Delete the scratch directory in its `onCreate()`, and serve only files that are in the live-lease set. Enforce the limit by file mtime (wall-clock time) as well.

### M8. A killed backup leaves a copy of the catalog in `incoming/` **[checked]**

`chur-media/src/backup.rs:353-383` writes `incoming/<random>/catalog.db` and removes it only in `Drop`. No sweep at startup or at unlock covers that directory. Each killed backup leaves one more catalog copy, with no time limit. The copy holds notes and metadata that the user deletes later, encrypted under the same catalog key.

**Fix:** Use a fixed `incoming/backup-snapshot/` directory, and remove it at unlock next to `clear_scratch`.

### M9. Security settings are saved without atomic writes and fall back to the weaker value **[checked]**

`ChurHost.kt:63-96` writes `device-slot-policy.txt`, `app-lock.txt` and `auto-lock.txt` with `File.writeText` and no fsync. The readers treat a missing or unexpected value as `convenient` or `vault-only`. If power is lost after the truncate, the file can come back empty, and whole-app lock or the strict policy is then off with no warning.

**Fix:** Write with `android.util.AtomicFile`. Treat a value that cannot be parsed as the stricter setting.

### M10. There is no minimum length for a non-numeric password

`chur-core/src/limits.rs:144` sets `ENCODED_MIN = 1`. The 12-digit floor in `ChurController.kt:584-585` applies only when all characters are digits, so `"1234a"` passes. The same gap is in `changePassword` (`:1733`). With m=64 MiB and t=3, an offline attack on a short password from an extracted `registry/` is practical.

**Fix:** Require a minimum strength for new and changed passwords: at least 12 characters or a strength score. Keep accepting existing slots.

### M11. Unsaved private-note edits are lost at every lock (design decision)

`PrivateNotesController.kt:130-131` says "Explicit saves only". `onPause` locks the vault, so an incoming call or an app switch discards the typed text. Nothing is corrupted. This is listed for the product owner to decide: an encrypted draft row, or a warning before the lock.

## Low

| # | Finding | Evidence | Fix |
|---|---|---|---|
| L1 | The local catalog has no freshness binding. A copy of `catalog.db` (or of some of its pages) written back while the vault is locked restores deleted notes and revocations. | `db.rs:43-50`, ADR-0039. `descriptor.catalog_generation` is not compared at unlock. | Compare a monotonic generation from the descriptor at unlock. |
| L2 | A note delete or purge leaves the old pages in the WAL. The FTS index keeps tokens until a merge. | `notes.rs:189-248`, `product.rs:1675-1682`. `deletion.rs:178` does checkpoint. | `db.checkpoint()` after a purge, plus FTS `'optimize'`. |
| L3 | The docs describe per-record metadata sealing, but `record` is empty and the labels are not used. All metadata relies on SQLCipher alone. | `model.rs:234-248`, `import.rs:543`, `kdf.rs:92-128` | Correct the docs, or implement the sealing. |
| L4 | The SQLCipher cipher parameters are not pinned. | `db.rs:30,43-50` | `PRAGMA cipher_compatibility = 4`, or explicit parameters, plus a test. |
| L5 | Zeroization gaps: `Zeroizing<Vec>` grown by `extend_from_slice` (`container.rs:1562,1581`, `content_index.rs:104`, `live_photo.rs:36`); `backup.rs:603` `to_vec()`; stack key copy in `envelope.rs:378-384`; JNI write-back `Vec<i8>` (`chur-jni/src/convert.rs:111`); Kotlin range copies (`ChurVault.kt:1432`, `Playback.android.kt:533-538`); passwords and phrases as `String`, and the unlock field is not cleared after submit (`UnlockScreen.kt:81,120`). | as listed | Allocate with `with_capacity` before writing, return `Zeroizing`, wipe the intermediate arrays, and clear the field after submit. |
| L6 | One invalidated or malformed Keystore slot stops device unlock for both identities. | `AndroidDeviceUnlock.kt:93`, `vault.rs:694` | Skip the bad entry, try every entry, and report the result last. |
| L7 | No check of `KeyInfo.securityLevel`. StrongBox is requested in every mode, against ANDROID.md §9.5. No `setUnlockedDeviceRequired(true)`. API 29 uses a 10-second validity window. | `DeviceSlot.android.kt:61-97` | Reject or warn on a software-backed key, and add `setUnlockedDeviceRequired`. |
| L8 | `check-backup-rules.py` does not check each section, does not see `device_*` domains, and does not check the manifest wiring. | `scripts/check-backup-rules.py:38,56-91` | Check each section, and fail when a section is missing. |
| L9 | A `userId@authority` URI bypasses the own-provider check on incoming shares. There is no leak today. | `IncomingShareIntent.kt:24-27` | Refuse `@` in `encodedAuthority`. |
| L10 | A crash during a MediaStore or SAF export leaves a partial plaintext file in shared storage. | `ExportDestinations.kt:206-219` | Set `DATE_EXPIRES`, and delete the app's own pending rows at startup. |
| L11 | The backed-up `public/` set shows that a vault was configured and that the policy was changed. | `ChurHost.kt:63-84`, `data_extraction_rules.xml` | Always write the defaults, or store these files outside the backup. Add `disableIfNoEncryptionCapabilities`. |
| L12 | Sync invitation and pairing codes are copied through `SelectionContainer` without `EXTRA_IS_SENSITIVE`. | `PeerConnectionCard.kt:40`, `DevicePairingCard.kt:87` | Use an explicit copy action that sets the sensitive flag. |
| L13 | The exported `singleTask` activity uses the default `taskAffinity` (StrandHogg risk on unpatched API 29/30). | `AndroidManifest.xml:27-33` | `android:taskAffinity=""` |
| L14 | The peer listener binds `0.0.0.0`, and the first TCP connection uses up the invitation before any secret is proven (DoS of pairing). **[checked]** | `chur-media/src/peer_transport.rs:33,80` | Bind the user's address, and keep listening until the AEAD verifies. |
| L15 | The peer device identity is self-asserted, and the PSK-only session has no forward secrecy. Object keys stay safe because grants are signed, but heads and history metadata are exposed to anyone who holds the invitation. | `PeerSyncCoordinator.kt:197-205`, `peer_transport.rs:166-178` | Add a hello transcript signed with the device key, and an optional X25519 exchange. |
| L16 | Orphan files are not swept: a temp container created before its journal row, and public-note `.tmp` files and blobs. | `import.rs:281-309`, `api.rs:406-408`, `FileNoteStore.kt:110-116` | Sweep with `is_orphan_container` at unlock, and remove unreferenced blobs at `load()`. |
| L17 | Directory fsync errors are discarded. Restore does not fsync the shard directories before it installs the descriptor. | `vault.rs:2003`, `sync_staging.rs:389-393`, `backup.rs:1209-1236` | Return errors other than EINVAL/ENOTSUP, and fsync the directories before the install. |
| L18 | The device-slot policy is written before the slot swap. A kill during the swap makes the UI show the wrong policy. | `ChurController.kt:870-896` | Write the policy after the swap, or reconcile it at startup. |
| L19 | `sync_staging` removes old records before it installs the new one. A kill between the two loses one record. | `sync_staging.rs:145-159` | Install first, then remove. |
| L20 | Two unlock error paths drop the session without `lock()`. | `chur-ffi/src/api.rs:424-425` | Use the same `lock()` on error as lines 416 and 427. |

## Info

- `ContainerReader::read_chunk` is public and does not check the final commit (truncation). Its only callers are in the CLI bench. Make it `pub(crate)`.
- There is no length padding, so the plaintext length can be calculated from the file length. CRYPTOGRAPHY.md §5 lists this as a non-goal.
- The backup `CatalogExport` is the raw SQLCipher file. BACKUP_FORMAT_V1.md:21 documents this divergence.
- A rewrap keeps the object key, and superseded envelopes stay as rows (CRYPTOGRAPHY.md:1790).
- There is no attempt counter or wipe after N failures. PASSWORD_PROFILE.md permits this.
- `CreateVault` is not in `needsPrivacyCover` (`Privacy.kt:60-63`).
- Any app can send `lock-and-hide`. The worst result is a forced lock.
- The Rust scratch journal (`export.rs:121 materialize`) has no production caller. The production share scratch (M7) has no journal.

## Checked and correct

- **Backup:**
  - `vaults/` and `registry/` are excluded.
  - Only `public/` and `public_shell.xml` are included.
  - The legacy `backup_rules.xml` has the same include set.
- **Providers:**
  - All providers are not exported.
  - Grants are read-only, go only to the chooser, and are revoked on release, on lock and in `cancelPending()`.
  - Camera paths are checked against their canonical path.
- **Catalog:** SQLCipher with a raw key in `Zeroizing`, WAL, `synchronous=FULL`, `secure_delete`, `temp_store=MEMORY`, `cipher_memory_security`, and FTS5 secure-delete.
- **Containers:**
  - Per-stream HKDF keys, with a random `stream_id` and the revision.
  - The chunk nonce is a random prefix plus the chunk index.
  - The AAD binds version, suite, object, stream, kind, revision, manifest commitment, index and length.
  - The authenticated final commit detects truncation.
  - The parsers have bounds.
- **Slots:**
  - Argon2id m=64 MiB, t=3, p=1, with no downgrade path.
  - A fresh 16-byte salt for each slot.
  - A constant-cost unlock with two derivations.
  - The Keystore key is AES-256-GCM with a random IV, needs authorization for each use on API 30 and later, and maps an invalidated key to `PLATFORM_KEY_INVALIDATED` without data loss.
- **Durability:**
  - Descriptor and container installs use fsync, then rename, then a directory fsync.
  - Import ordering follows ADR-0012, and `reconcile` handles every crash point.
  - Originals are offered for deletion only after a durable commit.
  - Migrations run in one transaction each.
  - The `DescriptorLock` flock prevents a second session.
- **Plaintext:**
  - No Media3 cache, Coil, Glide or WebView.
  - PDF and OCR read through proxy FDs.
  - The thumbnail cache is cleared on every lock path.
  - Lock revokes the readers before the catalog closes.
  - Handles carry a generation, so a stale handle is refused.
- **Logging:** only fixed strings. The panic hook prints only the location.

## Not verified

- **Dependency advisories:** `cargo deny --offline check advisories` could not run without network access (`anstyle-wincon` was not in the cache). Gradle dependencies were not audited.
- **Runtime behaviour on a device:** H1, M3, M6, L6, L13, StrongBox, and f2fs/ext4 directory ordering.
- **Merged manifest:** components that AAR dependencies add.
- **Out of scope:** iOS.

## Resolution

Every finding is closed. Each fix had an independent review, and the review findings were fixed before the fix reached `main`. The commit column gives the commit on `main`.

| ID | Status | Commit | Note |
|---|---|---|---|
| H1, M3 | Fixed | `ac8384d8` | A device slot can be enrolled in one identity only. Replacing a slot in the same identity stays allowed. DECOY_VAULT.md §5 and §10 record the remaining signal. |
| M1 | Fixed | `df01da83`, `722160d9` | The first commit compares the catalog generation. Backup package v2 (ADR-0068) commits to every record and carries a portable catalog export. |
| M2 | Fixed | `a753acdd` | |
| M4 | Fixed | `7fc641d7` | Abstract socket, shell or root peer only, no clipboard copy. |
| M5 | Fixed | `5bfafcd8` | Image, PDF and JPEG XL decoding runs in an isolated process. API 37 refuses the system media services to an isolated process, so video, audio and HEIF/AVIF run in an app-UID process that receives bytes over Binder, and OCR runs in an app-UID process that receives pixels only. ANDROID.md §15.1 records the residual risk. |
| M6 | Fixed | `f9c14e36` | Covers private dialog windows too. |
| M7 | Fixed | `53e06811` | |
| M8 | Fixed | `a2afc5f3` | |
| M9 | Fixed | `bf082146` | |
| M10 | Fixed | `2910d782` | |
| M11 | Fixed | `5a228d6c` | An ordinary lock saves the open note as a revision. A panic lock does not save. |
| L1 | Already correct, documented | `c99f9652` | Unlock already refused an older catalog. The audit statement was wrong. The document now states the rule. |
| L2 | Fixed | `4d7a2bdf` | |
| L3 | Documented | `d85b1e94` | The documents now state that SQLCipher alone protects metadata. |
| L4 | Fixed | `fb73c781` | |
| L5 | Fixed | `66a5b709`, `12ee334e`, `dffedbdb` | |
| L6 | Fixed | `1ca25c44` | |
| L7 | Fixed | `5e87b138` | A software-only Keystore refuses device-slot enrollment. |
| L8 | Fixed | `474fa2c5` | |
| L9 | Fixed | `21ff7492` | |
| L10 | Fixed | `78ec503d` | MediaStore replaces a `DATE_EXPIRES` that the app sets, so the fix deletes pending rows at start. |
| L11 | Fixed | `e4125805` | |
| L12 | Fixed | `c60a77bb` | |
| L13 | Fixed | `e6c98908` | |
| L14 | Fixed | `3799dce7` | |
| L15 | Fixed | `99d74467` | Peer protocol v2 (ADR-0067). |
| L16 | Fixed | `4d9961d9`, `1f95d386` | |
| L17 | Fixed | `f9ece33a` | |
| L18 | Fixed | `d2104650` | |
| L19 | Fixed | `cc49a2a9` | |
| L20 | Fixed | `83539817` | |
| Info: `read_chunk` | Fixed | `a8af3549` | |
| Info: no padding | Fixed | `b8dddd57` | Container v2 with size buckets (ADR-0066). |
| Info: raw SQLCipher backup | Fixed | `722160d9` | |
| Info: rewrap keeps object keys | Fixed | `1d1af99b` | Superseded envelopes are deleted, and objects are re-encrypted after a revocation (ADR-0069). |
| Info: no attempt counter | Fixed | `e011e8f3` | Unlock delay after repeated failures, the same for every identity. |
| Info: `CreateVault` not covered | Fixed | `c06ae620` | |
| Info: `lock-and-hide` from any app | Fixed | `fa6e5b31` | |
| Info: unused scratch journal | Removed | `e6501fa5` | |

The fixes also needed three follow-up commits on `main`: `eab69174` (a clippy lint in code from another change), `40277477` (a CI test count) and `d6a1357b` (a device test that needed an export lease).

Not verified on hardware: behaviour on a hardware-backed Keystore (the emulator Keystore is software-only), API 29 devices, and two-device peer sync over a real network.
