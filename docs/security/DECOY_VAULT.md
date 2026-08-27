# Decoy Vault

> **Status:** Proposed normative isolation and product contract

A Decoy Vault is an independently encrypted vault identity intended to present plausible private content during ordinary or coercive UI inspection. It is not a filtered view of the real vault and is not claimed to be forensically undetectable.

## 1. Cryptographic separation

```text
Real credential → RealRoot → Real catalog/collections/objects
Decoy credential → DecoyRoot → Decoy catalog/collections/objects
```

Real and decoy must not share:

- root, collection, object, catalog, search, settings, or identity keys;
- password/recovery slots;
- Keystore aliases or Keychain item identifiers;
- private catalog files;
- object directories or stable object IDs;
- thumbnail/preview/player caches;
- navigation restoration;
- session generations and handle registries;
- sync accounts or backup manifests by default.

## 2. Session routing

The session gate resolves a credential to an opaque `VaultSessionHandle`. Ordinary feature code does not receive a durable `isDecoy` flag.

```text
credential/platform action
    → slot candidate resolution
    → root validation
    → opaque vault descriptor
    → session-scoped dependency graph
```

External failure behavior is equivalent for real, decoy, wrong, damaged, or absent credentials.

## 3. Provisioning

Decoy provisioning occurs from an already authenticated real session or a dedicated setup flow. Requirements:

- fresh independent root and slots;
- user confirms the decoy credential is distinct;
- initial plausible content is user-controlled or synthetic and clearly explained;
- the limitation in §10 is stated before the decoy is created: the feature is publicly documented, so a coercer may know to keep demanding credentials;
- no automatic copying of real media or metadata;
- recovery semantics explained separately;
- setup can be disabled without destroying the real vault.

## 4. Storage layout

Perfectly hiding a sibling vault is out of scope. The physical layout should nevertheless avoid obvious semantic labels such as `real/` and `decoy/`.

Use random vault directory identifiers and opaque descriptor references. Do not attempt deceptive filesystem tricks that make recovery or integrity unsafe.

## 5. Timing and side channels

Potential distinguishing signals include:

- Argon2 profiles and unlock latency;
- catalog/object count and storage size;
- presence of platform aliases;
- backup/sync traffic;
- cleanup journals;
- cache warmness;
- error timing.

Mitigations may normalize user-visible flow and avoid explicit labels, but Chur does not promise constant-time whole-vault behavior or hidden storage volume.

Unlock latency is the one signal that is bounded. [`KEY_SLOTS.md`](KEY_SLOTS.md) §8 fixes the password-candidate count at two and pads the list with dummy derivations, so the Argon2id cost of one attempt does not grow with the number of identities present. Two residual leaks remain and are accepted:

- the Argon2id parameters of a password slot are public descriptor bytes, so identities calibrated to different parameters differ in both the published parameters and the cost of one derivation. Provision every identity on one device with the same profile, as [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §23 requires;
- a device holding one identity still pays two derivations, and a device that cannot allocate the memory floor fails before the first one under [`PASSWORD_PROFILE.md`](PASSWORD_PROFILE.md) §6. Both are constants of the unlock procedure, readable from these documents, and neither counts identities.

## 6. Public and decoy UI

The public shell remains separate from the decoy vault. A decoy session should behave as a complete private vault:

- import/export works according to policy;
- media and metadata are genuinely encrypted;
- lock and recovery rules apply;
- content is not marked as fake inside ordinary UI;
- no settings reveal real-vault counts, backups, or identities.

## 7. Backup and sync

Initial recommendation:

- local-only decoy in Phase 2;
- no shared backup package containing both identities unless a dedicated format and threat analysis exists;
- no common remote account metadata that reveals a sibling identity;
- user explicitly chooses whether the decoy has independent backup/recovery.

A future server cannot be assumed to hide storage volume or account relationships.

## 8. Recovery

Real and decoy recovery secrets are independent. Recovering one must not reveal that another exists. Product UX must prevent accidental overwriting of the other vault's descriptors during restore.

If the user chooses no recovery for decoy, loss is irreversible.

## 9. Deletion

Deleting decoy requires authenticated confirmation in the decoy or an explicit management flow from the real session. It must never delete shared state because shared private state is forbidden.

Deleting the real vault must not silently leave a decoy presented as the only recoverable identity without clear user intent.

## 10. Product claims

Allowed terms:

- Decoy Vault;
- discreet access;
- independent alternate vault;
- coercion-resistant UI with limitations.

Disallowed without a new proven design:

- undetectable;
- deniable filesystem volume;
- impossible to prove another vault exists;
- forensic-proof.

### Assumed adversary knowledge

The existence of this feature is public by design. [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md) requires the store listing to describe the discreet interfaces, and [`../IOS.md`](../IOS.md) §37 requires review notes to describe how real and decoy sessions behave. A coercer may therefore know that a second credential can exist before demanding anything, and this specification does not assume otherwise.

What remains is indistinguishability, not concealment: the defence holds only where the coercer cannot tell an opened decoy from a vault that has no sibling. Therefore:

- a decoy session must not prove or disprove the existence of a sibling identity from inside the application. No count, setting, notification, backup state, error, timing class, or management surface reachable from a decoy session may differ according to whether a real vault exists;
- the same must hold in a real session with no decoy provisioned, so that "no decoy exists" and "the decoy was not opened" are one observation rather than two;
- §3 provisioning must state this limitation before the user creates a decoy.

This bounds the claim: Decoy Vault raises the cost of a demand to open the vault. It does not defeat a coercer who knows the feature exists and keeps demanding credentials.

## 11. Test matrix

- correct real credential opens only real data;
- correct decoy credential opens only decoy data;
- wrong credential produces equivalent external failure;
- caches and navigation do not cross sessions;
- platform alias invalidation affects only its identity;
- backup/restore of one identity does not expose or overwrite the other;
- process death returns to public locked state;
- storage/log/notification inspection finds no semantic real/decoy label;
- lock invalidates readers from both identities;
- migration can process one vault without opening the sibling;
- a decoy session and a real session with no decoy provisioned are indistinguishable from inside the application across counts, settings, notifications, backup state, and error copy.
