# ADR-0033: Chur Operates No Sync Service

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../sync/SERVER_TRUST_MODEL.md`](../sync/SERVER_TRUST_MODEL.md), [`0007`](0007-local-first-before-sync.md), [`../security/THREAT_MODEL.md`](../security/THREAT_MODEL.md)

## Context

Eight sync documents assume accounts, authentication tokens, device identifiers, and a server that stores ciphertext. `SERVER_TRUST_MODEL.md` §1 admits that the server observes account, IP, timing, object count, and transfer sizes, and §9 says a delete acknowledgment is not proof of erasure. The words operator, self-host, backend, and hosting appear nowhere in the repository, so nothing said who runs the service or who controls metadata that is personal data wherever the user lives. Phase 3 cannot be designed, priced, or reviewed against an unnamed operator, and a zero-knowledge product that leaves the operator unnamed has left part of its model unwritten.

## Decision

- The Chur project operates no service. There is no first-party account, server, or storage in any roadmap phase.
- A vault that syncs does so against a deployment the user controls: a self-hosted Chur sync service, or object storage the user holds with a provider of their choosing.
- The operator of a deployment is the data controller for the metadata `SERVER_TRUST_MODEL.md` §1 lists, and in both supported cases the user is that operator.
- An implementation distributed as a Chur sync server carries the operator obligations of `SERVER_TRUST_MODEL.md` §11: bounded log retention, protocol-exposed deletion, published retention documentation, and no analytics or content-derived indexing.
- A third party operating a deployment for other people is out of scope until an ADR adds it, and must not be described as Chur's service.

## Alternatives considered

### Run a first-party service

Rejected. It creates an account relationship, a controller obligation, and a revenue model that no document plans, and it asks a user to accept one vendor on both sides of a boundary the threat model calls untrusted.

### Leave the deployment model open

Rejected. That is the current state, and it is what makes the metadata question unanswerable: an unnamed operator has no obligations, no retention period, and no jurisdiction.

## Consequences

### Positive

- the untrusted-server assumption matches the deployment reality instead of describing a service the project would run;
- Phase 3 has a concrete target to build, document, and attack in the malicious-server harness;
- no user metadata reaches the project.

### Tradeoffs

- sync is harder to adopt than a hosted service, and Phase 3 must carry deployment documentation and a reference server;
- the project cannot enforce operator obligations on a deployment it does not run, so they bind the implementation rather than guaranteeing anything to a client.

## Security impact

Affected invariants: SEC-040.

No control changes. The threat model already assumes a malicious server. Naming the operator makes the residual metadata exposure attributable, and bounds who can be compelled to produce it.

## Compatibility impact

No protocol bytes change. Account and token fields keep their definitions; they now refer to a deployment the user controls.

## Validation

- the malicious-server harness of `SERVER_TRUST_MODEL.md` §10 runs against the reference server implementation;
- the reference server ships retention documentation and a protocol-exposed deletion path;
- no document describes a first-party account, server, or storage.

## Follow-up

- Phase 3 adds the reference server and its operator documentation to scope;
- an ADR is required before any third-party-operated deployment is described.
