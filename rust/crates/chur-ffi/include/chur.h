/*
 * chur.h - the stable C ABI of the Chur native library.
 *
 * This header is hand-written and is the authority for the C side of the
 * boundary. No binding generator produces it, and a generated header must not
 * become the canonical protocol definition (docs/interop/FFI_CONTRACT.md,
 * ADR-0016). A change here and a change in rust/crates/chur-ffi/src/lib.rs land
 * in the same commit.
 *
 * This header declares the Phase-1 surface: the ABI handshake of
 * FFI_CONTRACT.md section 2, the status vocabulary of docs/ERROR_MODEL.md, the
 * control plane and data plane of section 6.2, the product surface of section
 * 6.5, the Android Keystore surface of section 6.6, and the portable backup
 * surface of section 6.7, the sync inbox surface of section 6.8, and the
 * sharing identity surface of section 6.9, and the share preparation surface
 * of section 6.10. Adding an
 * export raises the minor ABI version;
 * changing or removing one raises the major. The library reports 1.6.
 */

#ifndef CHUR_H
#define CHUR_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* -------------------------------------------------------------------------
 * Status
 *
 * The ABI representation of an error is int32_t. 0 is success and is not an
 * error code, every defined value is positive, 1-99 are permanently
 * unallocated, 700-899 and 1000 upward are reserved for future allocation, and
 * an unrecognized value maps to CHUR_INTERNAL_FAILURE. A code is added by
 * editing the table in docs/ERROR_MODEL.md in the same change that adds it
 * here.
 * ---------------------------------------------------------------------- */

typedef int32_t chur_status_t;

#define CHUR_OK 0

#define CHUR_AUTHENTICATION_FAILED 100
#define CHUR_PLATFORM_KEY_UNAVAILABLE 101
#define CHUR_PLATFORM_KEY_INVALIDATED 102
#define CHUR_RECOVERY_REQUIRED 103
#define CHUR_VAULT_LOCKED 104
#define CHUR_SESSION_EXPIRED 105
#define CHUR_PROTECTED_DATA_UNAVAILABLE 106
#define CHUR_KDF_MEMORY_UNAVAILABLE 107

#define CHUR_CANCELLED 200
#define CHUR_INVALID_INPUT 201
#define CHUR_RESOURCE_LIMIT_EXCEEDED 202
#define CHUR_PERMISSION_DENIED 203
#define CHUR_NOT_FOUND 204
#define CHUR_CONFLICT 205
#define CHUR_SYNC_CHAIN_FORK 206
#define CHUR_SYNC_HEAD_ROLLBACK 207

#define CHUR_UNSUPPORTED_VERSION 300
#define CHUR_UNSUPPORTED_SUITE 301
#define CHUR_NON_CANONICAL_ENCODING 302
#define CHUR_ABI_INCOMPATIBLE 303
#define CHUR_MIGRATION_REQUIRED 304
#define CHUR_MIGRATION_FAILED 305

#define CHUR_VAULT_INCOMPLETE 400
#define CHUR_VAULT_CORRUPT 401
#define CHUR_OBJECT_INCOMPLETE 402
#define CHUR_OBJECT_CORRUPT 403
#define CHUR_CATALOG_CORRUPT 404

#define CHUR_IO_FAILURE 500
#define CHUR_STORAGE_UNAVAILABLE 501
#define CHUR_SOURCE_NOT_SEEKABLE 502
#define CHUR_SOURCE_DOWNLOAD_REQUIRED 503

#define CHUR_NETWORK_FAILURE 600

#define CHUR_INTERNAL_FAILURE 900

/* -------------------------------------------------------------------------
 * Integrity states
 *
 * Range and complete-object verification are domain states, not errors. These
 * are the values chur_object_reader_verify_complete writes through out_state,
 * and the same values the catalog object row persists
 * (docs/format/CANONICAL_ENCODING_V1.md section 15.4). Proven corruption is a
 * lifecycle change and reaches a caller as CHUR_OBJECT_CORRUPT instead.
 * ---------------------------------------------------------------------- */

#define CHUR_INTEGRITY_UNVERIFIED 0x01
#define CHUR_INTEGRITY_VERIFYING 0x02
#define CHUR_INTEGRITY_RANGE_VERIFIED 0x03
#define CHUR_INTEGRITY_COMPLETE_VERIFIED 0x04
#define CHUR_INTEGRITY_INCOMPLETE 0x05
#define CHUR_INTEGRITY_QUARANTINED 0x06
#define CHUR_INTEGRITY_UNSUPPORTED 0x07
#define CHUR_INTEGRITY_MIGRATION_REQUIRED 0x08

/* -------------------------------------------------------------------------
 * Capability bits, returned by chur_capabilities().
 *
 * An unknown set bit is ignored and never enables behaviour. Bits 7 to 63 are
 * reserved and are zero in v1.
 * ---------------------------------------------------------------------- */

#define CHUR_CAP_DECOY_VAULT (UINT64_C(1) << 0)
#define CHUR_CAP_OBJECT_READER (UINT64_C(1) << 1)
#define CHUR_CAP_SEQUENTIAL_READER (UINT64_C(1) << 2)
#define CHUR_CAP_INTEGRITY_SCAN (UINT64_C(1) << 3)
#define CHUR_CAP_BACKUP_PACKAGE (UINT64_C(1) << 4)
#define CHUR_CAP_SYNC (UINT64_C(1) << 5)
#define CHUR_CAP_CONCURRENT_READS (UINT64_C(1) << 6)
#define CHUR_CAP_COLLECTION_SHARING (UINT64_C(1) << 7)

/* -------------------------------------------------------------------------
 * Build-flavor bits, returned by chur_build_flavor().
 *
 * A release application refuses a library with CHUR_FLAVOR_DEBUG_ASSERTIONS or
 * CHUR_FLAVOR_TEST_HOOKS set.
 * ---------------------------------------------------------------------- */

#define CHUR_FLAVOR_RELEASE (UINT32_C(1) << 0)
#define CHUR_FLAVOR_DEBUG_ASSERTIONS (UINT32_C(1) << 1)
#define CHUR_FLAVOR_TEST_HOOKS (UINT32_C(1) << 2)

/* -------------------------------------------------------------------------
 * Panic containment fallbacks
 *
 * FFI_CONTRACT.md section 11 has every export contain panics. A status-
 * returning export converts a caught panic into CHUR_INTERNAL_FAILURE. The
 * handshake exports below have no status channel, so ADR-0037 gives each one a
 * fallback the host already refuses: a major version that is not this ABI, an
 * inverted format range that contains no version, an empty capability mask, and
 * a flavor that is neither release nor debug. A host that reads one of these has
 * a library that panicked and must refuse it.
 * ---------------------------------------------------------------------- */

#define CHUR_PANIC_ABI_VERSION (UINT32_C(0))
#define CHUR_PANIC_FORMAT_MIN (UINT16_C(0xffff))
#define CHUR_PANIC_FORMAT_MAX (UINT16_C(0))
#define CHUR_PANIC_CAPABILITIES (UINT64_C(0))
#define CHUR_PANIC_BUILD_FLAVOR (UINT32_C(0))

/* -------------------------------------------------------------------------
 * Handshake
 *
 * Callable from any thread before runtime initialization. None of these can
 * fail, so none returns a status.
 * ---------------------------------------------------------------------- */

/*
 * None can fail. If a body panics, each returns the matching CHUR_PANIC_*
 * value above; chur_status_is_known returns false, which already fails closed.
 */
uint32_t chur_abi_version_major(void);
uint32_t chur_abi_version_minor(void);
uint64_t chur_capabilities(void);
uint16_t chur_object_format_min(void);
uint16_t chur_object_format_max(void);
uint16_t chur_key_slot_format_min(void);
uint16_t chur_key_slot_format_max(void);
uint32_t chur_build_flavor(void);

/*
 * Whether a status value is one this build allocates. A host uses it to tell a
 * genuine CHUR_INTERNAL_FAILURE from an unknown code it must fold into one.
 */
bool chur_status_is_known(int32_t value);

/* -------------------------------------------------------------------------
 * Handles
 *
 * chur_handle_t is uint64_t: the low 32 bits index a typed registry slot and
 * the high 32 bits carry that slot's generation. It is never a pointer and
 * never a business identifier. 0 is the null handle.
 *
 * Close is idempotent for every handle type: the first close releases the
 * resources and every later close of the same value returns CHUR_OK. Closing a
 * value this process never issued returns CHUR_INVALID_INPUT. A stale
 * generation returns CHUR_SESSION_EXPIRED, and no handle revives after a lock.
 * ---------------------------------------------------------------------- */

typedef uint64_t chur_handle_t;
typedef int32_t chur_status_t;

#define CHUR_NULL_HANDLE (UINT64_C(0))

/* Length of the page cursor of CATALOG_SCHEMA_V1.md section 16.2. */
#define CHUR_CURSOR_LEN 42
/* Length of ObjectProjectionV1 of CATALOG_SCHEMA_V1.md section 16.1. */
#define CHUR_PROJECTION_LEN 79
/* Length of the ChurPageV1 header of FFI_CONTRACT.md section 6.4. */
#define CHUR_PAGE_HEADER_LEN 63

/* Query scopes, CATALOG_SCHEMA_V1.md section 16.2. */
#define CHUR_SCOPE_TIMELINE 1
#define CHUR_SCOPE_ALBUM 2
#define CHUR_SCOPE_FAVORITES 3
#define CHUR_SCOPE_TAG 4
#define CHUR_SCOPE_SEARCH 5
#define CHUR_SCOPE_QUARANTINE 6

/* Query sorts, CATALOG_SCHEMA_V1.md section 16.2. */
#define CHUR_SORT_CAPTURE_DESC 1
#define CHUR_SORT_CAPTURE_ASC 2
#define CHUR_SORT_IMPORT_DESC 3

/* Unlock factors, KEY_SLOTS.md section 1. */
#define CHUR_FACTOR_PASSWORD 1
#define CHUR_FACTOR_RECOVERY 2
#define CHUR_FACTOR_APPLE_KEYCHAIN 3
/* The Keystore performs the unwrap, so this factor's secret is the unwrapped
   root itself rather than a value a slot body opens. See ADR-0041. */
#define CHUR_FACTOR_ANDROID_KEYSTORE 4

/* Operation kinds and stages, FFI_CONTRACT.md section 10. */
#define CHUR_OPERATION_IMPORT 1
#define CHUR_OPERATION_EXPORT 2
#define CHUR_OPERATION_INTEGRITY_SCAN 3
#define CHUR_OPERATION_BACKUP 4
#define CHUR_OPERATION_RESTORE 5

#define CHUR_STAGE_STARTING 1
#define CHUR_STAGE_RUNNING 2
#define CHUR_STAGE_COMMITTING 3
#define CHUR_STAGE_TERMINAL 4

/* Lock reasons, passed to chur_vault_lock and reported nowhere private. */
#define CHUR_LOCK_REASON_USER 1
#define CHUR_LOCK_REASON_TIMEOUT 2
#define CHUR_LOCK_REASON_BACKGROUND 3
#define CHUR_LOCK_REASON_PANIC 4

/* -------------------------------------------------------------------------
 * Control-plane records
 *
 * The caller allocates and owns every structure below. Rust reads them during
 * the call and retains no pointer afterwards. A string field is UTF-8 and is
 * not NUL-terminated; its length field bounds it.
 * ---------------------------------------------------------------------- */

typedef struct ChurRuntimeConfigV1 {
  const uint8_t *root_path;
  uint32_t root_path_length;
} ChurRuntimeConfigV1;

typedef struct ChurUnlockRequestV1 {
  uint8_t factor;
  uint8_t reserved[3];
  const uint8_t *secret;
  uint32_t secret_length;
} ChurUnlockRequestV1;

typedef struct ChurQueryV1 {
  uint8_t scope;
  uint8_t sort;
  uint16_t kinds;
  uint32_t limit;
  uint8_t scope_id[16];
  uint8_t cursor_present;
  uint8_t cursor[CHUR_CURSOR_LEN];
  const uint8_t *terms;
  uint32_t terms_length;
} ChurQueryV1;

typedef struct ChurObjectRefV1 {
  uint8_t object_id[16];
} ChurObjectRefV1;

typedef struct ChurImportRequestV1 {
  uint8_t seekable;
  uint8_t known_length_present;
  uint8_t media_class;
  uint8_t reserved;
  uint32_t width;
  uint32_t height;
  uint64_t duration_ms;
  uint64_t known_length;
  uint64_t capture_time_ms;
  uint8_t capture_time_present;
  uint8_t reserved_two[7];
  const uint8_t *content_type;
  uint32_t content_type_length;
  const uint8_t *original_filename;
  uint32_t original_filename_length;
} ChurImportRequestV1;

typedef struct ChurScanRequestV1 {
  uint8_t single_object;
  uint8_t reserved[7];
  uint8_t object_id[16];
} ChurScanRequestV1;

typedef struct ChurProgressV1 {
  uint32_t kind;
  uint32_t stage;
  uint64_t processed;
  uint64_t total;
  uint8_t terminal;
  uint8_t reserved[3];
  int32_t status;
} ChurProgressV1;

typedef struct ChurContentInfoV1 {
  uint64_t plaintext_size;
  uint8_t content_type[64];
  uint16_t media_kind;
  uint8_t byte_range_supported;
  uint8_t complete;
  uint8_t reserved[4];
} ChurContentInfoV1;

typedef struct ChurSyncReportV1 {
  uint64_t applied;
  uint64_t duplicates;
  uint64_t pending;
  uint64_t rejected;
  int32_t first_rejection;
  uint8_t reserved[4];
} ChurSyncReportV1;

/* -------------------------------------------------------------------------
 * Runtime and session
 * ---------------------------------------------------------------------- */

chur_status_t chur_runtime_open(const ChurRuntimeConfigV1 *config,
                                chur_handle_t *out_runtime);
chur_status_t chur_runtime_close(chur_handle_t runtime);
chur_status_t chur_vault_unlock(chur_handle_t runtime,
                                const ChurUnlockRequestV1 *request,
                                chur_handle_t *out_session);
chur_status_t chur_vault_lock(chur_handle_t session, uint32_t reason);
chur_status_t chur_session_close(chur_handle_t session);

/* Opaque locked staging and unlocked validation, FFI_CONTRACT.md section 6.8. */
#define CHUR_SYNC_RECORD_OPERATION 1
#define CHUR_SYNC_RECORD_CHECKPOINT 2
chur_status_t chur_sync_stage(chur_handle_t runtime, const uint8_t vault_id[16],
                              uint8_t kind, uint64_t staged_at_ms,
                              const uint8_t *record, uint32_t record_length);
chur_status_t chur_sync_process(chur_handle_t session, uint64_t now_ms,
                                ChurSyncReportV1 *out_report);

/* Idempotent local identity provisioning, FFI_CONTRACT.md section 6.9. */
chur_status_t chur_sharing_identity(chur_handle_t session,
                                    uint8_t *destination, size_t capacity,
                                    size_t *bytes_written);

/* Recipient membership and HPKE grant preparation, section 6.10. */
chur_status_t chur_sharing_prepare(chur_handle_t session,
                                   const uint8_t collection_id[16],
                                   const uint8_t *recipient_enrollment,
                                   uint32_t recipient_enrollment_length,
                                   uint8_t permissions,
                                   uint8_t fingerprint_verified,
                                   uint8_t *destination, size_t capacity,
                                   size_t *bytes_written);

/* -------------------------------------------------------------------------
 * Catalog queries
 *
 * The page is written into the caller's buffer as the canonical bytes of
 * FFI_CONTRACT.md section 6.4. bytes_written is set on every call, including
 * every failure, where it is set to 0. A buffer smaller than the page returns
 * CHUR_RESOURCE_LIMIT_EXCEEDED and writes nothing.
 * ---------------------------------------------------------------------- */

chur_status_t chur_catalog_query(chur_handle_t session, const ChurQueryV1 *query,
                                 uint8_t *destination, size_t capacity,
                                 size_t *bytes_written);

/* -------------------------------------------------------------------------
 * Operations
 *
 * An operation runs on an internal worker and the caller polls its own handle.
 * There is no callback, so there is no delivery thread and no re-entrancy rule.
 * chur_operation_cancel and every close are callable from any thread at any
 * time, including while another call on the same handle is in flight.
 *
 * Rust duplicates a descriptor it is given, so the caller closes its own on its
 * own schedule.
 * ---------------------------------------------------------------------- */

chur_status_t chur_import_begin(chur_handle_t session, int32_t source_fd,
                                const ChurImportRequestV1 *request,
                                chur_handle_t *out_import);
chur_status_t chur_export_begin(chur_handle_t session,
                                const ChurObjectRefV1 *object,
                                int32_t destination_fd,
                                chur_handle_t *out_export);
chur_status_t chur_integrity_scan_begin(chur_handle_t session,
                                        const ChurScanRequestV1 *request,
                                        chur_handle_t *out_scan);
chur_status_t chur_operation_poll(chur_handle_t operation,
                                  ChurProgressV1 *out_progress);
chur_status_t chur_operation_cancel(chur_handle_t operation);
chur_status_t chur_operation_close(chur_handle_t operation);

/* -------------------------------------------------------------------------
 * Object reader
 *
 * chur_object_reader_read_at never mixes an error with a byte count. A short
 * read is permitted at any offset, so the caller loops until it has the range
 * it needs or observes *bytes_written == 0, which with a success status means
 * end of authenticated plaintext and occurs only when offset == size. An offset
 * above size returns CHUR_INVALID_INPUT, never a zero-length success. On any
 * failure the whole destination holds unspecified bytes.
 * ---------------------------------------------------------------------- */

chur_status_t chur_object_reader_open(chur_handle_t session,
                                      const ChurObjectRefV1 *object,
                                      uint32_t stream_kind,
                                      chur_handle_t *out_reader);
chur_status_t chur_object_reader_size(chur_handle_t reader, uint64_t *out_size);
chur_status_t chur_object_reader_content_info(chur_handle_t reader,
                                              ChurContentInfoV1 *out_info);
chur_status_t chur_object_reader_read_at(chur_handle_t reader, uint64_t offset,
                                         uint8_t *destination, size_t capacity,
                                         size_t *bytes_written);
chur_status_t chur_object_reader_verify_complete(chur_handle_t reader,
                                                 uint32_t *out_state);
chur_status_t chur_object_reader_close(chur_handle_t reader);

/* -------------------------------------------------------------------------
 * The Phase-1 product surface, ABI 1.1, FFI_CONTRACT.md section 6.5.
 *
 * Section 6.2 is the boundary a host needs to open a vault and read from it.
 * These are the exports it needs to deliver Phase 1: without them no vault is
 * ever created, so none is ever unlocked, and three of the four destinations of
 * DESIGN.md section 10 have nothing to show.
 *
 * out_secret is 32 bytes and is the one place section 12's "allowed only when
 * unavoidable for a key-slot operation" applies here: a recovery secret must
 * reach the presentation of RECOVERY.md section 2, and a DeviceUnlockSecret
 * must reach the platform keystore. The host clears the buffer as soon as it is
 * done with it and never converts it to a string.
 * ---------------------------------------------------------------------- */

typedef struct ChurCreateRequestV1 {
  const uint8_t *password;
  uint32_t password_length;
  uint32_t memory_kib;
  uint32_t iterations;
  uint32_t parallelism;
} ChurCreateRequestV1;

/* Length of the device secret a slot operation hands back. */
#define CHUR_SECRET_LEN 32

/* The largest recovery phrase: 24 words of at most 8 characters, separated. */
#define CHUR_RECOVERY_PHRASE_MAX 216

chur_status_t chur_vault_present(chur_handle_t runtime, uint8_t *out_present);
chur_status_t chur_vault_create_begin(chur_handle_t runtime,
                                      const ChurCreateRequestV1 *request,
                                      chur_handle_t *out_creation);
/*
 * The recovery slot writes the phrase, not the 32 canonical bytes: the phrase
 * is a presentation encoding (RECOVERY.md section 2) and
 * CANONICAL_ENCODING_V1.md section 13 reserves every encoding for Rust, so a
 * host that received the bytes would have to implement BIP-39 twice. The bytes
 * are UTF-8 and are not NUL-terminated; the host clears the buffer once the
 * user has seen the phrase.
 */
chur_status_t chur_vault_creation_add_recovery_slot(chur_handle_t creation,
                                                    uint8_t *destination,
                                                    size_t capacity,
                                                    size_t *bytes_written);
chur_status_t chur_vault_creation_activate(chur_handle_t creation,
                                           chur_handle_t *out_session);
chur_status_t chur_vault_creation_abandon(chur_handle_t creation);

chur_status_t chur_vault_add_recovery_slot(chur_handle_t session,
                                           uint8_t *destination, size_t capacity,
                                           size_t *bytes_written);
chur_status_t chur_vault_add_device_slot(chur_handle_t session,
                                         const uint8_t *item_id,
                                         uint8_t *out_secret);
chur_status_t chur_vault_remove_slot(chur_handle_t session,
                                     const uint8_t *slot_id);
chur_status_t chur_vault_change_password(chur_handle_t session,
                                         const ChurUnlockRequestV1 *request);
chur_status_t chur_vault_slots(chur_handle_t session, uint8_t *destination,
                               size_t capacity, size_t *bytes_written);

/* -------------------------------------------------------------------------
 * The Android Keystore surface, ABI 1.2, FFI_CONTRACT.md section 6.6.
 *
 * This is the one key-slot family whose AEAD runs outside Rust: the Keystore
 * cipher performs it, so the enrollment is two calls with a platform operation
 * between them, and the unlock factor carries the unwrapped root. ADR-0041
 * records the exception and what the caller owes: the root_secret field of the
 * enrollment and the buffer passed to chur_vault_unlock must be overwritten as
 * soon as the platform call returns.
 *
 * chur_vault_keystore_material runs on a locked runtime, because its result is
 * what a caller needs before it can unlock. Nothing it returns is secret.
 *
 * Both records are canonical bytes in a caller buffer, as section 6.4 has every
 * list be: the enrollment is a length-prefixed alias, a length-prefixed AAD,
 * and 32 bytes of root secret; the material is a uint32 count followed by that
 * many entries of a length-prefixed alias, a length-prefixed AAD, a 12-byte
 * nonce, and a 48-byte wrapped root. Every integer is big-endian.
 * ---------------------------------------------------------------------- */

chur_status_t chur_vault_keystore_begin(chur_handle_t session,
                                        uint8_t *destination, size_t capacity,
                                        size_t *bytes_written);
chur_status_t chur_vault_keystore_commit(chur_handle_t session,
                                         const uint8_t *gcm_nonce,
                                         const uint8_t *wrapped_root_secret);
chur_status_t chur_vault_keystore_material(chur_handle_t runtime,
                                           uint8_t *destination, size_t capacity,
                                           size_t *bytes_written);

chur_status_t chur_object_set_favorite(chur_handle_t session,
                                       const ChurObjectRefV1 *object,
                                       uint8_t favorite);
chur_status_t chur_object_delete(chur_handle_t session,
                                 const ChurObjectRefV1 *object);
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

chur_status_t chur_derived_put(chur_handle_t session,
                               const ChurObjectRefV1 *object, uint32_t kind,
                               uint32_t width, uint32_t height,
                               const uint8_t *bytes, uint32_t length);
chur_status_t chur_derived_read(chur_handle_t session,
                                const ChurObjectRefV1 *object, uint32_t kind,
                                uint8_t *destination, size_t capacity,
                                size_t *bytes_written);

/* -------------------------------------------------------------------------
 * The portable backup surface, ABI 1.3
 *
 * FFI_CONTRACT.md section 6.7. Both calls return an operation handle and are
 * driven with chur_operation_poll, _cancel, and _close, exactly as an import
 * or an export is.
 *
 * Both descriptors must be seekable. BACKUP_FORMAT_V1.md section 7 writes the
 * public preamble before the records and learns the record count only after
 * the inventory pass, and section 8 seeks over record headers before it reads
 * a payload, so a pipe is neither a destination nor a source.
 *
 * chur_backup_restore takes the runtime rather than a session: a restore
 * installs an identity, so at the moment it runs there may be no session and
 * no vault at all, and the credential comes from the package's own portable
 * descriptor.
 * ---------------------------------------------------------------------- */

chur_status_t chur_backup_create(chur_handle_t session, int32_t destination_fd,
                                 chur_handle_t *out_operation);
chur_status_t chur_backup_restore(chur_handle_t runtime, int32_t source_fd,
                                  const uint8_t *password,
                                  uint32_t password_length,
                                  chur_handle_t *out_operation);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* CHUR_H */
