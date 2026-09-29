# ADR-0059: Permit User-Started Requests for Public Platform-Security Data

- **Status:** Accepted
- **Date:** 2026-09-29
- **Decision owners:** @po4yka
- **Related:** [`0033`](0033-chur-operates-no-sync-service.md), [`../ANDROID.md`](../ANDROID.md) §24, §25.1, and §37, [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md) "Shared store answers", [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §10, [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) "Recorded additions"

## Context

Until now, Chur used the network only for encrypted sync with a deployment that the user controls ([`0033`](0033-chur-operates-no-sync-service.md)). `ANDROID.md` §24 named "network access for encrypted synchronization" as the only network need. No document gave a rule for a request to a party that the user did not choose.

AndroidX Security State 1.1.0 (September 2026) reads the device's installed security patch level and asks the on-device update clients over IPC whether a newer level is staged. Both steps are local. To compare the device with the newest published Android Security Bulletin, the app must fetch a public vulnerability report from `https://android-api.osv.dev/v1/android_sdk_<API level>.json` and pass it to the library. The library does not fetch the report itself.

That comparison is useful to the user. But it is a request to Google-operated infrastructure that the user did not configure. The decision is whether such a request is permitted, and under which conditions. The rule must also apply to later cases of the same kind, so each new case does not need a new policy.

## Decision

Chur may send a request to a third-party endpoint only when every condition below holds. This ADR calls such a request a **public-data request**:

1. **Public data only.** The response is public data about the platform, for example a security bulletin or a vulnerability report. It is not data about the user, the vault, or the device's contents.
2. **Nothing private leaves the device.** The request carries no vault value, no identifier, no cookie, no credential, and no persistent client state. The URL may carry a public platform fact that the endpoint needs, such as the Android API level. SEC-033 applies unchanged.
3. **The user starts it.** Only an explicit tap on a row starts the request. The row names the destination host before the tap. The request never runs at launch, at unlock, when a screen opens, from a background worker, or as an automatic retry.
4. **Fixed destination and transport.** The endpoint is an HTTPS URL fixed in code or in a pinned dependency. The request follows no redirect, has a timeout, and reads a bounded number of bytes. The response is parsed as untrusted input.
5. **Nothing persists.** The response and its result stay in memory for the unlocked session. A lock discards them. Nothing is written to disk, to a log, or to backup.
6. **The same in every identity.** The row, its copy, and its behavior do not depend on which vault identity is open ([`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §10, SEC-036, SEC-037).
7. **Information only.** The result never blocks, delays, or changes a vault operation. A failed request leaves the row readable and says that the host could not be reached.
8. **No SDK.** The request uses a platform HTTP client or a dependency recorded in `DEPENDENCY_POLICY.md`. No analytics, crash, or telemetry library takes part.
9. **Registered endpoint.** Each permitted endpoint is listed in the registry below. A new endpoint needs an amendment to this ADR, and an endpoint that fails a condition above needs a new ADR.

### Endpoint registry

| Endpoint | Platform | Purpose | Documented in |
| --- | --- | --- | --- |
| `https://android-api.osv.dev/v1/android_sdk_<API level>.json` | Android | Compare the system security patch level with the newest published bulletin | [`../ANDROID.md`](../ANDROID.md) §25.1 |

## Alternatives considered

### Keep the network for sync only

Rejected at the owner's request. The row would then show the installed and the staged patch levels but could not say whether they are current. Most users do not know the date of the newest bulletin, so the installed date alone does not help them.

### Check automatically when Settings opens

Rejected. The endpoint operator and any network observer would see a request each time the user opens vault Settings. The request time then correlates with the use of the private vault. A tap limits this to the moments the user chooses.

### Ship a bulletin snapshot inside the app

Rejected. The snapshot becomes stale between releases, so the row would claim "latest published" for an old level. That is a false statement about security.

### Proxy the request through a Chur server

Rejected by [`0033`](0033-chur-operates-no-sync-service.md): the Chur project operates no service.

## Consequences

- the endpoint operator sees the device's IP address, the request time, the API level in the path, and a TLS client fingerprint. It sees no vault data;
- a network observer sees a TLS connection to `android-api.osv.dev`. That shows that some app on the device checked the Android bulletin. It does not name Chur, and it gives no vault data;
- the answer "No data leaves the device" in `ANDROID.md` §37.2 stays true, because the request sends no user data. The shared store answers state the request so that a reviewer sees it;
- iOS has no equivalent API. The row does not exist on iOS, and nothing in this ADR adds a request there.

## Security impact

Affected invariants: SEC-033, SEC-036, SEC-037.

No vault control changes. The attack surface grows by one HTTPS response parser (the library's JSON parser, reached only after a user tap, with a bounded input). The row is information only, so a false or hostile report can at worst show a wrong date. It cannot unlock, lock, or change data. The threat model does not rely on the patch level: [`../security/THREAT_MODEL.md`](../security/THREAT_MODEL.md) A6 stays outside the primary guarantee.

## Compatibility impact

No persisted or wire bytes change.

## Validation

- the leakage tests of [`../assurance/SECURITY_TEST_PLAN.md`](../assurance/SECURITY_TEST_PLAN.md) include this request under "network requests": a canary vault value must not appear in its URL, headers, or body;
- a review confirms that no code path starts the request without the row's tap.

## Follow-up

- an iOS equivalent needs its own amendment to the registry;
- a request that must run without a tap, for example a scheduled check, needs a new ADR.
