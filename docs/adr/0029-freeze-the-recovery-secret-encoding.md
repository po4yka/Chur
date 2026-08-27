# ADR-0029: Freeze the Recovery-Secret Human Encoding as BIP-39 English

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../security/RECOVERY.md`](../security/RECOVERY.md), [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md), [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md)

## Context

`RECOVERY.md` §2 said only that the 32-byte `RecoverySecret` "is encoded for humans as a versioned mnemonic, QR, or both". No wordlist, no word count, no checksum algorithm, no version marker, and no normalization rule for re-entry existed anywhere. `CRYPTOGRAPHY.md` §74 item 14 carried the question as open and dismissed it as presentation-layer work.

It is not presentation-layer work. A phrase written down under one encoding and read back under another is unrecoverable data loss — the exact failure the recovery slot exists to prevent — and `ROADMAP.md` puts recovery slots in Phase 1, so the first recovery-slot commit would have frozen an undocumented choice.

## Decision

Freeze the representation in `RECOVERY.md` §2:

- 24 BIP-39 English words, entropy 256 bits plus BIP-39's 8-bit `SHA-256` checksum, 11 bits per word against the 2048-word English list;
- the ASCII marker `chur-recovery-v1` printed above the numbered words, and the QR payload `chur-recovery-v1:` followed by the 24 space-separated words in byte mode at error-correction level M;
- re-entry normalization: NFKD, lowercase, collapse whitespace runs to one space, trim; then match each word by its first four characters, which are unique in the English list;
- an unmatched word or a failed checksum is `INVALID_INPUT` with no slot unwrap attempted; only a checksum-valid phrase that fails to unwrap returns `AUTHENTICATION_FAILED`.

## Alternatives considered

### A Chur-specific wordlist

Rejected. It would need the same properties BIP-39 already has — 2048 entries, four-character prefix uniqueness, no confusable pairs, a published normative list — and would ship without any of BIP-39's implementation review or user familiarity.

### Base32 or a hex string with a CRC

Rejected. 32 bytes is 52 base32 characters or 64 hex characters, both of which are transcribed by hand worse than words, and neither carries a checksum users are trained to expect.

### SLIP-39 shares

Rejected for v1. It solves secret splitting, which no requirement asks for, at the cost of a second wordlist and a share-management interface.

### A version word inside the mnemonic

Rejected. It would consume entropy bits or break the 24-word arithmetic. The marker sits outside the mnemonic, so a future encoding is detectable without touching the secret.

## Consequences

### Positive

- one phrase has exactly one written form and one binary value;
- audited Rust BIP-39 implementations exist, and a user's existing 24-word habits transfer;
- four-character prefix matching recovers a mistyped word ending without weakening anything.

### Tradeoffs

- the English list only: other languages need a new profile marker and its own vectors;
- BIP-39's 8-bit checksum catches roughly 255 of 256 random corruptions, not all of them, and the slot unwrap is the real check;
- an observer who sees the phrase recognizes it as a wallet-style recovery phrase, which is not a Chur-specific signal.

## Security impact

Affected invariants: SEC-001, SEC-002.

The encoding changes no key and no derivation: `RecoveryKEK` is still `HKDF-SHA-256` over the same 32 bytes under `chur/v1/recovery/root-envelope`. The failure split is deliberate and creates no oracle: word-match and checksum failures are computable offline by anyone already holding the phrase, so returning them separately reveals nothing an attacker could not compute, while a checksum-valid phrase that fails the unwrap returns the single indistinguishable authentication failure `KEY_SLOTS.md` §3 requires.

## Compatibility impact

No recovery secret has been issued, so nothing migrates. A future encoding takes a new marker, `chur-recovery-v2`, and both readers stay until no phrase under the old marker can exist.

## Validation

- round-trip vectors: 32 bytes to 24 words and back, including all-zero and all-ones entropy;
- a denormalized re-entry with mixed case, NBSP separators, and composed accents that must normalize to the same words;
- one-bit entropy change producing a different phrase, and a one-word substitution failing the checksum;
- a QR payload without the prefix rejected before parsing.

## Follow-up

- the printed and on-screen layout is presentation, owned by `DESIGN.md` §17.2;
- additional BIP-39 languages, if ever added, take a new marker and their own vectors.
