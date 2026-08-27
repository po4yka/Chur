/*
 * chur.h - the stable C ABI of the Chur native library.
 *
 * This header is hand-written and is the authority for the C side of the
 * boundary. No binding generator produces it, and a generated header must not
 * become the canonical protocol definition (docs/interop/FFI_CONTRACT.md,
 * ADR-0016). A change here and a change in rust/crates/chur-ffi/src/lib.rs land
 * in the same commit.
 *
 * What this header declares today is the ABI handshake of FFI_CONTRACT.md
 * section 2 and the status vocabulary of docs/ERROR_MODEL.md. The control-plane
 * and data-plane functions are declared as they land.
 */

#ifndef CHUR_H
#define CHUR_H

#include <stdbool.h>
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

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* CHUR_H */
