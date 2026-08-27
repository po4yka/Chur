# ADR-0031: Continuous Integration Owns Release-Gate Enforcement

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../assurance/RELEASE_GATES.md`](../assurance/RELEASE_GATES.md), [`../assurance/FUZZING.md`](../assurance/FUZZING.md), [`../assurance/SECURITY_TEST_PLAN.md`](../assurance/SECURITY_TEST_PLAN.md), [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md)

## Context

The assurance documents delegate enforcement to continuous integration. `FUZZING.md` §10 requires a smoke fuzz on every pull request that touches a target, `TEST_VECTORS.md` has release CI archive the vector-set digest, `PERFORMANCE_BUDGETS.md` §1 compares against a baseline pinned in CI, and `RELEASE_GATES.md` records a CI/test matrix in every evidence package. The repository contains no pipeline definition and no test, bench, or fuzz target, and `DEVELOPMENT.md` told contributors that CI is the source of truth for supported commands. No phase, gate, or document owned creating it, and none said what applies in the meantime, so an unenforced gate read exactly like an enforced one.

## Decision

- `.github/workflows/rust.yml` is the enforcing workflow. The repository maintainer owns it, and creating it is Phase 0 scope in `ROADMAP.md`.
- Its v1 minimum job set is `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`, and `cargo deny check`, run against `rust/` on every pull request and on the default branch, with every action pinned to an immutable commit SHA.
- Jobs join that set as their subject lands: fuzz smoke with the first fuzz target, vector-digest archival with the first vector set, benchmark comparison with the first pinned baseline, Gradle and Xcode jobs with those builds. A document may not name a job that does not exist.
- Until the workflow exists, no gate item is enforced. Local command output attached to a pull request is evidence for that pull request only and is never recorded as a passed gate. Gate 1 may not be declared while the workflow is absent.
- `RELEASE_GATES.md` "Enforcement" is the single place these rules live; `FUZZING.md` §10, `SECURITY_TEST_PLAN.md` §13, and `DEVELOPMENT.md` point at it.

## Alternatives considered

### Leave enforcement to reviewer discipline

Rejected. The gates block on byte-exact specifications, the workspace lints, and the cryptographic dependency set. A reviewer cannot re-derive those from a diff, and nothing records that the check ran.

### Specify the full job matrix now

Rejected. The Gradle and Xcode builds do not exist, and a job that cannot run is the same unenforced claim relocated into a new file.

## Consequences

### Positive

- an unenforced gate is visible as unenforced instead of implied;
- the format, lint, test, and advisory floor applies from the first Rust pull request;
- the exact toolchain pinned in `rust/rust-toolchain.toml` becomes the version that actually runs the checks.

### Tradeoffs

- the workflow is edited whenever a document adds a gate, which is the cost of the mapping being real;
- pinned action SHAs need deliberate periodic updates.

## Security impact

Affected invariants: SEC-019, SEC-039.

No invariant changes. The decision removes a false-assurance path into Gate 1: a gate with no job is now recorded as unenforced instead of assumed covered, and the two invariants whose evidence is a repository-level check gain a place to run.

## Compatibility impact

No persisted or wire bytes change.

## Validation

- the workflow runs the four jobs and fails the pull request on any non-zero exit;
- a deliberately unformatted commit and a deliberate clippy warning both fail;
- the evidence package of the first gated release lists the items that had no enforcing job.

## Follow-up

- add the fuzz smoke job with the first `chur-format` fuzz target;
- add vector-digest archival with the first `chur-cli` vector generator;
- add the Gradle and Xcode jobs when those builds exist.
