# ADR-0069: Re-encrypt Objects After a Revocation

- **Status:** Accepted
- **Date:** 2026-10-21
- **Decision owners:** @po4yka
- **Related:** [`../sync/REVOCATION.md`](../sync/REVOCATION.md) §3.1 and §3.2, [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §26 and §55, [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §6.32, [`0012`](0012-import-journal-durability-ordering.md), [`0047`](0047-resume-rewrap-from-the-next-missing-envelope.md), [`0066`](0066-pad-object-containers-to-size-buckets.md)

## Context

A collection rotation rewraps each object key under the new epoch. The rewrap keeps the object key. Before this decision, the rewrap also kept the old envelope as a row with status `SUPERSEDED`. A member that the rotation removed, and that already opened an object, therefore kept the key of every container of that object. A copy of the object store, a stolen backup of the source device, or a later container of the same object stayed readable to that member for ever. The old envelope rows also stayed in the catalog and in its WAL, and each rotation added one row per object until the §21 envelope bound refused the next rewrap. A trashed object is not rewrapped at all, so its envelope stays under the old epoch.

## Decision

1. A rewrap deletes every earlier envelope of the object in the catalog transaction that inserts the new envelope. `secure_delete` overwrites the deleted cells. When the walk completes, the same transaction deletes any `SUPERSEDED` row that an earlier version left in the collection, and the device then runs a `TRUNCATE` checkpoint of the WAL. Each unlock repeats the checkpoint, so a blocked checkpoint is not permanent. No reader uses a superseded row, so no format changes.
2. After a completed rotation, the device that owns the rotation replaces each active or trashed object of its own collection whose envelope is above generation 1 or under an earlier epoch. The replacement is a new object with a fresh object key and a fresh object identifier. The original is read and written again through the import writer, so the reservation journal of ADR-0012 and the bucket padding of ADR-0066 apply. The copy keeps every metadata revision, the import time, the favourite flag, the album memberships, the tags, the extracted text, the video resume position, the Trash state and deadline, and the display assets. Its envelope is generation 1 at the current epoch, so only current members receive it through the ordinary publication.
3. The display assets are sealed under the fresh key before the activation, one at a time, and their rows are inserted in the activation transaction. The copy activates in the same transaction that moves the old object to step 1 of the deletion of `CATALOG_SCHEMA_V1.md` §14.1. The deletion then publishes a signed purge of the old identifier to the own-device and sharing logs, destroys the old envelope, truncates the WAL, and unlinks the old containers. A crash leaves the old object alone, or the complete copy with a deletion that the next unlock rolls forward. It never leaves two listable copies or a copy without the assets that the old object had. A display asset that cannot be read, or that is above 32 MiB, is not copied; the host shows the original in its place.
4. Re-encryption is automatic after every rotation and does not need a user request. It is an operation of the C ABI that the host starts after unlock, in the foreground, and that a lock cancels. It never runs inside an unlock. It holds the session for one chunk or one catalog step at a time, so the interface stays usable while a large object is copied, and the poll does not count as activity, so the pass never keeps the vault unlocked. The candidates come from catalog state, not from a cursor, so the next pass resumes after a cancellation or a crash. A cancellation or a failure abandons only the journal records of the copy, never another live import on the same session, and a locked session ends the pass as a cancellation, not as a failure of the object. The smallest objects come first. A cancelled copy restarts from its first chunk under new key material, because a dead import is never resumed; an object that needs an uninterrupted session therefore does not hold back the others.
5. An object that fails does not block the others. An original that does not authenticate is marked corrupt, which is durable and which the corrupt-object cleanup shows. Any other failure is retried by the same process only after a backoff that starts at one minute and doubles up to one day; a new process retries it at once. The host reads the pending and failed counts and shows one neutral line in Settings while any object cannot be re-encrypted.

## Alternatives considered

### Re-encryption only on user request

Rejected. The exposure that remains after a rewrap is the same for every revocation, and the user cannot judge which objects the removed member opened. A request also leaves the default state exposed.

### Re-encryption inside the unlock

Rejected. The first object of a step had to be admitted whatever its size, so one large video made each unlock take minutes, and a killed copy made every later unlock repeat it. Unlock latency would also differ between an identity with revoked members and one without.

### Keep the object identifier and replace its key

Rejected. The signed `CreateObject` and `RewrapObjectKey` operations commit to the object key. A new key under the same identifier needs a new frozen operation payload and a change in every receiver. A new object uses only the frozen operations.

### A durable per-object failure count

Rejected for now. It needs a new catalog table and therefore a catalog format version, a descriptor transition, and a change to the portable catalog export. The durable corrupt state covers the failure that never clears; every other failure is transient and needs only a backoff in the process.

### Keep superseded envelopes

Rejected. They add nothing that a reader uses, they keep a key wrap under an epoch the removed member holds, and they grow without bound.

## Consequences

### Positive

- after a rotation and its re-encryption, no stored envelope or container of the collection opens under a key that a removed member held;
- new objects and their copies are under the new epoch only, so a revoked device obtains no new ciphertext under a key it held;
- a removed member's copied object keys open only ciphertext that it copied before the replacement;
- unlock does the same work in every identity.

### Tradeoffs

- each object is read twice and written once on the owning device, and each current member downloads it again;
- object identifiers change, so a link to the old identifier and a manual album order are not carried;
- until the pass reaches an object, that object stays under its rewrapped key;
- a large object finishes only in a session long enough to copy it;
- received shared collections are not re-encrypted by the recipient. Their source vault re-encrypts them;
- the background work, and its storage and network effects, happen only in an identity that owns a completed rotation, that is, one that revoked a member. [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §5 records this signal.

## Security impact

Affected invariants: SEC-011, SEC-012, SEC-024. Revocation now removes access to every object that the source re-encrypts. It still cannot remove plaintext, keys, or ciphertext that a removed member already copied, and it does not change a backup made before the revocation.

## Compatibility impact

No container, envelope, operation, catalog, or vector format changes. The C ABI adds two exports and one operation kind at 7.2. A catalog that holds `SUPERSEDED` rows from an earlier version loses them at the end of its next rotation.

## Validation

- a rewrap leaves one envelope per object, and a completed walk removes a legacy superseded row;
- after re-encryption, the old object keys open no stored container, the old containers and object rows are gone, every original reads back unchanged, and the carried data is present;
- the own-device log and the sharing log carry a signed purge of each replaced identifier that peers received;
- a cancellation between chunks leaves no copy and no live journal, and the session is free between chunks;
- a kill during the copy, after the replacement commit, or after the key erasure each resumes to the same end state;
- a corrupt original and an unreadable original fail alone, back off, and are counted;
- an unreadable or oversized display asset does not fail its object;
- unlock does no re-encryption, and the C ABI pass reports progress and empties the counts.

## Follow-up

- show the progress of re-encryption in the revocation interface;
- measure the cost on a device against `PERFORMANCE_BUDGETS.md` §3.
