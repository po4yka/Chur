# Discreet Mode

> **Status:** Proposed product and platform privacy contract

Discreet Mode makes Chur unobtrusive during ordinary device use. It is not the cryptographic boundary and does not claim an undetectable hidden volume. The Rust vault, key hierarchy, and independent real/decoy identities provide data protection; Discreet Mode controls visible product surfaces.

## Goals

- provide a genuine, useful public application surface;
- prevent accidental exposure through navigation, recents, notifications, widgets, and deep links;
- switch to a locked public state quickly and predictably;
- support an independent decoy vault for coercive UI inspection;
- remain transparent to platform review and store policies.

## Non-goals

- hiding the product's vault functionality from Apple, Google, reviewers, or documentation;
- resisting filesystem forensics that can observe ciphertext volume;
- preventing an external camera from recording the screen;
- guaranteeing screenshot prevention on every platform;
- disguising malicious behavior or evading user consent.

## Public shell

The public shell must be functional rather than a static decoy screen. Initial direction is a Notes surface; later shells may include Journal or Calculator.

Public-shell requirements:

- data stored only in public Room/DataStore storage;
- no private object count, filename, date, album, thumbnail, or search state;
- independent navigation graph and dependency graph;
- usable after process restart without opening a vault;
- no code path that treats public content as private encryption metadata;
- backup policy documented independently from private-vault recovery.

## Session gate

A secret gesture, route, or credential may enter the vault unlock flow, but it must be:

- explicitly configured by the user;
- documented inside product settings/help;
- available to platform review;
- reversible without deleting private data;
- accessible without relying on ambiguous or unsafe gestures;
- separated from ordinary public-shell credentials.

Open item: the exact gesture and its discoverability model are decided here and nowhere else. [`../../DESIGN.md`](../../DESIGN.md) §31 routed the question to this section and no longer carries it.

The feature layer receives an opaque session result. It should not branch on `isReal` or `isDecoy`.

## Lock behavior

Locking must:

1. stop private playback and decoding;
2. invalidate Rust session handles;
3. clear private navigation state;
4. remove private overlays and dialogs;
5. clear private image/media caches;
6. replace the visible scene with the public shell or neutral lock surface;
7. prevent process restoration of the previous private route.

Panic lock performs the same security transition immediately. It is not a data deletion command.

## Launcher and icon presentation

Any alternate icon or launcher presentation must be user-selected, documented, reversible, and supported by platform APIs. The default store listing must accurately describe Chur as an encrypted private archive with optional discreet interfaces.

Do not dynamically remove all discoverable means of reopening or managing the feature.

## App switcher and recents

Before the OS captures a background snapshot:

- obscure private Compose/SwiftUI/UIKit content;
- show the public shell or a neutral cover;
- stop transient previews;
- avoid private titles in task/activity labels.

Android should use `FLAG_SECURE` on sensitive windows according to policy. iOS can cover scene snapshots but cannot promise universal screenshot prevention.

## Notifications

Notifications must be neutral and minimal. They must not contain:

- private filenames or media types;
- album names or contact identities;
- vault object counts;
- captions, EXIF, locations, or thumbnails;
- real/decoy identity.

Background ciphertext transfer may use generic status such as “Backup complete” only when the user enabled it and the wording does not reveal private structure.

## Widgets, search, shortcuts, and assistants

Private data is excluded from:

- launcher/home-screen widgets;
- system search and app suggestions;
- Siri/App Intents or Android shortcuts;
- clipboard previews;
- share-target suggestions;
- voice-assistant indexing;
- public deep-link parameters.

A future private widget requires a dedicated threat model and locked-state rendering contract.

## Deep links

Public deep links may navigate only to public content or a neutral session gate. Private identifiers, filenames, collection IDs, and search queries must not appear in URLs, intents, universal links, or restored navigation state.

## External displays and capture

Sensitive content should be suppressed on non-secure Android displays where supported. iOS capture/mirroring state can trigger a privacy overlay, but the OS and external cameras remain outside the strong guarantee.

## Decoy interaction

The decoy vault is governed by [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md). Discreet Mode must not leak the real vault through:

- timing or error copy that differs by credential type;
- recent routes;
- shared cache entries;
- backup status;
- notification counts;
- settings visible from the decoy session.

## Accessibility

Discreet behavior must remain accessible:

- secret triggers require an accessible alternative;
- screen readers must not announce hidden private controls on the public surface;
- privacy overlays must preserve a valid focus target;
- lock must not leave private semantics in the accessibility tree;
- authentication copy should be clear without revealing which vault is expected.

## Review and store compliance

Review notes must explain:

- how to reach the vault;
- how real and decoy sessions behave;
- what permissions are used;
- why neutral UI surfaces exist;
- that cryptographic and recovery features are user-facing and documented.

### Shared store answers

Both stores receive the same facts. These answers are owned here; `ANDROID.md` §37 and `IOS.md` §37 cite them and must not answer differently:

- the app stores user-selected media in an encrypted local vault, and the user chooses what enters it;
- no analytics or diagnostics leave the device by default;
- private media, metadata, and vault identity are never used for tracking or advertising, and are not linked to an account in v1;
- photo access is selection-only wherever the platform picker suffices;
- the vault, decoy vault, discreet presentation, alternate icon, and recovery flows are documented to review and reachable by a reviewer;
- the app uses standard cryptography as listed in `CRYPTOGRAPHY.md` §15.1, and answers export-compliance questions on that basis;
- deletion removes the encrypted object and its catalog rows, and no server copy exists in v1.

A change that makes any answer false is a release blocker, not a form update afterwards.

### Forbidden claims

Marketing and store listings must not claim:

- an independent audit before one exists;
- universal screenshot prevention;
- physical secure erase of flash storage;
- protection from a compromised or unlocked operating system;
- cryptographically undetectable plausible deniability;
- recoverability when the user chose device-bound-only storage;
- invisibility to the platform, the store, or a forensic examiner.

## Verification checklist

- cold launch opens public/locked state;
- background snapshot contains no private pixels or semantics;
- lock invalidates media readers;
- process death restores no private route;
- notifications remain neutral;
- widgets/search/shortcuts expose no private metadata;
- real and decoy sessions use separate stores and caches;
- alternate presentation is user-controlled and reversible;
- platform review can exercise all functionality.
