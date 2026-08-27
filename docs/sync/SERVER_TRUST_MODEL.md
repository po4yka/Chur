# Server Trust Model

> **Status:** Proposed future-sync threat model; not part of the initial local release

Chur treats the sync/backup server as untrusted for confidentiality and content integrity. The server is a storage and relay service, not a cryptographic authority.

## 1. Server capabilities

Assume the server can:

- read, copy, modify, delete, reorder, delay, replay, or omit stored records;
- return different histories to different devices;
- observe account, IP, timing, object count, and transfer sizes;
- correlate device sessions and recipients;
- reject or throttle requests;
- compromise its authentication database or application logic;
- collude with a revoked or malicious client.

The server does not know valid user password/recovery/root/collection/object keys under the intended design.

## 2. Data permitted on server

- opaque account/device identifiers needed by protocol;
- public device identity/HPKE keys;
- encrypted object containers;
- wrapped collection/object keys and signed grants;
- encrypted canonical catalog operations;
- signed device-log entries and heads;
- opaque transfer/chunk state;
- ciphertext sizes, versions, and transport checksums;
- authentication tokens that do not unlock vault content.

## 3. Data forbidden on server

- plaintext media, thumbnails, previews, waveforms;
- filenames, album titles, captions, EXIF, GPS, private search terms;
- root, collection, object, recovery, or identity private keys;
- passwords or fast password verifiers;
- decrypted catalogs/manifests;
- semantic real/decoy labels;
- global unkeyed plaintext hashes.

## 4. Guarantees clients seek

- confidentiality independent of server behavior;
- cryptographic integrity of each object/operation/grant;
- detection of invalid signatures, replay, simple rollback, and device-log forks;
- deterministic conflict application;
- complete immutable-object activation only after authenticated commit;
- recipient verification independent from server key substitution.

## 5. Guarantees not fully achievable

Without additional trusted witnesses/transparency infrastructure, clients may not prove:

- that the server has shown every operation;
- that all devices see the same global history;
- freshness after every local-state loss;
- deletion of server copies;
- availability;
- traffic-analysis resistance;
- recipient deletion of downloaded keys/plaintext.

These limitations must be product-visible where relevant.

## 6. Authentication versus encryption

Account authentication controls server resources but does not unlock vault content. Resetting server credentials must not reset the vault password or reveal root keys.

Auth tokens are stored/protected separately from vault keys and may be revoked without re-encrypting media.

## 7. Key-directory risk

The server may substitute public keys. Mitigations:

- signed device enrollment chains;
- stable verification fingerprints/phrases;
- key-change warnings;
- optional out-of-band verification;
- collection grants signed by sender identity;
- transparency/witness mechanisms in later versions.

Trust models are fixed here, not left open:

- enrolling a device into the user's own vault requires verification. The enrolling device shows the device fingerprint of [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §5, and the new device is not signed into membership until that fingerprint is compared or its QR payload is scanned. Both devices are in the user's hands at that moment, so the check costs nothing and removes server key substitution from own-device enrollment;
- a collection grant to an external recipient uses trust on first use. The recipient's keys are accepted as presented with the first grant and pinned. A later change to either pinned key blocks further grants until the user verifies the new fingerprint out of band or a signed identity rotation chaining to the pinned key authorizes it. Requiring out-of-band verification before every first share would make sharing unusable; pinning with a blocking key-change prompt limits the server to a substitution it must commit to before the first share and cannot repeat later.

The first device of a vault is self-enrolled and has no second device to compare against; its fingerprint is verified when the second device enrolls.

## 8. Metadata leakage

Server-visible leakage may include:

- account and device relationships;
- transfer timing and size;
- approximate number and size of objects;
- sharing graph/recipient count unless grants are additionally hidden;
- IP/network metadata;
- ciphertext version/capability;
- the per-device causal graph carried by the cleartext `observed_heads` vector of [`OPERATION_LOG.md`](OPERATION_LOG.md) §4, which shows which devices had accepted which of each other's operations;
- the grouping of operations by `key_selector`, so operations under one key epoch are linkable to each other, and the count of distinct live selectors. The selector is a random 16-byte value and names no collection.

Per-operation action kind and collection attribution are deliberately absent from this list: `operation_kind` and the collection context are inside `encrypted_payload` under [`OPERATION_LOG.md`](OPERATION_LOG.md) §6, and a change that moved either back into the cleartext record would add a row here.

Padding, batching, relay privacy, and private information retrieval are outside v1 but should remain possible extensions.

## 9. Availability and deletion

Clients maintain local/portable recovery. The server may delete or withhold data; replication and backups are reliability mechanisms, not confidentiality controls.

A server “delete” acknowledgment is not proof of physical erasure. Crypto-erasure depends on key-envelope destruction under client control, subject to recipient/backup copies.

## 10. Malicious-server test harness

Simulate:

- replayed old operations/objects;
- omitted tombstones;
- per-device forked histories;
- key substitution;
- stale collection grants;
- missing/duplicated/reordered chunks;
- inconsistent object commit state;
- rollback after local reinstall;
- equivocation between devices;
- deletion and availability failures.

Expected detection/limitation is documented per scenario.
