# RustCrypto secret cleanup patches

These are the existing resolved RustCrypto HKDF, HMAC, and digest packages. The local
patch changes buffer ownership and cleanup, not the algorithms or public APIs.
MIT and Apache-2.0 license files, upstream source, README, and test vectors are
retained for HKDF and HMAC. Digest retains source and licenses; its unrelated
test targets and dev-dependencies are omitted. Benchmarks and registry metadata
are omitted.

- `hmac 0.13.0`: upstream commit `0236c8eb50098dd7f277a71ab89caaeb1e7314df`, path `hmac`; crates.io archive SHA-256 `6303bc9732ae41b04cb554b844a762b4115a61bfaa81e3e83050991eeb56863f`.
- `hkdf 0.13.0`: upstream commit `bfb3b209abeeaa02277935b167e06bac320b2773`, path `hkdf`; crates.io archive SHA-256 `4aaa26c720c68b866f2c96ef5c1264b3e6f473fe5d4ce61cd44bbe913e553018`.

- `digest 0.11.3`: upstream commit `2fb9ed8922e244117040bb037a7d141a6a2b8228`, path `digest`; crates.io archive SHA-256 `f1dd6dbb5841937940781866fa1281a1ff7bd3bf827091440879f9994983d5c2`.

Sources: https://github.com/RustCrypto/KDFs/tree/bfb3b209abeeaa02277935b167e06bac320b2773/hkdf
https://github.com/RustCrypto/MACs/tree/0236c8eb50098dd7f277a71ab89caaeb1e7314df/hmac,
and https://github.com/RustCrypto/traits/tree/2fb9ed8922e244117040bb037a7d141a6a2b8228/digest.

## Patch scope

- The upstream test assertions use explicit `expect` messages where required
  by workspace Clippy checks. Removed Clippy lint names are omitted.
- HKDF owns discarded PRKs and expansion blocks with `Zeroizing`.
- HMAC owns padded keys, long-key digests, and inner digests with `Zeroizing`,
  including simple and reset variants.
- Digest clears the named `CtOutWrapper` full-output buffer, and forwards
  `zeroize` to `crypto-common` and thus `hybrid-array`. HMAC always enables
  `digest/zeroize`; the app enables `sha2/zeroize`.
  SHA state and block buffers then clear on drop, including cloned state.
- The root and fuzz workspaces patch all three crates, so HPKE also uses these copies.

The explicit extract API still transfers its PRK to its caller. That caller
must clear the returned bytes. A digest other than SHA-2 needs its own state
cleanup support. These patches do not guarantee removal of CPU registers,
compiler-generated copies, or transient compression-function values.

The packages add no primitive, build script, network access, permission, or
native code. They keep the existing no_std Android/iOS path. Retain these
patches until an upstream release provides the same cleanup; review the source
diff and run upstream vectors before replacing them. Recheck both lockfiles.

Validation: run `cargo test -p hmac -p hkdf` from `rust`, plus the Chur crypto,
format, and frozen-vector checks. This runs the retained upstream RFC and
Wycheproof tests. Release tests check the optimized build separately.
