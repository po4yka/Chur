# ADR-0030: Freeze the Vault Registry Layout and Discovery Order

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md), [`0005`](0005-real-and-decoy-vault-isolation.md), [`0011`](0011-freeze-vault-descriptor-authentication.md)

## Context

Startup must enumerate candidate vault descriptors before any credential exists. The registry appeared exactly twice in the corpus — one line of the `ARCHITECTURE.md` §14.4 tree and one sentence in `VAULT_DESCRIPTOR_V1.md` §11 — with no format, no naming rule, no entry cap, and no statement of what one wrong password costs when several candidates exist.

ADR-0011 made the gap load-bearing rather than cosmetic: the indistinguishability rules it froze into §8 require that "the same candidate set is attempted in the same order for every attempt, whatever the outcome", and no document defined the candidate set or the order. Phase 1 ships one vault, so a fixed path would work, but the real/decoy isolation Phase 2 inherits depends entirely on this mechanism and retrofitting it means rewriting the unlock path.

## Decision

Define discovery in `VAULT_DESCRIPTOR_V1.md` §11:

- one file per descriptor in `registry/`, named with 16 CSPRNG bytes as 32 lowercase hexadecimal characters plus `.vd`, unrelated to `vault_id` and to creation order;
- at most 2 entries, a third being `RESOURCE_LIMIT_EXCEEDED`; two is the product maximum of one real identity plus one decoy;
- the candidate set is every entry, enumerated by filename bytes ascending;
- an entry failing the §13 parser limits is skipped before any credential is used and its failure is attributed to no credential;
- an attempt evaluates every candidate before returning, so its cost is exactly one key-derivation evaluation per entry whatever the outcome.

## Alternatives considered

### One fixed descriptor path for Phase 1, registry decided later

Rejected. The unlock path, the per-attempt cost bound, and the constant-work rules of §8 all depend on the candidate set, so "later" means rewriting the security-critical part of unlock after it has been reviewed.

### Name the entry after `vault_id` or a hash of it

Rejected. Either makes the filename a stable identifier that survives a descriptor rewrite and links a registry entry to anything else that carries the same value.

### Enumerate in directory order, or randomize per attempt

Rejected. Directory order follows creation order on common filesystems, which reveals which identity was created first. A per-attempt random order makes the work non-constant across attempts, which is what §8 forbids.

### Always keep two entries, padding with an indistinguishable filler

Rejected for v1. It would hold the per-attempt cost constant whether or not a decoy exists, but it doubles password-unlock latency for every user to hide a signal `DECOY_VAULT.md` §5 already declines to promise is hidden, and Phase 1 excludes the decoy entirely.

## Consequences

### Positive

- unlock has a defined candidate set, a defined order, and a bounded cost;
- the registry discloses nothing through filenames or ordering;
- Phase 2 adds a decoy by writing a second file, with no change to the unlock path.

### Tradeoffs

- a vault with a decoy costs two Argon2id evaluations per password attempt, so it misses the single-evaluation unlock budget by roughly a factor of two, and Phase 2 revisits that budget;
- the entry count is observable as unlock latency, recorded in `DECOY_VAULT.md` §5;
- two identities is a hard product ceiling until a new descriptor version raises it.

## Security impact

Affected invariants: SEC-005, SEC-027.

The registry is read before authentication, so everything in it is attacker-visible in the sandbox-extraction profile. Random filenames keep it free of stable identifiers, the ascending-filename order removes the creation-order signal, and evaluating every candidate on every attempt is what makes ADR-0011's constant-work rules realizable rather than aspirational. Skipping an unparseable entry before any credential is used keeps a malformed file from becoming an authentication oracle.

## Compatibility impact

No registry exists, so nothing migrates. The entry cap and the naming rule are descriptor-version policy: raising the cap or changing the suffix takes a new `descriptor_version` and a dual-reader policy.

## Validation

- an unlock attempt against one, two, and three entries, asserting the cap and the evaluation count;
- identical work and identical error output for a wrong password, a decoy password, and a corrupt entry;
- enumeration order stable across creation orders and across filesystems;
- an entry failing §13 skipped with its own parser code and no credential attributed.

## Follow-up

- the decoy-creation flow that writes the second entry lands with Phase 2;
- the Phase-2 password-unlock budget for a two-entry registry is set in `assurance/PERFORMANCE_BUDGETS.md`.
