# Plaintext Lifecycle

> **Status:** Proposed normative handling policy

Plaintext is a toxic, temporary resource. Chur minimizes where it appears, how long it exists, and which subsystem can retain it.

## 1. Inventory

Potential plaintext locations:

| Location | Allowed | Lifetime/policy |
| --- | ---: | --- |
| Rust secret buffers | Yes | unlocked operation/session only; zeroize |
| Rust media buffers | Yes | bounded operation; overwrite/reuse |
| JNI/direct buffers | Yes | bounded call; explicit ownership |
| Kotlin `ByteArray` | Avoid | small control data only; clear best-effort |
| Swift `Data` | Avoid | small bounded interop only |
| decoded image surfaces | Yes | session-scoped cache; clear on lock |
| Media3/AVFoundation buffers | Yes | player lifetime; invalidate on lock |
| private catalog pages in memory | Yes | DB connection lifetime |
| scratch files | Exceptional | protected, backup-excluded, random-named, promptly deleted |
| Room/DataStore/public DB | No | private data forbidden |
| logs/analytics/crash reports | No | forbidden |
| clipboard | No by default | explicit user export only with warning/policy |
| notifications/widgets/search | No | forbidden |
| app-switcher snapshot | No | privacy overlay |

## 2. Unlock

During unlock:

- password bytes enter Rust once when possible;
- derived KEK and candidate root are scoped to the operation;
- no private catalog opens until root/vault context authenticates;
- failed candidates are cleared;
- platform APIs receive only the bytes required for their key-slot operation;
- feature state receives only an opaque session handle.

## 3. Import

Preferred path:

```text
platform picker/file descriptor
    → bounded read buffer
    → platform probe/derivative step when required
    → Rust encryptor
    → temporary ciphertext
    → authenticated final commit
```

Requirements:

- do not create an unprotected duplicate solely for import;
- use streaming and bounded buffers;
- keep decoded derivatives only long enough to encrypt them;
- cancel promptly on lock;
- clean partial plaintext and temporary ciphertext according to journal state;
- never delete source before durable encrypted commit.

## 4. Viewing and playback

- decrypt only requested image/byte ranges;
- authenticate before releasing each range;
- use a separate private image loader/cache;
- scope cache keys to session generation;
- stop players and invalidate readers on lock;
- avoid exporting private file paths to UI state;
- clear accessibility semantics when covered/locked.

## 5. Scratch files

Some platform codecs, editors, share targets, or APIs require a file URL. Scratch plaintext is allowed only under an explicit policy:

- app-private directory;
- strongest compatible file-protection class;
- random opaque filename and extension only when required;
- excluded from backup and indexing;
- minimal permissions;
- bounded lifetime and size;
- deletion immediately after consumer completion;
- startup cleanup of abandoned entries;
- no claim of physical overwrite on flash.

A scratch journal records opaque cleanup state without private filenames.

## 6. Export

```text
Rust verified reader
    → protected destination chosen by user
    → platform save/share flow
```

The user is explicitly leaving the vault boundary. Chur should state that recipients, Photos, Files, editors, and share extensions may persist plaintext according to their own policies.

## 7. Background behavior

Background work without an unlocked session is ciphertext-only. It may:

- upload/download encrypted containers;
- reconcile opaque transfer state;
- verify transport checksums that reveal no plaintext;
- schedule future work.

It must not:

- derive/open root keys;
- generate plaintext thumbnails;
- inspect private metadata;
- create plaintext scratch;
- keep a session alive solely for convenience.

## 8. Lock sequence

1. transition session to cancelling;
2. prevent new private operations;
3. stop players/decoders;
4. invalidate handle generation;
5. close private catalog;
6. zeroize root/collection/object keys;
7. clear decoded caches and feature projections;
8. delete or quarantine scratch according to operation policy;
9. show public/neutral UI.

Lock completion should be measurable and covered by performance budgets.

## 9. Memory limitations

`zeroize` and mutable-buffer overwrites reduce exposure but cannot prove erasure from:

- compiler/runtime copies;
- immutable strings;
- GC heap history;
- OS swap/compressed memory;
- GPU/display surfaces;
- codec-internal buffers;
- hostile kernel snapshots.

Avoid creating copies rather than relying on later erasure.

## 10. Diagnostics

Private values are forbidden from:

- `Debug`/`Display`;
- exception text;
- coroutine/task names;
- signposts/traces;
- screenshots in bug reports;
- analytics properties;
- crash breadcrumbs;
- network debugging proxies.

Synthetic fixture identifiers may be logged only in test builds.

## 11. Verification

Tests should inspect:

- public DB and preferences after private workflows;
- app files/backups after scratch use;
- logs/crashes for injected canary values;
- cache contents before/after lock;
- stale FFI readers;
- process-death restoration;
- background tasks while locked;
- platform snapshots and notifications.
