# Chur Error Model

> **Status:** Proposed normative application and FFI error taxonomy

Chur errors must be actionable for code, safe for users, stable across FFI, and non-revealing to attackers. Internal causes may be richer than externally visible errors, but private values must never enter messages, logs, analytics, or crash reports.

## Principles

1. Error codes are stable; human messages are not protocol fields.
2. Authentication failures do not reveal which slot, vault identity, or integrity check failed.
3. Untrusted inputs are rejected before expensive work or allocation.
4. Cancellation is not reported as corruption.
5. A retryable transport error is distinct from a permanent format error.
6. Rust panics never cross FFI.
7. Unknown error codes fail closed and map to a generic internal failure.

## Stable categories

This table is the sole registry of Chur error names and values. `ARCHITECTURE.md`, `CRYPTOGRAPHY.md`, `ANDROID.md`, `IOS.md`, and the interop contracts map their conditions onto these codes and must not define a code of their own. The numeric value is the C ABI representation defined in the next section.

| Value | Code | Meaning | Retryable | User action |
| ---: | --- | --- | ---: | --- |
| 100 | `AUTHENTICATION_FAILED` | Credential or wrapped-root validation failed | Yes | retry credential or recovery |
| 101 | `PLATFORM_KEY_UNAVAILABLE` | Keystore/Keychain factor is absent, unenrolled, or locked out | Yes | authenticate again or use password/recovery |
| 102 | `PLATFORM_KEY_INVALIDATED` | Keystore/Keychain factor can no longer unwrap | No | use password/recovery and re-enroll |
| 103 | `RECOVERY_REQUIRED` | no usable daily-unlock slot remains | No | begin recovery flow |
| 104 | `VAULT_LOCKED` | operation requires an unlocked session | Yes | unlock |
| 105 | `SESSION_EXPIRED` | handle belongs to a locked/older generation | Yes | reopen after unlock |
| 106 | `PROTECTED_DATA_UNAVAILABLE` | device-level protected storage is not accessible | Yes | unlock the device and retry |
| 200 | `CANCELLED` | caller or lock transition cancelled work | Yes | retry intentionally |
| 201 | `INVALID_INPUT` | argument, length, alignment, or range failed validation | No | correct the call |
| 202 | `RESOURCE_LIMIT_EXCEEDED` | declared size/KDF/collection exceeds policy | No | reject input or use supported parameters |
| 203 | `PERMISSION_DENIED` | platform denied requested resource | Yes | grant/select resource |
| 204 | `NOT_FOUND` | opaque requested entity is absent | Sometimes | refresh state |
| 205 | `CONFLICT` | operation conflicts with current revision | Yes | refresh and merge |
| 300 | `UNSUPPORTED_VERSION` | recognized artifact has unsupported version | No | upgrade or migrate |
| 301 | `UNSUPPORTED_SUITE` | algorithm suite is not permitted | No | migrate with supported client |
| 302 | `NON_CANONICAL_ENCODING` | record has multiple or invalid encodings | No | reject source |
| 303 | `ABI_INCOMPATIBLE` | native library failed the handshake in `interop/FFI_CONTRACT.md` §2 | No | update the application |
| 304 | `MIGRATION_REQUIRED` | readable data must migrate before use | Yes | run migration |
| 305 | `MIGRATION_FAILED` | migration could not commit safely | Sometimes | preserve checkpoint; diagnose |
| 400 | `VAULT_INCOMPLETE` | initialization or transaction did not commit | Sometimes | recover or resume |
| 401 | `VAULT_CORRUPT` | authenticated vault structure is inconsistent | No | restore or repair |
| 402 | `OBJECT_INCOMPLETE` | final commit or required records are missing | Sometimes | resume transfer/import |
| 403 | `OBJECT_CORRUPT` | tag, commitment, or structural check failed | No | restore object |
| 404 | `CATALOG_CORRUPT` | catalog integrity or schema state failed | No | repair/restore |
| 500 | `IO_FAILURE` | local I/O failed without proving corruption | Sometimes | retry or free storage |
| 501 | `STORAGE_UNAVAILABLE` | target volume is full, detached, or unwritable | Sometimes | free space or choose another destination |
| 502 | `SOURCE_NOT_SEEKABLE` | import source cannot satisfy the required access pattern | No | copy the source or choose another |
| 503 | `SOURCE_DOWNLOAD_REQUIRED` | provider-backed source is not materialized locally | Yes | allow the provider download and retry |
| 600 | `NETWORK_FAILURE` | transport failed | Yes | retry with backoff |
| 900 | `INTERNAL_FAILURE` | redacted unexpected implementation failure | Sometimes | retry; collect safe diagnostics |

## Numeric encoding and the C ABI

- the ABI representation of an error is `int32_t` (`i32` in Rust), named `chur_status_t`;
- `0` is success (`CHUR_OK`) and is not an error code;
- every defined value is positive; a negative value is never emitted and must be treated as unknown;
- `1`-`99` are permanently unallocated, so a caller that returns a boolean or a POSIX `errno` in the status channel cannot land on a defined code;
- values are allocated in blocks of 100 by domain; an unallocated value inside an allocated block is reserved for that domain;
- `700`-`899` and `1000`-`2147483647` are reserved for future allocation by this document;
- allocation is append-only: a retired code keeps its value and is never reused for another meaning;
- an unrecognized value maps to `INTERNAL_FAILURE` and must never be treated as success, retryable, or benign;
- a code is added by editing the table above in the same change that adds it to the FFI header. No other document allocates a value.

Error codes are ABI, not persisted bytes: a value never appears inside an encrypted record unless a versioned format explicitly stores an integrity state.

## Layer mapping

```text
Rust internal error
    ↓ classify and redact
stable native error code + safe metadata
    ↓ FFI adapter
KMP domain error
    ↓ feature policy
user-facing copy / retry / navigation
```

Platform exceptions are normalized before reaching features. Features must not branch on localized message text.

## Safe metadata

An error may carry bounded, non-private fields such as:

- stable operation code;
- format version;
- supported/received suite numeric identifier;
- chunk index only when it cannot reveal a user-facing identifier;
- retry-after duration;
- whether recovery is possible;
- safe platform capability code.

It must not carry:

- filename, path, album, EXIF, GPS, search query;
- password, recovery secret, salt, nonce, key, wrapped-key bytes;
- decrypted manifest or catalog row;
- stable real/decoy identity;
- raw untrusted input.

## Authentication errors

The external result for these cases is intentionally equivalent:

```text
wrong password
wrong recovery secret
damaged slot ciphertext
damaged slot AAD
slot points to a different vault
real credential not present
decoy credential not present
```

Internal diagnostics may distinguish causes only in explicitly local, redacted developer instrumentation using synthetic data.

## Integrity states versus errors

Range verification and complete-object verification are domain states:

```text
VerifiedRange
CompleteVerifiedObject
Incomplete
Corrupt
Unsupported
```

A caller requesting playback may accept `VerifiedRange`; export, backup, or migration requires `CompleteVerifiedObject`.

## Retry policy

- never automatically retry authentication or KDF failures;
- use bounded exponential backoff for network failures;
- do not retry authenticated corruption without a different source;
- resume incomplete immutable object transfer only after validating journal state;
- re-open a session after `SESSION_EXPIRED` rather than reviving a handle;
- do not retry `RESOURCE_LIMIT_EXCEEDED` with the same input.

## Logging severity

| Condition | Level | Notes |
| --- | --- | --- |
| expected cancellation | debug/none | no stack trace by default |
| authentication failure | info/none | rate-limited; no slot identity |
| transient I/O/network | warning | safe operation code only |
| integrity failure | error | security event; no private context |
| invariant violation | fatal/error | terminate operation, preserve evidence safely |
| panic contained at FFI | fatal/error | generic code; synthetic reproduction required |

## Wire and persistence compatibility

Error codes are not persisted inside encrypted content unless a versioned format explicitly includes an integrity state. Sync protocol errors use separate versioned wire codes. Unknown remote codes map to `INTERNAL_FAILURE` or `NETWORK_FAILURE` without weakening validation.

## Testing

Tests must verify:

- every internal error maps to one stable code;
- messages and metadata contain no injected input;
- real and decoy failures are indistinguishable externally;
- cancellation does not commit partial state;
- unknown codes fail closed;
- localized UI copy does not affect program flow.
