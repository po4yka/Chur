package dev.po4yka.chur.core.model

/**
 * The stable Chur error codes.
 *
 * `docs/ERROR_MODEL.md` is the sole registry of names and values, and its
 * numeric encoding is the `chur_status_t` of the C ABI. This enumeration is
 * that table on the Kotlin side. It allocates nothing: a value here that the
 * document does not carry is a defect, and a test asserts the two agree.
 *
 * Features must not branch on a localized message. They branch on [ChurStatus]
 * and on [Retry].
 */
public enum class ChurStatus(
    /** The `int32_t` the native library returns. */
    public val value: Int,
    /** The retry classification `ERROR_MODEL.md` records. */
    public val retry: Retry,
) {
    /** Credential or wrapped-root validation failed. */
    AUTHENTICATION_FAILED(100, Retry.YES),

    /** The Keystore or Keychain factor is absent, unenrolled, or locked out. */
    PLATFORM_KEY_UNAVAILABLE(101, Retry.YES),

    /** The Keystore or Keychain factor can no longer unwrap. */
    PLATFORM_KEY_INVALIDATED(102, Retry.NO),

    /** No usable daily-unlock slot remains. */
    RECOVERY_REQUIRED(103, Retry.NO),

    /** The operation requires an unlocked session. */
    VAULT_LOCKED(104, Retry.YES),

    /** The handle belongs to a locked or older session generation. */
    SESSION_EXPIRED(105, Retry.YES),

    /** Device-level protected storage is not accessible. */
    PROTECTED_DATA_UNAVAILABLE(106, Retry.YES),

    /** The device cannot allocate the approved Argon2id memory. */
    KDF_MEMORY_UNAVAILABLE(107, Retry.YES),

    /** The caller or a lock transition cancelled the work. */
    CANCELLED(200, Retry.YES),

    /** An argument, length, alignment, or range failed validation. */
    INVALID_INPUT(201, Retry.NO),

    /** A declared size, KDF parameter, or collection exceeds a parser limit. */
    RESOURCE_LIMIT_EXCEEDED(202, Retry.NO),

    /** The platform denied a requested resource. */
    PERMISSION_DENIED(203, Retry.YES),

    /** The opaque requested entity is absent. */
    NOT_FOUND(204, Retry.SOMETIMES),

    /** The operation conflicts with the current revision. */
    CONFLICT(205, Retry.YES),

    /** Two different signed records exist at one device sequence. */
    SYNC_CHAIN_FORK(206, Retry.NO),

    /** The offered sync state is below a locally accepted head. */
    SYNC_HEAD_ROLLBACK(207, Retry.NO),

    /** A recognized artifact carries an unsupported version. */
    UNSUPPORTED_VERSION(300, Retry.NO),

    /** The algorithm suite is not permitted. */
    UNSUPPORTED_SUITE(301, Retry.NO),

    /** A record has multiple or invalid encodings. */
    NON_CANONICAL_ENCODING(302, Retry.NO),

    /** The native library failed the ABI handshake. */
    ABI_INCOMPATIBLE(303, Retry.NO),

    /** Readable data must migrate before use. */
    MIGRATION_REQUIRED(304, Retry.YES),

    /** A migration could not commit safely. */
    MIGRATION_FAILED(305, Retry.SOMETIMES),

    /** Initialization or a transaction did not commit. */
    VAULT_INCOMPLETE(400, Retry.SOMETIMES),

    /** An authenticated vault structure is inconsistent. */
    VAULT_CORRUPT(401, Retry.NO),

    /** The final commit or another required record is missing. */
    OBJECT_INCOMPLETE(402, Retry.SOMETIMES),

    /** A tag, commitment, or structural check failed. */
    OBJECT_CORRUPT(403, Retry.NO),

    /** Catalog integrity or schema state failed. */
    CATALOG_CORRUPT(404, Retry.NO),

    /** Local input or output failed without proving corruption. */
    IO_FAILURE(500, Retry.SOMETIMES),

    /** The target volume is full, detached, or unwritable. */
    STORAGE_UNAVAILABLE(501, Retry.SOMETIMES),

    /** The import source cannot satisfy the required access pattern. */
    SOURCE_NOT_SEEKABLE(502, Retry.NO),

    /** A provider-backed source is not materialized locally. */
    SOURCE_DOWNLOAD_REQUIRED(503, Retry.YES),

    /** The transport failed. */
    NETWORK_FAILURE(600, Retry.YES),

    /** A redacted unexpected implementation failure. */
    INTERNAL_FAILURE(900, Retry.SOMETIMES),
    ;

    public companion object {
        /** The `chur_status_t` value of success. It is not a member of this enum. */
        public const val OK: Int = 0

        private val byValue: Map<Int, ChurStatus> = entries.associateBy { it.value }

        /**
         * The status a native value denotes.
         *
         * An unrecognized value folds into [INTERNAL_FAILURE] and is never
         * treated as success, retryable, or benign. `OK` is not a status, so it
         * folds too: a caller that reached this function had a failure.
         */
        public fun fromValue(value: Int): ChurStatus = byValue[value] ?: INTERNAL_FAILURE

        /** Whether a native value is one this build allocates. */
        public fun isAllocated(value: Int): Boolean = byValue.containsKey(value)
    }
}

/** The retry classification of a [ChurStatus]. */
public enum class Retry {
    /** The same call with the same input cannot succeed. */
    NO,

    /** The same call may succeed after the stated user or system action. */
    YES,

    /** Retry depends on the cause, which the caller must establish first. */
    SOMETIMES,
}
