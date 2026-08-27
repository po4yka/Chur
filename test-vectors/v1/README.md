# Chur v1 Vector Scaffold

> **Status:** Proposed — empty scaffold; the v1 constants, domain tags, and record layouts are frozen and the fixtures are outstanding

Planned fixture groups:

```text
canonical-encoding/
password-slots/
recovery-slots/
vault-descriptors/
collection-envelopes/
object-key-envelopes/
object-containers/
backup-packages/
sync-operations/
collection-grants/
negative/
manifest.json
```

The first vector-generating implementation must land with:

- explicit test-only deterministic RNG boundary;
- generator source in `chur-cli`;
- positive and negative vectors;
- byte-layout documentation;
- Android/iOS/CLI consumption tests;
- digest recorded in release evidence.

No binary fixtures are included yet because the vector generator in `chur-cli` does not exist. The constants and domain tags are allocated in [`docs/format/CANONICAL_ENCODING_V1.md`](../../docs/format/CANONICAL_ENCODING_V1.md) §15, and the container, descriptor, and envelope layouts are frozen in their specifications.
