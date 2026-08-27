//! Stable error codes.
//!
//! `docs/ERROR_MODEL.md` is the sole registry of Chur error names and values.
//! This module is that table as Rust. No other module allocates a value, and
//! adding one here without adding it there, and to the FFI header, is a defect.
//!
//! The ABI representation is `int32_t`, `0` is success and is not an error
//! code, every defined value is positive, `1` to `99` are permanently
//! unallocated, and an unrecognized value maps to [`ChurStatus::InternalFailure`]
//! rather than to success.

use core::fmt;

/// Retry classification of a status, as the "Retryable" column of
/// `docs/ERROR_MODEL.md` records it.
///
/// The classification is advice for a caller, never a licence to loop: the
/// retry policy of that document forbids automatic retry of authentication and
/// key-derivation failures whatever this value says.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Retry {
    /// The same call with the same input cannot succeed.
    No,
    /// The same call may succeed after the stated user or system action.
    Yes,
    /// Retry depends on the cause, which the caller must establish first.
    Sometimes,
}

/// The ABI value of a successful call, `CHUR_OK`.
///
/// It is not a member of [`ChurStatus`]: success is the absence of an error,
/// and giving it a variant would let a caller pass it where a failure is
/// expected.
pub const CHUR_OK: i32 = 0;

macro_rules! chur_status {
    ($( $(#[$meta:meta])* $variant:ident = $value:literal, $name:literal, $retry:ident; )+) => {
        /// A stable Chur error code.
        ///
        /// Values are ABI, not persisted bytes. A value never appears inside an
        /// encrypted record unless a versioned format explicitly stores an
        /// integrity state, which no v1 format does.
        /// The derived ordering is the numeric one: variants are declared in
        /// ascending value order and a test asserts that they stay that way.
        #[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
        #[non_exhaustive]
        #[repr(i32)]
        pub enum ChurStatus {
            $( $(#[$meta])* $variant = $value, )+
        }

        impl ChurStatus {
            /// Every allocated status, in ascending numeric order.
            pub const ALL: &'static [ChurStatus] = &[ $( ChurStatus::$variant, )+ ];

            /// The ABI value of this status.
            #[must_use]
            pub const fn as_i32(self) -> i32 {
                self as i32
            }

            /// The registered name, as `docs/ERROR_MODEL.md` spells it.
            #[must_use]
            pub const fn name(self) -> &'static str {
                match self {
                    $( ChurStatus::$variant => $name, )+
                }
            }

            /// The retry classification of this status.
            #[must_use]
            pub const fn retry(self) -> Retry {
                match self {
                    $( ChurStatus::$variant => Retry::$retry, )+
                }
            }

            /// The status an ABI value denotes.
            ///
            /// An unrecognized value, `CHUR_OK` and every negative value
            /// included, maps to [`ChurStatus::InternalFailure`]. A caller that
            /// receives an unknown code must never treat it as success,
            /// retryable, or benign, so this function fails closed rather than
            /// returning an option the caller might unwrap into a default.
            #[must_use]
            pub const fn from_i32(value: i32) -> ChurStatus {
                match value {
                    $( $value => ChurStatus::$variant, )+
                    _ => ChurStatus::InternalFailure,
                }
            }

            /// Whether an ABI value is one this build allocates.
            ///
            /// Use it to tell a genuine [`ChurStatus::InternalFailure`] from an
            /// unknown code that [`ChurStatus::from_i32`] folded into it.
            #[must_use]
            pub const fn is_allocated(value: i32) -> bool {
                matches!(value, $( $value )|+)
            }
        }
    };
}

chur_status! {
    /// Credential or wrapped-root validation failed.
    ///
    /// Wrong password, wrong recovery secret, damaged slot ciphertext, damaged
    /// slot AAD, a slot bound to another vault, and an absent real or decoy
    /// credential are one external result. Nothing in the error distinguishes
    /// them.
    AuthenticationFailed = 100, "AUTHENTICATION_FAILED", Yes;
    /// The Keystore or Keychain factor is absent, unenrolled, or locked out.
    PlatformKeyUnavailable = 101, "PLATFORM_KEY_UNAVAILABLE", Yes;
    /// The Keystore or Keychain factor can no longer unwrap.
    PlatformKeyInvalidated = 102, "PLATFORM_KEY_INVALIDATED", No;
    /// No usable daily-unlock slot remains.
    RecoveryRequired = 103, "RECOVERY_REQUIRED", No;
    /// The operation requires an unlocked session.
    VaultLocked = 104, "VAULT_LOCKED", Yes;
    /// The handle belongs to a locked or older session generation.
    SessionExpired = 105, "SESSION_EXPIRED", Yes;
    /// Device-level protected storage is not accessible.
    ProtectedDataUnavailable = 106, "PROTECTED_DATA_UNAVAILABLE", Yes;
    /// The device cannot allocate the memory the approved Argon2id profile
    /// requires.
    ///
    /// It is a device-resource state, decided before any credential is used, so
    /// it reveals nothing about which slots exist. Never retry it with reduced
    /// parameters: `docs/security/PASSWORD_PROFILE.md` section 6 forbids that.
    KdfMemoryUnavailable = 107, "KDF_MEMORY_UNAVAILABLE", Yes;

    /// The caller or a lock transition cancelled the work.
    Cancelled = 200, "CANCELLED", Yes;
    /// An argument, length, alignment, or range failed validation.
    InvalidInput = 201, "INVALID_INPUT", No;
    /// A declared size, KDF parameter, or collection exceeds a parser limit.
    ResourceLimitExceeded = 202, "RESOURCE_LIMIT_EXCEEDED", No;
    /// The platform denied a requested resource.
    PermissionDenied = 203, "PERMISSION_DENIED", Yes;
    /// The opaque requested entity is absent.
    NotFound = 204, "NOT_FOUND", Sometimes;
    /// The operation conflicts with the current revision.
    Conflict = 205, "CONFLICT", Yes;
    /// Two different signed records exist at one device sequence.
    SyncChainFork = 206, "SYNC_CHAIN_FORK", No;
    /// The offered sync state is below a locally accepted head.
    SyncHeadRollback = 207, "SYNC_HEAD_ROLLBACK", No;

    /// A recognized artifact carries an unsupported version.
    UnsupportedVersion = 300, "UNSUPPORTED_VERSION", No;
    /// The algorithm suite is not permitted.
    UnsupportedSuite = 301, "UNSUPPORTED_SUITE", No;
    /// A record has multiple or invalid encodings.
    NonCanonicalEncoding = 302, "NON_CANONICAL_ENCODING", No;
    /// The native library failed the ABI handshake.
    AbiIncompatible = 303, "ABI_INCOMPATIBLE", No;
    /// Readable data must migrate before use.
    MigrationRequired = 304, "MIGRATION_REQUIRED", Yes;
    /// A migration could not commit safely.
    MigrationFailed = 305, "MIGRATION_FAILED", Sometimes;

    /// Initialization or a transaction did not commit.
    VaultIncomplete = 400, "VAULT_INCOMPLETE", Sometimes;
    /// An authenticated vault structure is inconsistent.
    VaultCorrupt = 401, "VAULT_CORRUPT", No;
    /// The final commit or another required record is missing.
    ObjectIncomplete = 402, "OBJECT_INCOMPLETE", Sometimes;
    /// A tag, commitment, or structural check failed.
    ObjectCorrupt = 403, "OBJECT_CORRUPT", No;
    /// Catalog integrity or schema state failed.
    CatalogCorrupt = 404, "CATALOG_CORRUPT", No;

    /// Local input or output failed without proving corruption.
    IoFailure = 500, "IO_FAILURE", Sometimes;
    /// The target volume is full, detached, or unwritable.
    StorageUnavailable = 501, "STORAGE_UNAVAILABLE", Sometimes;
    /// The import source cannot satisfy the required access pattern.
    SourceNotSeekable = 502, "SOURCE_NOT_SEEKABLE", No;
    /// A provider-backed source is not materialized locally.
    SourceDownloadRequired = 503, "SOURCE_DOWNLOAD_REQUIRED", Yes;

    /// The transport failed.
    NetworkFailure = 600, "NETWORK_FAILURE", Yes;

    /// A redacted unexpected implementation failure.
    InternalFailure = 900, "INTERNAL_FAILURE", Sometimes;
}

impl fmt::Display for ChurStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

impl From<ChurStatus> for i32 {
    fn from(status: ChurStatus) -> i32 {
        status.as_i32()
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn every_value_round_trips_through_the_abi_representation() {
        for status in ChurStatus::ALL {
            assert_eq!(ChurStatus::from_i32(status.as_i32()), *status);
            assert!(ChurStatus::is_allocated(status.as_i32()));
        }
    }

    #[test]
    fn values_are_unique_positive_and_ascending() {
        let mut previous = 0;
        for status in ChurStatus::ALL {
            let value = status.as_i32();
            assert!(value > previous, "{} breaks ascending order", status.name());
            previous = value;
        }
    }

    #[test]
    fn success_and_the_reserved_low_block_are_not_allocated() {
        assert!(!ChurStatus::is_allocated(CHUR_OK));
        for value in 1..=99 {
            assert!(!ChurStatus::is_allocated(value), "{value} is allocated");
        }
    }

    #[test]
    fn an_unknown_value_fails_closed() {
        for value in [-1, i32::MIN, CHUR_OK, 42, 700, 899, 1000, i32::MAX] {
            assert_eq!(ChurStatus::from_i32(value), ChurStatus::InternalFailure);
        }
        assert!(!ChurStatus::is_allocated(700));
        assert!(!ChurStatus::is_allocated(899));
    }

    #[test]
    fn the_derived_ordering_is_the_numeric_ordering() {
        let mut sorted = ChurStatus::ALL.to_vec();
        sorted.sort_unstable();
        assert_eq!(sorted, ChurStatus::ALL);
        assert!(ChurStatus::AuthenticationFailed < ChurStatus::InternalFailure);
    }

    #[test]
    fn the_registry_holds_the_documented_count() {
        assert_eq!(ChurStatus::ALL.len(), 33);
    }
}
