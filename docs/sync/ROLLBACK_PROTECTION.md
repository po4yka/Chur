# Rollback, Replay, and Fork Protection

> **Status:** Proposed future sync security model

AEAD and signatures prove authenticity, not freshness or completeness. Chur uses local accepted heads, per-device chains, membership generations, and checkpoints to reject common rollback/replay attacks while documenting malicious-server limitations.

## 1. Attack classes

- replay identical accepted operation;
- present older prefix as current history;
- omit tombstone or membership change;
- present two different records at same device sequence;
- roll back collection epoch/grant;
- restore old but authentic object/catalog/backup;
- show different histories to different devices;
- erase all local freshness state then present stale server state.

## 2. Local trusted state

Each device stores, inside protected authenticated catalog state:

```text
membership generation/commitment
latest accepted sequence and hash per device
latest accepted checkpoint commitment
global/materialized state generation when defined
collection epochs and grant generations
```

A server response below a local accepted head is rejected as rollback.

## 3. Replay

An identical operation already applied is idempotently ignored/acknowledged. A non-identical operation with the same `(device_id, sequence)` is a fork.

Operation IDs alone are not freshness proof.

## 4. Fork handling

On fork:

- stop applying that device's subsequent chain;
- retain both signed branches as evidence where safe;
- mark device/account security state;
- compare with another authorized device or recovery checkpoint;
- require explicit reconciliation/revocation;
- do not let server choose branch silently.

## 5. Membership and key epochs

Device enrollment/revocation and collection epoch changes are signed, monotonically versioned state. Old grants/epochs cannot replace newer accepted state even if cryptographically valid.

## 6. Checkpoints

A checkpoint may include:

```text
membership commitment
map/commitment of per-device heads
catalog/materialized-state commitment
collection epoch summary
issuer/quorum signatures
```

Checkpoint design requires its own canonical format and trust rule. A single-device self-signed checkpoint cannot prove server did not hide later unseen operations from every device.

## 7. Device loss and reinstall

If all local accepted heads are lost, a malicious server may present an older authentic history. Mitigations:

- portable backup containing authenticated heads;
- recovery record/checkpoint stored separately;
- comparison with another authorized device;
- future transparency/witness service;
- user-visible backup/checkpoint date after authentication.

V1 must state the residual risk rather than claim perfect rollback protection.

## 8. Offline backups

Backups are authentic but can be stale. Restore compares embedded heads/generations with any surviving trusted local/peer state. User may intentionally restore older state, which creates a new recovery branch under explicit policy rather than silently overwriting newer sync state.

## 9. Server omission

Per-device chains detect gaps after a known later sequence, but server may hide an entire unknown device/branch or all operations after the client's last checkpoint. Cross-device gossip or transparency is required for stronger global completeness.

## 10. Time

Wall-clock timestamps are not rollback proof. They may aid UX after decryption but ordering is based on signed sequence/causal state.

## 11. Tests

- exact duplicate replay;
- modified record same sequence;
- old prefix after newer local head;
- missing middle operation;
- forked device branches;
- stale membership/collection epoch/grant;
- reinstall with and without trusted backup/peer;
- equivocation between two devices;
- intentional old-backup restore;
- checkpoint signature/commitment corruption;
- revoked device branch.
