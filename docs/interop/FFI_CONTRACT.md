# Rust–KMP FFI Contract

> **Status:** Proposed normative interop contract

The FFI boundary exposes coarse-grained vault operations without making Kotlin, Swift, JNI, Objective-C, UniFFI, or Gobley part of the private storage protocol.

## 1. Layers

```text
chur-core / crypto / format / catalog / media
    ↓ Rust-native APIs
chur-ffi   one stable C ABI, one process-global handle registry
    ├──────────────────────────────┐
    ↓                              ↓
KMP expect/actual adapter      platform shell data-plane adapter
    ↓                              (iOS AVAssetResourceLoader in v1)
features and platform shells
```

The handle registry is process-global and language-agnostic. A handle created through the control plane in one language is usable from the other, and lock invalidates it for both in one step. A platform shell adapter may call the data plane directly when this avoids repeated copies, but it never creates or owns a session: sessions are created and closed through the shared application layer, and exactly one Rust runtime exists in the process (§14).

Bindings are replaceable. The secure core has no dependency on binding-language types.

## 2. ABI versioning

The native library exports a handshake that answers every fact a platform gate checks before a vault opens. These functions are callable from any thread before runtime initialization and cannot fail:

```text
chur_abi_version_major()   -> uint32_t
chur_abi_version_minor()   -> uint32_t
chur_capabilities()        -> uint64_t
chur_object_format_min()   -> uint16_t
chur_object_format_max()   -> uint16_t
chur_key_slot_format_min() -> uint16_t
chur_key_slot_format_max() -> uint16_t
chur_build_flavor()        -> uint32_t
```

- native API version is the (major, minor) pair. v1 ships 1.6: §6.5 through §6.10 each added one minor surface. A different major value fails loading, reports `ABI_INCOMPATIBLE`, and the library is not called again in that process. A major value of `0` is such a value: §11 makes it what a handshake export returns when its body panics, so a panicking library fails the gate;
- the object-format range is the inclusive `container_version` interval this build reads, using the values registered in [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15;
- the key-slot range is the inclusive key-slot format interval;
- build flavor is a bitfield: bit 0 set means a release build, bit 1 set means debug assertions are compiled in, bit 2 set means test hooks are compiled in. A release application refuses a library with bit 1 or bit 2 set;
- required feature flags are capability bits.

`chur_capabilities()` returns a bitmask:

| Bit | Name | Meaning |
| ---: | --- | --- |
| 0 | `CHUR_CAP_DECOY_VAULT` | independent decoy identity supported |
| 1 | `CHUR_CAP_OBJECT_READER` | random-access authenticated reader available |
| 2 | `CHUR_CAP_SEQUENTIAL_READER` | sequential reader available |
| 3 | `CHUR_CAP_INTEGRITY_SCAN` | background integrity scan available |
| 4 | `CHUR_CAP_BACKUP_PACKAGE` | portable backup package import/export available |
| 5 | `CHUR_CAP_SYNC` | ciphertext sync available |
| 6 | `CHUR_CAP_CONCURRENT_READS` | one reader handle serves parallel reads (§8) |
| 7 | `CHUR_CAP_COLLECTION_SHARING` | local sharing identity and collection records available |
| 8-63 | reserved | zero in v1 |

An unknown set bit is ignored and never enables behavior. Minor and capability differences are negotiated only within explicitly compatible behavior; they never select cryptographic suites from untrusted input.

## 3. Handles

Opaque handles represent:

```text
RuntimeHandle
VaultSessionHandle
ObjectReaderHandle
ImportHandle
ExportHandle
IntegrityScanHandle
```

Requirements:

- `chur_handle_t` is `uint64_t`: the low 32 bits index a typed registry slot, the high 32 bits carry that slot's generation counter. It is never a raw pointer and never a business ID; `0` is the null handle;
- explicit owner runtime/session;
- thread affinity and concurrency fixed per handle type by the table in §8, not per instance;
- close is idempotent for every handle type without exception: the first close releases the resources, and every later close of the same value returns success and does nothing. Close never returns `NOT_FOUND` or `SESSION_EXPIRED`; closing a value this process never issued returns `INVALID_INPUT`, which the generation counter makes distinguishable from a re-close;
- a handle value is never reissued: the generation counter of a slot increments on every allocation, so a stale value cannot alias a live handle for the life of the process;
- stale generation returns `SESSION_EXPIRED`;
- no handle revives after lock;
- handle registry bounded against leaks/DoS.

## 4. Session generation

Every opened vault session receives a monotonically changing in-process generation. Handles capture it. Locking:

1. marks session cancelling;
2. increments/invalidates generation;
3. zeroizes session secrets in place;
4. closes catalog;
5. cancels operations;
6. makes every old handle fail.

UI cleanup is not required for native invalidation.

## 5. Control plane

Suitable values:

- commands and bounded query parameters;
- opaque references;
- small projections;
- stable error codes;
- progress summaries;
- migration/integrity states;
- capability flags.

Control records must not contain keys, decrypted manifests, private paths, or arbitrarily large media bytes.

## 6. Data plane

Large data uses:

- platform file descriptors/seekable handles when safe;
- caller-provided direct/native buffers;
- `read_at(offset, destination)`;
- authenticated content information before the first range request;
- bounded sequential import/export;
- explicit byte counts;
- no whole-file `ByteArray`, `NSData`, or generated-binding list.

### 6.1 Content information

A range reader must publish content information before it answers the first request: Media3 needs a length and AVFoundation needs `contentInformationRequest` filled in. `chur_object_reader_content_info` supplies it from authenticated canonical metadata, never from the provider hint that [`MEDIA_PIPELINE.md`](MEDIA_PIPELINE.md) §3 classifies as untrusted:

```text
ChurContentInfoV1
    plaintext_size        u64        authenticated size from the final commit record
    content_type          char[64]   NUL-terminated lowercase IANA media type
    media_kind            u16        canonical metadata media-kind value
    byte_range_supported  u8         1 for a committed immutable object
    complete              u8         1 only after final-commit validation
```

- the content-type identifier space is IANA media types, at most 63 bytes plus the terminator, taken from the canonical metadata Rust validated at import. Android uses the value as the MIME type unchanged; iOS converts it with `UTType(mimeType:)`;
- content information is publishable only when the final commit record validates. Until then the call returns `OBJECT_INCOMPLETE` and the adapter fails the open or loading request instead of publishing a size, because a player that has been given a length treats a later failure as a transport error and retries indefinitely;
- a reader opened on an incomplete object may still serve `read_at` for resumable transfer and verification, but it must not be attached to a player.

### 6.2 Exported symbols

Every exported symbol is `chur_` followed by lower snake case. An operation on a handle takes the shape `chur_<subject>_<verb>`; the eight handshake accessors of §2 are named `chur_<fact>` for the value they return and are the only exceptions. Nothing else leaves the artifact: the Android link step applies a version script, the Apple link step an exported-symbols list, and a release check fails on any symbol outside this set. `chur_handle_t` is `uint64_t` with `0` as the null handle, and `chur_status_t` is the `int32_t` of [`../ERROR_MODEL.md`](../ERROR_MODEL.md).

The Phase-1 surface is frozen. Adding an export raises the minor ABI version; changing or removing one raises the major version. `chur.h`, checked in with the first `chur-ffi` export, is the deliverable both platform teams build against, and every binding derives from it.

The list below is the surface at ABI 1.0. §6.5 adds the exports Phase 1's own product scope requires and raises the minor version to 1; nothing in this list changed.

```c
/* handshake: any thread, before initialization, cannot fail (§2) */
uint32_t chur_abi_version_major(void);
uint32_t chur_abi_version_minor(void);
uint64_t chur_capabilities(void);
uint16_t chur_object_format_min(void);
uint16_t chur_object_format_max(void);
uint16_t chur_key_slot_format_min(void);
uint16_t chur_key_slot_format_max(void);
uint32_t chur_build_flavor(void);

/* runtime and session */
chur_status_t chur_runtime_open(const ChurRuntimeConfigV1 *config, chur_handle_t *out_runtime);
chur_status_t chur_runtime_close(chur_handle_t runtime);
chur_status_t chur_vault_unlock(chur_handle_t runtime, const ChurUnlockRequestV1 *request,
                                chur_handle_t *out_session);
chur_status_t chur_vault_lock(chur_handle_t session, uint32_t reason);
chur_status_t chur_session_close(chur_handle_t session);

/* catalog queries: a bounded projection written into a caller buffer */
chur_status_t chur_catalog_query(chur_handle_t session, const ChurQueryV1 *query,
                                 uint8_t *destination, size_t capacity, size_t *bytes_written);

/* operations */
chur_status_t chur_import_begin(chur_handle_t session, int32_t source_fd,
                                const ChurImportRequestV1 *request, chur_handle_t *out_import);
chur_status_t chur_export_begin(chur_handle_t session, const ChurObjectRefV1 *object,
                                int32_t destination_fd, chur_handle_t *out_export);
chur_status_t chur_integrity_scan_begin(chur_handle_t session, const ChurScanRequestV1 *request,
                                        chur_handle_t *out_scan);
chur_status_t chur_operation_poll(chur_handle_t operation, ChurProgressV1 *out_progress);
chur_status_t chur_operation_cancel(chur_handle_t operation);
chur_status_t chur_operation_close(chur_handle_t operation);

/* object reader */
chur_status_t chur_object_reader_open(chur_handle_t session, const ChurObjectRefV1 *object,
                                      uint32_t stream_kind, chur_handle_t *out_reader);
chur_status_t chur_object_reader_size(chur_handle_t reader, uint64_t *out_size);
chur_status_t chur_object_reader_content_info(chur_handle_t reader, ChurContentInfoV1 *out_info);
chur_status_t chur_object_reader_read_at(chur_handle_t reader, uint64_t offset, uint8_t *destination,
                                         size_t capacity, size_t *bytes_written);
chur_status_t chur_object_reader_verify_complete(chur_handle_t reader, uint32_t *out_state);
chur_status_t chur_object_reader_close(chur_handle_t reader);
```

### 6.3 Range reads

`chur_object_reader_read_at` never mixes an error with a byte count: the status is the return value, the count is written through `bytes_written`.

- `bytes_written` is set on every call, including every failure, where it is set to `0`;
- on success `*bytes_written <= capacity`. A short read is permitted at any offset, not only near the end: the reader returns at most the authenticated bytes it already holds, so the caller must loop until it has the range it needs or observes `*bytes_written == 0`;
- `*bytes_written == 0` with a success status means end of authenticated plaintext, and occurs only when `offset == size`;
- `offset == size` returns success with `0` bytes;
- `offset > size` returns `INVALID_INPUT`, never a zero-length success, so a seek past the end stays distinguishable from end of stream;
- `capacity == 0` returns success with `0` bytes and touches nothing;
- on any failure status the whole destination buffer holds unspecified bytes. The caller must not use any prefix of it, and must not treat bytes written by an earlier successful call into the same buffer as still valid;
- `size` is the authenticated plaintext size from the final commit record, not a file length.

### 6.4 The catalog page encoding

`chur_catalog_query` writes one page into the caller's buffer as canonical bytes rather than as a C structure. A structure would carry the padding and alignment of whichever compiler built the host, and [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §13 reserves the definition of persisted and boundary bytes for Rust; a page whose layout depended on the host's compiler would not be one definition.

```text
ChurPageV1
    total_count           u64        rows the scope holds, not rows returned
    catalog_generation    u64        the generation the page was read at
    object_count          u32        projections that follow
    next_cursor_present   u8         0x00 or 0x01
    next_cursor           bytes[42]  the §16.2 cursor, zero bytes when absent
    objects               object_count × ObjectProjectionV1, §16.1
```

The header is 63 bytes and a projection is 79, so a page of `n` rows is `63 + 79n` bytes and a caller sizes its buffer from the `limit` it asked for. A buffer smaller than the page is `RESOURCE_LIMIT_EXCEEDED` and writes nothing: a truncated page would be indistinguishable from a short one, and the caller would treat the scope as exhausted.

`ChurQueryV1` is the request. It is a C structure because it is the caller's to build, and it carries no variable field except the search terms:

```text
ChurQueryV1
    scope                 uint8_t    1 timeline, 2 album, 3 favorites, 4 tag, 5 search, 6 quarantine
    sort                  uint8_t    1 capture_desc, 2 capture_asc, 3 import_desc
    kinds                 uint16_t   the §16.2 media-kind mask
    limit                 uint32_t   1 to 500, 0 for the default of 200
    scope_id              uint8_t[16]  the album or tag, zero bytes otherwise
    cursor_present        uint8_t    0 or 1
    cursor                uint8_t[42]  the §16.2 cursor
    terms                 const uint8_t *  UTF-8 search text, not NUL-terminated
    terms_length          uint32_t
```

`terms` is read only when `scope` is `search`, and `terms_length` bounds it; the pointer is not retained after the call.

`chur_object_reader_verify_complete` writes through `out_state`, on success only, the `integrity_summary` value the scan reached: the enum of [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §5.1, whose byte values are allocated in [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15.4. Proven corruption is a lifecycle change rather than a verification verdict, so it returns `OBJECT_CORRUPT` and writes no state.

The control plane uses these same symbols through a thin KMP `expect`/`actual` adapter. No binding generator is part of the boundary ([ADR-0016](../adr/0016-freeze-the-v1-c-abi.md)).

### 6.5 The Phase-1 product surface, ABI 1.1

The list in §6.2 is the boundary a host needs to open a vault and read from it. It is not the boundary a host needs to *deliver* Phase 1, and the gap is not a matter of degree: with §6.2 alone an application cannot create a vault, so no vault ever exists to unlock; it cannot mark a favourite, create an album, delete an object, or read a thumbnail, so three of the four destinations of [`../../DESIGN.md`](../../DESIGN.md) §10 have nothing to show.

These exports close that gap. They are an addition, so they raise the minor ABI version to 1 and change nothing in §6.2. A host built against 1.0 still works: §2 negotiates a minor difference within explicitly compatible behaviour, and an export a host does not call costs it nothing.

```c
/* provisioning, PROVISIONING.md section 3 */
chur_status_t chur_vault_present(chur_handle_t runtime, uint8_t *out_present);
chur_status_t chur_vault_create_begin(chur_handle_t runtime,
                                      const ChurCreateRequestV1 *request,
                                      chur_handle_t *out_creation);
chur_status_t chur_vault_creation_add_recovery_slot(chur_handle_t creation,
                                                    uint8_t *destination,
                                                    size_t capacity,
                                                    size_t *bytes_written);
chur_status_t chur_vault_creation_activate(chur_handle_t creation,
                                           chur_handle_t *out_session);
chur_status_t chur_vault_creation_abandon(chur_handle_t creation);

/* key slots, KEY_SLOTS.md section 9 */
chur_status_t chur_vault_add_recovery_slot(chur_handle_t session, uint8_t *destination,
                                           size_t capacity, size_t *bytes_written);
chur_status_t chur_vault_add_device_slot(chur_handle_t session,
                                         const uint8_t *item_id,
                                         uint8_t *out_secret);
chur_status_t chur_vault_remove_slot(chur_handle_t session, const uint8_t *slot_id);
chur_status_t chur_vault_change_password(chur_handle_t session,
                                         const ChurUnlockRequestV1 *request);
chur_status_t chur_vault_slots(chur_handle_t session, uint8_t *destination,
                               size_t capacity, size_t *bytes_written);

/* library, DESIGN.md sections 11 to 13 */
chur_status_t chur_object_set_favorite(chur_handle_t session,
                                       const ChurObjectRefV1 *object,
                                       uint8_t favorite);
chur_status_t chur_object_delete(chur_handle_t session, const ChurObjectRefV1 *object);
chur_status_t chur_object_metadata(chur_handle_t session,
                                   const ChurObjectRefV1 *object,
                                   uint8_t *destination, size_t capacity,
                                   size_t *bytes_written);
chur_status_t chur_album_create(chur_handle_t session, const uint8_t *name,
                                uint32_t name_length, uint8_t *out_album_id);
chur_status_t chur_album_set_membership(chur_handle_t session,
                                        const uint8_t *album_id,
                                        const ChurObjectRefV1 *object,
                                        uint8_t member);
chur_status_t chur_album_list(chur_handle_t session, uint8_t *destination,
                              size_t capacity, size_t *bytes_written);
chur_status_t chur_tag_create(chur_handle_t session, const uint8_t *name,
                              uint32_t name_length, uint8_t *out_tag_id);
chur_status_t chur_object_set_tag(chur_handle_t session, const uint8_t *tag_id,
                                  const ChurObjectRefV1 *object, uint8_t tagged);

/* derived assets, MEDIA_PIPELINE.md section 6 */
chur_status_t chur_derived_put(chur_handle_t session, const ChurObjectRefV1 *object,
                               uint32_t kind, uint32_t width, uint32_t height,
                               const uint8_t *bytes, uint32_t length);
chur_status_t chur_derived_read(chur_handle_t session, const ChurObjectRefV1 *object,
                                uint32_t kind, uint8_t *destination, size_t capacity,
                                size_t *bytes_written);
```

`ChurCreateRequestV1` is the password and the Argon2id profile a new vault is created with:

```text
ChurCreateRequestV1
    password          const uint8_t *
    password_length   uint32_t
    memory_kib        uint32_t   0 for the frozen v1 floor
    iterations        uint32_t   0 for the frozen v1 default
    parallelism       uint32_t   0 for the frozen v1 default
```

A creation handle is a fourth handle type. It exists because [`../security/PROVISIONING.md`](../security/PROVISIONING.md) §3 has a middle: the recovery slot is offered at step 5, after the password slot is verified at step 4 and before the descriptor reaches `ACTIVE` at step 6. A single create call would have to skip the offer or take a callback, and §10 admits no callback. Closing a creation handle without activating it abandons the creation, which §9 of [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) requires to leave nothing openable.

Two things cross that §12 would otherwise keep inside, and they cross differently.

`out_secret` is 32 bytes and is the `DeviceUnlockSecret` of [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §5. It must reach the platform keystore, which is exactly §12's "unavoidable for a key-slot operation", and it never reaches a screen: the host stores it and clears the buffer.

The recovery slot writes the *phrase* rather than the 32 canonical bytes. [`../security/RECOVERY.md`](../security/RECOVERY.md) §2 requires the user to see it, and the phrase is a presentation encoding, which [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §13 reserves for Rust; a host given the bytes would have to implement BIP-39 twice, once per platform, to show anything. It crosses as bounded UTF-8 bytes in a caller buffer, which is the same shape §12 already permits for a password entering Rust, and the host clears the buffer once the user has seen it. `CHUR_RECOVERY_PHRASE_MAX` is 216: twenty-four words of the English list, whose longest entry is eight characters, plus the separators.

Three list results are canonical bytes, for the reason §6.4 gives:

```text
ChurSlotListV1
    count             u32
    entries           count × { slot_id: bytes[16], slot_type: u8, slot_generation: u64 }

ChurAlbumListV1
    count             u32
    entries           count × { album_id: bytes[16], member_count: u64,
                                name_length: u16, name: bytes[name_length] }

ChurObjectMetadataV1
    capture_time_ms            u64
    import_time_ms             u64
    capture_time_substituted   u8
    width                      u32
    height                     u32
    duration_ms                u64
    plaintext_size             u64
    content_type_length        u16
    content_type               bytes
    filename_length            u16
    filename                   bytes
    caption_length             u16
    caption                    bytes
    tag_count                  u16
    tags                       tag_count × { tag_id: bytes[16], name_length: u16, name: bytes }
```

`ChurObjectMetadataV1` is the only record in this contract that carries free-form private text, and §16.1 of [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) is why: a page of 200 rows must never carry 200 filenames, so the projection carries none and a detail screen fetches them for one object. A caller that asked for this record per row would be defeating that rule rather than using this one.

A buffer smaller than the record is `RESOURCE_LIMIT_EXCEEDED` and writes nothing, exactly as in §6.4.

### 6.6 The Android Keystore surface, ABI 1.2

The Android Keystore is the one key-slot family whose AEAD runs outside Rust. Its wrapping key is generated inside the Keystore and is non-exportable, which is the property that makes it worth having, and it means the cipher runs on the platform side. [`../format/KEY_SLOT_BODIES_V1.md`](../format/KEY_SLOT_BODIES_V1.md) §5 already records that in the format: `AndroidKeystoreSlotBodyV1` stores bytes Rust never produced and cannot open, and `wrap_suite_id` `0x0002` says so.

A cipher that runs on the platform side needs its plaintext there. These three exports are the consequence, and [ADR-0041](../adr/0041-the-android-keystore-slot-exchanges-root-bytes.md) is where the exception to §12 is argued rather than assumed. They are an addition, so they raise the minor ABI version to 2 and change nothing above.

```c
chur_status_t chur_vault_keystore_begin(chur_handle_t session,
                                        uint8_t *destination, size_t capacity,
                                        size_t *bytes_written);
chur_status_t chur_vault_keystore_commit(chur_handle_t session,
                                         const uint8_t *gcm_nonce,
                                         const uint8_t *wrapped_root_secret);
chur_status_t chur_vault_keystore_material(chur_handle_t runtime,
                                           uint8_t *destination, size_t capacity,
                                           size_t *bytes_written);
```

Enrollment is two calls with a platform operation between them:

1. `chur_vault_keystore_begin` allocates the slot id and the slot generation, because the §4 AAD binds both, and records the pending enrollment in the session. It writes nothing to the descriptor, so an enrollment the platform abandons leaves the vault exactly as it was. A second `begin` replaces the pending enrollment; a `commit` without one is `CONFLICT`;
2. the host asks the Keystore to encrypt the root under the AAD;
3. `chur_vault_keystore_commit` stores the nonce and the wrapped bytes, and the slot exists.

`chur_vault_keystore_material` takes a **runtime** handle rather than a session, because its result is what a caller needs *before* it can unlock. Nothing it returns is secret: every field is already stored in the clear in the descriptor. It returns one entry per enrolled slot across every identity the registry admits, in registry order, and names no identity: a caller tries each in turn, which is what [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) requires of a caller that must not learn which vault it opened.

Unlock factor `4` carries the unwrapped root itself rather than a value a slot body opens, and is verified the way every other factor is: the descriptor authenticates under the root, so a wrong or substituted value is `AUTHENTICATION_FAILED` and not corruption. A descriptor with no Keystore slot is skipped.

Both records are canonical bytes in a caller buffer, as §6.4 has every list be:

```text
ChurKeystoreEnrollmentV1
    alias_length      u32
    alias             bytes[alias_length]
    aad_length        u32
    aad               bytes[aad_length]
    root_secret       bytes[32]

ChurKeystoreMaterialV1
    count             u32
    entries           count × { alias_length: u32, alias: bytes,
                                aad_length: u32, aad: bytes,
                                gcm_nonce: bytes[12],
                                wrapped_root_secret: bytes[48] }
```

`root_secret` is the one field in this contract that carries a vault root, and every holder clears it: Rust zeroizes the encoded record, and the caller overwrites the buffer as soon as the wrap returns. The same rule applies to the secret passed to `chur_vault_unlock` under factor `4`. A host that will not accept that window has a supported answer, which is not to enroll the slot: [`../security/PROVISIONING.md`](../security/PROVISIONING.md) §5 already makes a device slot never the only slot.

### 6.7 The portable backup surface, ABI 1.3

[`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md) §1 makes the package portable across Android, iOS, and the CLI, and its §7 and §8 are long-running work over the whole vault. Two exports carry them, and both return an operation handle driven by `chur_operation_poll`, `_cancel`, and `_close`, exactly as an import or an export is:

```c
chur_status_t chur_backup_create(chur_handle_t session, int32_t destination_fd,
                                 chur_handle_t *out_operation);
chur_status_t chur_backup_restore(chur_handle_t runtime, int32_t source_fd,
                                  const uint8_t *password, uint32_t password_length,
                                  chur_handle_t *out_operation);
```

`ChurProgressV1` gains two `kind` values, `4` for a backup and `5` for a restore. §10's rule is unchanged: a snapshot carries bounded non-private numbers, and a byte count is one.

Three things differ from the §6.2 operations and each follows from the format.

Both descriptors must be **seekable** as well as open. §7 writes the public preamble before the records and learns the record count only after the inventory pass, and §8 walks record headers before it reads a payload. A pipe is therefore neither a destination nor a source, and an application that uploads a package writes it to a file and uploads that file.

`chur_backup_restore` takes the **runtime** rather than a session. A restore installs an identity, so at the moment it runs there may be no session and no vault at all; §8 step 2 obtains the credential from the package's own portable descriptor. The operation belongs to the runtime, so §14's runtime close tears it down and §4's session lock does not, because there is no session to lock.

The password crosses the boundary as bytes and is **not retained**. §12 keeps credentials inside Rust: the bytes are copied into a zeroizing buffer before the call returns and the caller's pointer is not held.

A restore refuses when the registry already holds the two identities [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §11 admits, and it installs nothing until the package authenticates whole.

### 6.8 The ciphertext sync inbox, ABI 1.4

The host can store downloaded opaque records while the vault is locked. Rust derives the local record name from the record bytes, bounds the inbox, and does not parse the record until unlock. The host passes the public random `vault_id`; it does not pass a private path or a key.

```c
chur_status_t chur_sync_stage(chur_handle_t runtime, const uint8_t vault_id[16],
                              uint8_t kind, uint64_t staged_at_ms,
                              const uint8_t *record, uint32_t record_length);
chur_status_t chur_sync_process(chur_handle_t session, uint64_t now_ms,
                                ChurSyncReportV1 *out_report);
```

Record kind `1` is an encrypted signed operation. Kind `2` is a signed checkpoint. `chur_sync_stage` is idempotent for identical bytes. It rejects one record above the 16 MiB response bound. The whole per-vault inbox remains bounded to the limits in [`../sync/SYNC_PROTOCOL_V1.md`](../sync/SYNC_PROTOCOL_V1.md) §7.

`chur_sync_process` authenticates and decrypts operations under the unlocked session. It removes an applied record, an exact replay, and a record that full validation rejects. It retains an operation with a missing device sequence, causal predecessor, or collection key. The report contains applied, duplicate, pending, and rejected counts. `first_rejection` is zero or the first stable `chur_status_t`; it contains no private text.

### 6.9 Sharing identity, ABI 1.5

`chur_sharing_identity` idempotently creates the vault's first ordinary device identity or returns the existing one. Ed25519 and X25519 private keys stay root-wrapped in the encrypted catalog and never cross the ABI. The same transaction commits the signed self-enrollment and its encrypted outer operation.

```c
chur_status_t chur_sharing_identity(chur_handle_t session,
                                    uint8_t *destination, size_t capacity,
                                    size_t *bytes_written);
```

The caller-owned result is a bounded public record: `version:u16 = 1`, `vault_id:bytes[16]`, `device_id:bytes[16]`, `signing_public_key:bytes[32]`, `hpke_public_key:bytes[32]`, then three `u32`-length-prefixed fields containing the 49-byte ASCII fingerprint, the 270-byte self-enrollment, and its canonical outer operation. Integers are big-endian. A retry returns identical bytes. A buffer that is too small receives no partial record and returns `RESOURCE_LIMIT_EXCEEDED`.

### 6.10 Share preparation, ABI 1.6

`chur_sharing_prepare` adds or updates one recipient and issues its HPKE collection-key grant. The input enrollment is the recipient's canonical 270-byte initial enrollment. `permissions` is the cumulative profile `1` (read), `3` (contribute), or `7` (manage members). `fingerprint_verified` is exactly `0` or `1`. Private identity and collection keys stay inside Rust.

```c
chur_status_t chur_sharing_prepare(chur_handle_t session,
                                   const uint8_t collection_id[16],
                                   const uint8_t *recipient_enrollment,
                                   uint32_t recipient_enrollment_length,
                                   uint8_t permissions,
                                   uint8_t fingerprint_verified,
                                   uint8_t *destination, size_t capacity,
                                   size_t *bytes_written);
```

The caller-owned output is `version:u16 = 1` followed by four `u32`-length-prefixed canonical fields: the collection membership record, its authenticated outer operation, the HPKE grant, and its authenticated outer operation. Integers are big-endian. A retry with the same accepted state returns identical bytes. A short buffer receives no partial record, sets `bytes_written` to zero, and returns `RESOURCE_LIMIT_EXCEEDED`.

## 7. Buffer ownership

Each function specifies:

- allocating side;
- writable/readable range;
- alignment;
- maximum capacity;
- whether bytes remain valid after return;
- whether zeroization is required;
- whether concurrent reuse is allowed.

Default data-plane policy: caller allocates a bounded mutable buffer, Rust writes authenticated plaintext, validity ends when caller reuses/frees it. Rust never retains the pointer after return.

## 8. Threads and blocking

Native FFI calls are synchronous. v1 exposes no callback-based call (§10). KMP wraps blocking work on a dedicated I/O dispatcher. Rust may use internal workers but must not call arbitrary Kotlin/Swift code while holding secret locks.

Thread affinity is a property of the handle type, not of the creating thread. No handle is bound to the thread that created it:

| Handle | Callable from | Concurrent calls on one handle |
| --- | --- | --- |
| `RuntimeHandle` | any thread | serialized inside Rust |
| `VaultSessionHandle` | any thread | serialized per session |
| `ObjectReaderHandle` | any thread, explicitly including a thread other than its creator | serialized per reader in v1; parallel only when `CHUR_CAP_CONCURRENT_READS` is set, which requires benchmarks and correctness tests first |
| `ImportHandle` | any thread | one call at a time; a second concurrent call returns `CONFLICT` |
| `ExportHandle` | any thread | one call at a time; a second concurrent call returns `CONFLICT` |
| `IntegrityScanHandle` | any thread | one call at a time; a second concurrent call returns `CONFLICT` |

`chur_operation_cancel` and every `*_close` are exempt: they are callable from any thread at any time, including while another call on the same handle is in flight, and they never wait on that call. The registry lock is therefore per slot and is never held across user work, so a Media3 loader thread and an `AVAssetResourceLoader` queue may both drive a reader they did not create.

### 8.1 Vault-level concurrency

The table above bounds calls on one handle. These rules bound a vault, and no other document restates them:

- one process opens a vault. The runtime takes an exclusive advisory lock on the descriptor file for the life of the session; a second process that cannot take it returns `CONFLICT` and attempts no slot unwrap, so a split Android process or a second launch cannot corrupt the catalog;
- one runtime per process (§14), so a second iOS scene or a second Android task shares the one session rather than opening its own. There is no per-scene vault state;
- catalog writes are serialized by one writer mutex per session. Reads run on the writer's connection in v1, which is why every reader handle is serialized in the table above. A read pool is a later change gated on `CHUR_CAP_CONCURRENT_READS` and does not alter this contract, because callers must already tolerate serialized reads;
- at most one unlock is in flight per runtime. A `chur_vault_unlock` arriving while another is running returns `CONFLICT` before deriving anything, so a double-tapped unlock button never starts two derivations;
- the Argon2id semaphore is 1 for the whole process. No two Argon2id evaluations ever run at once, whatever requested them: one evaluation is the largest allocation the runtime makes, and two at once on a low-memory device is the fastest way to be killed by the platform;
- several import, export, and scan operations may run at once. The bound is the concurrent `ImportTransaction` limit of [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §21;
- `ObjectReaderHandle` is therefore safe to call from a Media3 loader thread and from an `AVAssetResourceLoader` queue, including a thread that did not create it, with no lock added by the caller.

## 9. Cancellation

Long operations accept a cancellation handle/token or expose cancel functions. Lock cancellation has higher priority than ordinary caller cancellation.

Cancellation guarantees:

- no new plaintext after cancellation observed;
- partial ciphertext remains temp/journaled, not active;
- no progress snapshot advances after the terminal flag is set;
- exactly one terminal result;
- cancellation maps to `CANCELLED`, not corruption.

## 10. Progress reporting

v1 has no foreign callbacks. Rust never calls Kotlin, Swift, or Objective-C code, so there is no delivery thread, no re-entrancy rule, and no consumer-disappearance race to specify. The caller polls its own operation handle:

```text
chur_operation_poll(operation, out_progress) -> chur_status_t
```

- polling is synchronous and cheap: it takes the per-slot lock only long enough to copy a snapshot, and never waits on the operation;
- the caller polls on its own dispatcher or queue, at a rate it chooses, and republishes to the UI on the platform's main thread. The delivery thread is therefore the caller's, by construction;
- `ChurProgressV1` contains only bounded non-private numbers: operation kind, encrypted or plain bytes processed when safe, total bytes if known, stage code, terminal flag, and the terminal status;
- once the terminal flag is set the snapshot is frozen; every later poll returns the same terminal result until the handle is closed, so exactly one terminal result is observable;
- polling a stale-generation handle returns `SESSION_EXPIRED` rather than a partial snapshot;
- no filename, path, album, object ID, or real/decoy identity appears in progress.

A callback data plane would need a delivery-thread contract, a re-entrancy rule, and a release race against a disappearing consumer. Adding callbacks later is a minor-version addition behind a capability bit.

## 11. Errors

Every exported function that can fail returns `chur_status_t`, the `int32_t` status registered in [`../ERROR_MODEL.md`](../ERROR_MODEL.md), which owns every error name and value. `0` is success. Results never share the status channel: a byte count, a handle, or a projection is written through an out-parameter. Error strings are diagnostic-only and redacted, and this contract adds no code of its own.

An unrecognized value maps to `INTERNAL_FAILURE`.

The FFI artifacts build with `panic = "unwind"`; abort is not used. Every exported symbol wraps its whole body in `catch_unwind`, and one that returns `chur_status_t` converts a caught panic into `INTERNAL_FAILURE`.

The handshake exports of §2 have no status channel: they return a scalar and cannot fail. Each one instead returns a value the host already refuses, frozen by [ADR-0037](../adr/0037-contain-panics-in-channel-less-exports.md): `0` for either version component, an inclusive range whose minimum is `0xFFFF` and whose maximum is `0` for either format range, `0` for the capability mask, and `0` for the build flavor, which sets neither the release nor the debug bit. Containment is therefore visible to the host rather than silent, and no export is exempt. This is unconditional: every export, no "where applicable" exemption, verified by panic injection at each symbol. The panic payload is dropped inside the boundary and no payload text crosses it, the handle that owned the call is invalidated so a later call on it also fails, and a panic hook records a synthetic-reproduction diagnostic with no private values. Abort is rejected because it converts a contained, redactable failure into a process kill that skips session zeroization and removes the public shell along with the vault ([ADR-0016](../adr/0016-freeze-the-v1-c-abi.md)).

## 12. Secrets across FFI

Allowed only when unavoidable for a key-slot operation:

- bounded mutable byte buffers;
- exact length validation;
- no string conversion;
- no JSON/serialization;
- immediate best-effort clearing on foreign side;
- Rust secret wrapper on receipt;
- no callback echo.

Object/collection/root keys never return to application feature code.

## 13. File descriptor ownership

For each import/export call define whether Rust duplicates or consumes the descriptor. Preferred:

- platform opens descriptor;
- adapter passes it with explicit ownership flag;
- Rust duplicates when needed for asynchronous lifetime;
- original closes deterministically;
- non-seekable capability communicated explicitly;
- no integer descriptor persisted after operation.

## 14. Packaging

Android loads ABI-specific native libraries through the application shell/JNI adapter. iOS links one Rust static library/XCFramework instance. Duplicate Rust runtimes in one process are forbidden unless an ADR proves safety.

## 15. Tests

- ABI mismatch and unknown capabilities;
- invalid/null/misaligned/oversized buffers;
- double close and leaked handle cleanup;
- lock during read/import/export/verify/migrate;
- panic injection;
- poll after the terminal result, after close, and after lock;
- file descriptor closed early/non-seekable;
- cancellation at every stage;
- no secret values in errors/logs;
- Android/iOS byte-equivalent behavior.
