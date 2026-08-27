# Vendored data

## `bip39-english.txt`

The BIP-39 English wordlist, 2048 words, one per line, in the canonical order
BIP-39 defines. It is vendored rather than pulled from a crate because it is
data, not code: the list is frozen by the standard, the encoding around it is
about eighty lines, and a dependency would add a build surface for a constant.

- **Upstream:** <https://github.com/bitcoin/bips>, path `bip-0039/english.txt`
- **SHA-256:** `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`
- **Used by:** `chur-crypto::recovery`, under
  [ADR-0029](../../../../docs/adr/0029-freeze-the-recovery-secret-encoding.md)

`docs/DEPENDENCY_POLICY.md` requires a source revision for vendored content.
The list has been byte-stable since BIP-39 was finalized, and the SHA-256 above
is the value the standard publishes; a test in `recovery.rs` asserts it at
build time, so a substituted list fails the suite rather than silently changing
every recovery phrase.
