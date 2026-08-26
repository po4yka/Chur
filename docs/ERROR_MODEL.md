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

| Code | Meaning | Retryable | User action |
| --- | --- | ---: | --- |
| `AUTHENTICATION_FAILED` | Credential or wrapped-root validation failed | Yes | retry credential or recovery |
| `PLATFORM_KEY_INVALIDATED` | Keystore/Keychain factor can no longer unwrap | No | use password/recovery and re-enroll |
| `RECOVERY_REQUIRED` | no usable daily-unlock slot remains | No | begin recovery flow |
| `SESSION_EXPIRED` | handle belongs to a locked/older generation | Yes | reopen after unlock |
| `CANCELLED` | caller or lock transition cancelled work | Yes | retry intentionally |
| `RESOURCE_LIMIT_EXCEEDED` | declared size/KDF/collection exceeds policy | No | reject input or use supported parameters |
| `UNSUPPORTED_VERSION` | recognized artifact has unsupported version | No | upgrade or migrate |
| `UNSUPPORTED_SUITE` | algorithm suite is not permitted | No | migrate with supported client |
| `NON_CANONICAL_ENCODING` | record has multiple or invalid encodings | No | reject source |
| `VAULT_INCOMPLETE` | initialization or transaction did not commit | Sometimes | recover or resume |
| `VAULT_CORRUPT` | authenticated vault structure is inconsistent | No | restore or repair |
| `OBJECT_INCOMPLETE` | final commit or required records are missing | Sometimes | resume transfer/import |
| `OBJECT_CORRUPT` | tag, commitment, or structural check failed | No | restore object |
| `CATALOG_CORRUPT` | catalog integrity or schema state failed | No | repair/restore |
| `MIGRATION_REQUIRED` | readable data must migrate before use | Yes | run migration |
| `MIGRATION_FAILED` | migration could not commit safely | Sometimes | preserve checkpoint; diagnose |
| `CONFLICT` | operation conflicts with current revision | Yes | refresh and merge |
| `IO_FAILURE` | local I/O failed without proving corruption | Sometimes | retry or free storage |
| `NETWORK_FAILURE` | transport failed | Yes | retry with backoff |
| `PERMISSION_DENIED` | platform denied requested resource | Yes | grant/select resource |
| `NOT_FOUND` | opaque requested entity is absent | Sometimes | refresh state |
| `INTERNAL_FAILURE` | redacted unexpected implementation failure | Sometimes | retry; collect safe diagnostics |

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
