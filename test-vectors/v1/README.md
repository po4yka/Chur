# Chur v1 Vector Scaffold

> **Status:** Proposed — empty scaffold; v1 bytes are not frozen

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

No binary fixtures are included yet because canonical constants, tags, and exact v1 layouts remain proposed.
