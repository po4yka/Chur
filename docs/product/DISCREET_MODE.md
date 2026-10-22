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

The public shell is a daily-use application. Authenticated Settings offers Notes, Calculator, Camera, and Chur. Public Settings contains appearance, public-app help, data storage, and About. Notes is the default. The choice is stored in public device preferences, is the same for every vault identity, and survives a restart. Switching screens does not delete Notes, public photos, or vault data. An unreadable or unknown choice falls back to Notes.

Public-shell requirements:

- data stored only in the public shell's own platform storage;
- no private object count, filename, date, album, thumbnail, or search state;
- independent navigation graph and dependency graph;
- usable after process restart without opening a vault;
- no code path that treats public content as private encryption metadata;
- backup policy as stated in "Public-shell disclosure and backup" below.

### Calculator

The basic calculator preserves immediate execution, percentages, repeated equals, and a limit of 12 significant digits. Scientific mode has a separate expression draft and mathematical precedence, parentheses, powers, roots, logarithms, trigonometry, constants, and DEG/RAD. Domain errors and overflow are recoverable. Copy, number paste, hardware keyboard input, and a local decimal separator are supported. Public history retains at most 500 calculations, with reuse and deletion. Input drafts are not saved across process death. A recognized transition code is consumed before calculation or history storage.

### Notes

Notes keeps the existing Markdown format, attachments, folders, tags, pinned notes, trash, drafts, and version history. Search and text editing are primary. Organization, import, export, and version controls are in secondary panels. Failed writes retain the draft for retry. A migration must not replace an unsupported document version.

### Camera and public album

Android uses CameraX; iOS uses AVFoundation. The preview starts after camera permission. Photo and video capture save automatically to the local public album. JPEG photos keep the supported capture resolution. Video uses H.264 with sound when microphone access is granted; recording without sound is explicitly indicated. Prefer 1080p at 30 frames per second when supported, with lower supported resolution as fallback. The first video with sound requests microphone access. The gallery supports thumbnails, photo zoom, video playback, selection, export, and confirmed deletion. System-library export and sharing require separate actions.

The album remains under Android `filesDir/public/camera/` or iOS `Documents/public/camera/`. Existing JPEGs remain readable. There is no 100-photo or 256 MiB user quota. Capture checks available storage, uses pending files, validates the completed file, and moves it atomically into the album. Failed finalization retains the pending file for retry. Backgrounding stops recording and releases capture resources. A success message follows confirmed local persistence. Thumbnails have bounded dimensions; video bytes do not cross the Kotlin/Swift bridge.

Camera hardware, lenses, frame profiles, audio, orientation, interruption, and exported-file playback require physical-device acceptance on both platforms. Simulator checks cannot establish those results.

Every public screen has a visible Settings control. Settings is a separate route: no Notes editor or camera preview remains composed behind it. No shell reveals a private count, identity, route, or thumbnail.

### Chur pet and onboarding

Chur is a local pet scene with greeting, dragging, treats, a movable toy, and
sleep. It has no progression or needs. State ends with the screen session.
The first-launch guide offers all four applications, then explains creation,
recovery, library controls, and settings. It can be skipped. Existing users keep
their selected application and can start tutorials manually.

The first-launch shell choice may write only that public preference, once,
before the introductory step completes. All later changes require authenticated
Settings. Public help contains only public lessons. Private tutorials never
start automatically after discreet mode is enabled. Tutorial status is excluded
from backup and does not record a private route, identity, or secret.

### Public-shell disclosure and backup

Public-shell content is not vault-protected. It is ordinary application data in public storage, readable by anyone holding the unlocked device, and it enters the platform backup. The product deliberately asks the user to keep real content there, because a shell that is not genuinely used is not a shell, so the application must say what that costs:

The optional whole-app gate controls access through the interface. It does not encrypt public-shell content or change its backup policy.

- the first time the user writes public-shell content, the application states once that this content is not encrypted by this app and can enter system backup;
- public-shell settings carry the same statement permanently under Data storage;
- the copy must not present the disclosure as a security feature and must not imply that the public shell is private.

Public-shell storage is included in the ordinary platform backup and private-vault storage is excluded from it without exception. Both halves are deliberate: a Notes surface that loses its content on device transfer is not functional, and an object store carried by a transport that chooses what to copy is not restorable. The two rules apply to disjoint directories and are implemented by [`../ANDROID.md`](../ANDROID.md) §13.4 and [`../IOS.md`](../IOS.md) §14.1. A change that places a vault path in a backup include set is a release blocker, and no public-shell backup rule may be widened to a path outside public storage.

Settings that only an open vault can write (the device-slot policy, the whole-app lock and the auto-lock time) are not public-shell content. Their presence would show that a vault was set up, so Android keeps them in `noBackupFilesDir` and not in `public/`. A device that restores a backup starts with their defaults. Android cloud backup also requires end-to-end encryption by the transport (`disableIfNoEncryptionCapabilities`), so the public shell is not sent to a server that can read it.

Public statements describe public storage only. Private Settings explains backup exclusion and encrypted archives. Explicit full/selective archives use [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md).

## Session gate

A secret gesture, route, or credential may enter the vault unlock flow, but it must be:

- explicitly configured by the user;
- documented inside product settings/help;
- available to platform review;
- reversible without deleting private data;
- accessible without relying on ambiguous or unsafe gestures;
- separated from ordinary public-shell credentials.

### Configured transition code

This contract replaces the earlier requirement for a permanently visible private entry. New and existing installations retain the visible setup entry until the user explicitly enables Discreet Mode from authenticated Settings. Setup records a pending digest, locks the session, and requires an actual public-app trigger followed by successful vault authentication before enabling the mode. A failed or cancelled trial does not enable it.

One user-selected eight-digit code applies to all four apps:

- Calculator: enter the exact digits, then press equals.
- Notes: enter the exact digits in search, then submit the search.
- Camera: hold the gallery thumbnail, enter the code, then submit.
- Chur: hold the moon, enter the code, then submit. A short tap changes sleep.

Recognition occurs only on submission. A recognized code is not written to calculation history, saved UI state, analytics, or logs. Code configuration is versioned and stores a digest, outside public backup. It is a navigation preference, not a cryptographic credential.

About offers a fallback: hold Version, or use its equivalent accessibility action, to open normal authentication. Setup documents this route before the trial. The fallback does not bypass vault authentication. All long-press actions have TalkBack/VoiceOver actions. The OS, product documentation, and store listing can still disclose app identity and features.

The feature layer receives an opaque session result. It should not branch on `isReal` or `isDecoy`.

The vault unlock and recovery screens offer a visible return to the selected public screen, including while authentication is pending. Returning invalidates that attempt: a delayed credential result must not reopen private content. The optional whole-app gate has no public-screen bypass; its recovery screen may return only to that gate.

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

Both ordinary and panic lock conceal private content and clear feature projections before waiting for native work to drain. Pending creation, recovery, and private metadata results belong to the session epoch that requested them and must not restore a phrase, private route, or projection after that epoch ends. Native lock completion remains necessary before another session may be used.

After the concealment, an ordinary lock saves an open private note with unsaved text before the native close. The save must start within 2 seconds; a native call that already started finishes first ([Private notes](PRIVATE_NOTES.md)). Panic lock does not wait for a save: it ends the save wait of an earlier lock, starts no save, and the unsaved text is lost. Only a native call that is already in progress delays the panic close.

### The panic gesture

The panic transition is bound to **a long press on the lock control**, and an ordinary press performs the ordinary lock. The lock stays visible when viewer controls are hidden. Private dialogs, sheets, and popup menus also offer Lock and hide, so hiding content never requires dismissing another surface first.

It satisfies the same six requirements the session gate carries: the control is visible and labelled, and Settings explains the press and hold actions. Neither action waits for a confirmation or a save before concealing the screen. Both run the same security transition; long press and Lock and hide identify the panic reason rather than a weaker or stronger authentication boundary.

Accessibility: a long press is exposed as a custom accessibility action on the same control, named for what it does. That is the accessible alternative the section below requires, and it is an alternative rather than a second mechanism.

The reason reaches Rust as `LockReason::PANIC`, which the vault records and treats identically. A reason that changed the transition would make panic a different operation, and this section says it is not.

### Android Quick Settings lock

Android offers an optional **Lock Chur** tile. The user adds it from private Settings or the system Quick Settings editor. Android 13 and later show the system add-tile request; Android 10–12 use the editor. A refusal leaves the tile absent and the user can try again later.

A tap performs the same panic lock described above, including while a picker or other host activity holds off automatic background locking. It covers the private window first, stops desktop control, and closes the process session. It does not open the app, authenticate, unlock the device or vault, or delete stored content. It can therefore run from a locked device without an authentication challenge.

The tile has one fixed action label, icon, and inactive action state. It must not disclose whether a vault exists or is open, its identity, object counts, or public Notes content. A process with no existing host has no open session; the tap does nothing and must not create a runtime or storage. Repeated taps are safe. The tile uses the same process host as the activity and sync worker; it must not create a separate session.

## Launcher and icon presentation

The default launcher presentation uses the Chur name and silver mirror-charm icon; the public shell remains Notes.

Any alternate icon or launcher presentation must be user-selected, documented, reversible, and supported by platform APIs. The default store listing must accurately describe Chur as an encrypted private archive with optional discreet interfaces.

Android switches among user-selected launcher activity aliases with public names and icons. iOS uses alternate icons and retains the installed app name. The About fallback remains available. OS settings, installed-package information, existing pinned shortcuts, and store records can retain product identity; this mode makes no concealment promise for them.

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

[App icon actions](APP_ICON_ACTIONS.md) use New note, Calculator, Camera, and Chur in Discreet Mode. Outside that mode, the four generic private capabilities remain: Import, Search, Lock and hide, and New private note. They disclose no private data or session state. Private navigation uses the normal session gate; Lock and hide performs panic lock and displays the public shell or the enabled whole-app gate. No shortcut carries content, private identity, or authentication authority.

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
- photo access for import is selection-only; library access is requested only when the user asks Chur to delete an imported original, and declining it leaves the import as it was;
- local network access is requested only when Chur connects to or syncs with a sync server on the user's own network that the user set up, and declining it affects only that server;
- on Android, when the user taps the system security row in the vault's Settings, Chur fetches the public Android vulnerability report from `android-api.osv.dev` to compare it with the device's patch level; the request sends no user data and no identifier, and it never runs without that tap ([`../adr/0059-permit-user-started-requests-for-public-platform-security-data.md`](../adr/0059-permit-user-started-requests-for-public-platform-security-data.md));
- the vault, decoy vault, discreet presentation, alternate icon, and recovery flows are documented to review and reachable by a reviewer;
- the app uses standard cryptography as listed in `CRYPTOGRAPHY.md` §6, and answers export-compliance questions on that basis;
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
- platform review can exercise all functionality;
- the public-shell disclosure appears on the first public write and is present in public-shell settings;
- a real backup and restore run carries public-shell content and no vault path.
