# Security Policy

> **Status:** Accepted security and vulnerability-reporting policy

Chur is currently in the architecture and protocol-design stage. It has not received an independent security audit and is **not yet suitable for storing irreplaceable or high-risk data**.

## Supported versions

No production release is currently supported. Security fixes are applied to the default branch while the project remains pre-release. A supported-version matrix will be added before the first public beta.

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public issue, discussion, pull request, or social-media post.

Private vulnerability reporting is not yet configured for this repository. Until it is enabled:

1. Contact the repository owner through an available private channel associated with the GitHub profile.
2. Share only the minimum information needed to establish a private reporting channel.
3. Do not attach user data, real vaults, credentials, or production secrets.
4. If no private channel is available, open a public issue that contains only the sentence that you need a private security contact. Do not include technical details.

The project should enable GitHub Private Vulnerability Reporting before a public beta.

## Report contents

A useful report includes:

- affected commit, release, platform, and device/OS version;
- affected component or crate;
- prerequisites and attacker capabilities;
- minimal reproduction steps;
- expected and observed behavior;
- confidentiality, integrity, availability, or privacy impact;
- whether key material, plaintext, metadata, or only ciphertext is exposed;
- crash logs or traces after removing private data;
- suggested remediation, if known.

## Scope

High-value report areas include:

- vault unlock, key slots, recovery, and password processing;
- nonce construction, key derivation, key wrapping, and AEAD use;
- object-container parsing, completeness verification, and migrations;
- catalog encryption and transaction recovery;
- Rust/Kotlin/Swift FFI ownership, cancellation, and stale handles;
- Android Keystore and iOS Keychain integration;
- plaintext scratch files, caches, app-switcher snapshots, logs, and backups;
- real/decoy vault isolation;
- sync identities, operation logs, rollback protection, and revocation, plus future collection grants;
- supply-chain or build-system compromise affecting released artifacts.

Examples that are generally out of scope unless they demonstrate a Chur-specific defect:

- attacks requiring a fully compromised kernel after the vault is already unlocked;
- physical photography of the screen;
- denial of service that only consumes resources within documented parser limits;
- social engineering without a technical security boundary bypass;
- findings against unsupported local modifications.

## Disclosure process

The project intends to:

1. acknowledge a complete report;
2. reproduce and classify the issue;
3. identify affected formats and releases;
4. prepare tests before or with the fix;
5. coordinate disclosure after a remediation path exists;
6. publish a remediation summary when disclosure is appropriate.

No response-time SLA is promised before the project has a staffed security process.

## Handling security fixes

A security fix that changes persisted or wire bytes must include:

- a versioned migration or explicit incompatibility decision;
- deterministic regression vectors;
- negative tests for the original exploit;
- impact on existing real and decoy vaults;
- backup, restore, and downgrade analysis;
- documentation updates to `docs/security`, `docs/format`, or `docs/sync`;
- a review by a maintainer other than the author when possible.

## Research safety

Use synthetic test data. Do not access, modify, or retain another person's vault or account without explicit authorization. Stop testing if continued work risks exposing user data or damaging a device.

## Cryptography status

The design in [`docs/CRYPTOGRAPHY.md`](docs/CRYPTOGRAPHY.md) is provisional. A published specification, stable vectors, fuzzing, platform integration tests, and an independent review are required before production security claims are made.
