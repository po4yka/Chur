# App Store readiness research — 2026-10-20

> Status: Proposed. Research and source inspection only; this is not an App Review approval, a completed release gate, or legal classification.

> Historical scope: this research describes baseline `7190b1a`. Its server/account and service-mediated sharing findings precede the removal of those features in [ADR-0065](../adr/0065-peer-only-sync-without-accounts.md); they do not describe the current peer-only application.

Scope: Chur's iOS application and incoming-share extension. Official Apple sources retrieved on **20 October 2026**; repository inspection started at `7190b1a` on `main`, initially clean. Unrelated research files appeared during inspection and were left untouched. No application code, account settings, signed archive, device installation, or submission was changed or tested.

## Conclusion

Chur needs bundled privacy manifests and an accessible privacy-policy entry before submission. Its release archive, export classification, App Privacy answers, and review instructions also need completion. Existing optional server synchronization and collection sharing are reachable on iOS, so their account lifecycle and abuse-handling implications must be resolved for the actual submitted product. A purely local vault must not be treated as a social platform, but the current application cannot be assessed as purely local.

This is a prioritized implementation and submission plan. It does not propose removing discreet mode, weakening encryption, or adding mandatory registration. Local vault creation is not automatically an online account.

## Applicable Apple rules

Apple requires an accessible privacy-policy link in the app and App Store Connect (ASC). Policies explain data use, recipients, retention/deletion, and consent withdrawal. Reviewers need complete access, working services, and instructions for non-obvious features. Metadata must describe the submitted experience accurately. [App Review Guidelines, 5.1.1(i), Before You Submit, 2.3](https://developer.apple.com/app-store/review/guidelines/).

Guideline 1.2 covers apps enabling content creation, upload, and sharing, and requires filtering, reporting and response, user blocking, and published contact information. How that applies to Chur's private collection-sharing design requires resolution; self-hosting and encryption do not establish an explicit exemption. [Guideline 1.2](https://developer.apple.com/app-store/review/guidelines/#user-generated-content).

Those are submission requirements, not a guarantee that a reviewer will accept any particular implementation.

## Necessary application changes

| Priority | Evidence and proposed change | Observable acceptance |
| --- | --- | --- |
| P0 | **Bundle privacy manifests.** No `PrivacyInfo.xcprivacy` was found. Required-reason APIs occur in [iOS settings](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosEntryPoint.kt#L167), [scratch copying](../../apps/iosApp/Sources/ScratchCopy.swift#L34), and [incoming shares](../../apps/iosApp/Sources/IncomingShareInbox.swift#L74). Add accurate declarations to every affected app/extension bundle and audit linked native code. | The release archive contains valid manifests in the correct bundles; an Xcode privacy report covers actual API use; upload processing reports no missing/invalid declarations. |
| P0 | **Add Privacy Policy and support access to ordinary settings.** [PublicSettingsScreen](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/notes/PublicSettingsScreen.kt#L60) currently has no policy/contact entry. Provide a public HTTPS policy URL and an easily discoverable UI link, including before vault access when practical. A discreet interface can retain its purpose without concealing legal/support access. | A new user and a user without an unlocked vault can open the published policy and contact route. Policy, ASC URL, and implementation describe the same behavior. |
| P1 | **Resolve remote deletion semantics.** [Sync bootstrap](../../shared/core-sync/src/commonMain/kotlin/dev/po4yka/chur/sync/SyncCoordinator.kt#L398) creates server membership. A [signed whole-vault delete operation](../../shared/core-sync/src/commonMain/kotlin/dev/po4yka/chur/sync/SyncClient.kt#L264) exists, but no coordinator/UI caller was found. [Stop using server](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/VaultShell.kt#L1380) forgets a token. If this membership qualifies as account creation, expose an intentional remote deletion flow instead of presenting disconnect as deletion. | Test against a real test server: remote membership/data are deleted as specified, failure leaves recoverable credentials and a retry route, and local-only deletion is clearly distinguished. Explain retention and administrator obligations. |
| P1 | **Resolve collection-sharing safeguards.** [SharingCard](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/SharingCard.kt#L54) shares the collection and future imports with recipients on the same server. Incoming grants, grant, and revoke exist; no report/block UI was found. Define and implement safeguards appropriate to the released service, or explicitly choose a narrower release scope before submission. | Review a real sender/recipient scenario, unwanted incoming sharing, revocation, abuse reporting and recipient blocking. Establish who receives and acts on reports without silently uploading decrypted vault contents. |

Required-reason declarations have been enforced since **1 May 2024**. Approved reasons must match behavior, including restrictions on derived data. [Required reason APIs](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api).

Candidates to validate against call sites: `CA92.1` for app-only UserDefaults; `1C8F.1` for App Group defaults; `35F9.1` for elapsed-time/timer calculations; `C617.1` for container file metadata; `3B52.1` for user-selected files; `E174.1` for observable space-dependent writing/cleanup; `85F4.1` for displaying space. Do not copy all reasons indiscriminately. [API categories](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype), [approved reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons).

[ScratchCopy.swift](../../apps/iosApp/Sources/ScratchCopy.swift#L52) uses uptime; [IncomingShareInbox.swift](../../apps/iosApp/Sources/IncomingShareInbox.swift#L189) uses it too and is compiled into both targets through [project.yml](../../apps/iosApp/project.yml#L110). Inspect the final bundles rather than assuming one main-app manifest covers every executable. iOS manifests belong at bundle root and must be target resources; invalid files are rejected. Listed third-party SDK requirements also apply where those SDKs are present. [Manifest packaging and validation](https://developer.apple.com/documentation/bundleresources/adding-a-privacy-manifest-to-your-app-or-third-party-sdk).

## Submission work and acceptance

| Work | Required outcome |
| --- | --- |
| **Signed distribution archive** | Produce and validate a correctly signed archive with provisioning and supported capabilities for both targets. [project.yml](../../apps/iosApp/project.yml#L76) disables signing by default, also at line 138; a distribution override is possible. This is a release configuration task, not proof of an inherent rejection. Confirm App Group entitlement/provisioning consistency with [iOS README](../../apps/iosApp/README.md#L106). |
| **Device evidence** | Complete the outstanding signed archive and physical-device checks in [RELEASE_GATES](RELEASE_GATES.md#L13). Exercise import/export, locked/unlocked state, permission denial, incoming shares, deletion, sync/revoke where shipped, and background/foreground transitions. Repository independent-audit gates are Chur's own requirements, not universal Apple mandates. |
| **Review access** | Supply clear fresh-install/vault instructions, any needed passphrase or QR examples, and reachable test resources for shipped sync/sharing. Explain discreet mode and its entry route. Use fictional review data; reviewers must not need personal production vaults. |
| **Metadata and age rating** | Prepare accurate screenshots, description, support URL, privacy answers, and age-rating questionnaire for the actual shipping feature set. The updated age questions have applied since 31 January 2026. [Age-rating requirement](https://developer.apple.com/news/upcoming-requirements/?id=02032026a). |

Confirm Apple Developer enrollment, accepted current agreements, copyright ownership, and bundled dependency licenses before distribution. StoreKit, alternative social-login support, and ATT are conditional on purchases, login, and tracking respectively; do not add them automatically. [Submission setup](https://developer.apple.com/app-store/submitting/), [Guidelines 3.1, 4.8, 5.1, 5.2](https://developer.apple.com/app-store/review/guidelines/).

Current SDK floor is **Xcode 26+ with iOS/iPadOS 26 SDK+**, enforced since **28 April 2026**. This is distinct from minimum deployment target. [Dated minimum](https://developer.apple.com/news/upcoming-requirements/?id=04282026a). The live submission page announces SDK 27+ and iOS deployment target 15+ **starting April 2027**, without an exact day; that is future guidance on this research date. [Submitting](https://developer.apple.com/app-store/submitting/).

## Privacy and encryption determinations

App Privacy answers must cover the release's complete data flow. Apple's collection definition concerns off-device transmission accessible to the developer or partners beyond servicing a request in real time. Local processing alone does not establish collection. E2EE alone does not establish `Data Not Collected`: assess ciphertext, readable metadata, credentials, IP/access logs, identifiers, diagnostics, recipients, and retention separately. [App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/).

[iOS routes](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosRoutes.kt#L1346) bind sync and sharing, with [visible cards](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/VaultShell.kt#L1270). The [trust model](../sync/SERVER_TRUST_MODEL.md#L134) does not describe a first-party hosted service. That narrows likely recipients but does not answer every privacy question about arbitrary server operators. Determine disclosures using actual shipped configuration and service relationships; do not invent telemetry or declare its absence without tracing dependencies.

Chur links [non-OS Rust cryptography](../../rust/crates/chur-crypto/Cargo.toml#L13). Apple requires an export determination even for standard algorithms. Its table distinguishes Apple-OS-only encryption, standard algorithms outside the OS, and proprietary algorithms; standard external encryption requires a French declaration when distributing in France. [Export documentation table](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption/).

The missing `ITSAppUsesNonExemptEncryption` key is not itself automatic rejection: ASC can ask the questions. Complete classification, select territories, and upload documents where required. The ASC questionnaire determines applicable exemptions and documentation case by case; this research does not establish Chur's final legal classification. Only then encode the correct boolean and, where issued, `ITSEncryptionExportComplianceCode`; do not set `NO` merely because algorithms are standard. Separate government reporting obligations may remain. [Encryption declaration](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations), [ASC workflow](https://developer.apple.com/help/app-store-connect/manage-app-information/determine-and-upload-app-encryption-documentation).

## Conditional obligations and remaining decisions

Account creation requires in-app initiation of account deletion, including automatically generated accounts; deactivate/disconnect alone is insufficient. Whether Chur server membership constitutes such an account needs explicit product/review treatment. Local vault creation alone does not settle this. [Account deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app/).

Declare DSA trader status in ASC. For traders distributing in the EU, Apple verifies and publishes address, phone, and email. The owner must self-assess commercial/professional activity; free distribution or individual enrollment does not determine the answer. [DSA requirements](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements).

If monetization is added, evaluate the selected storefront/payment model before implementation. Complete ASC's Digital Goods and Services questionnaire when it appears; Apple says no action is required when it does not. [Questionnaire](https://developer.apple.com/help/app-store-connect/manage-app-information/complete-the-digital-goods-and-services-questionnaire).

Existing permission flows are not grounds for a wholesale rewrite. Correct [IOS.md's claim](../IOS.md#L1419) that read/write Photos authorization is only for deletion: [ChurApp.swift](../../apps/iosApp/Sources/ChurApp.swift#L361) also explicitly requests it for Full Live Photo handling. Validate denied/limited access and explain the actual requested capability.

Open inputs are distribution territories, monetization, trader status, privacy/support hosting, and whether server collaboration ships in the first release. A local-first release is a possible explicit scope decision if collaboration obligations cannot yet be met; secretly hiding reachable features for review is not an acceptable substitute. No ASC account state or final archive was inspected, and no Apple acceptance is claimed.
