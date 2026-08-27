---
version: alpha
name: Chur Boundary Design System
description: A mobile-first design specification for Chur, a discreet local-first encrypted media archive built with Kotlin Multiplatform, Compose Multiplatform, thin Android and iOS shells, and a Rust-owned vault runtime. The visual language adapts monochrome precision, strict typography, hairline structure, restrained elevation, and machine-readable design tokens to a privacy-sensitive consumer application.
project: Chur
platforms:
  - Android
  - iOS
  - Kotlin Multiplatform
  - Compose Multiplatform
status: proposed
source_of_truth: DESIGN.md
source_of_truth_scope: visual and interaction contracts only
authority: rank 3 under the hierarchy in docs/README.md
related_documents:
  - README.md
  - docs/ARCHITECTURE.md
  - docs/CRYPTOGRAPHY.md
  - docs/ANDROID.md
  - docs/IOS.md

colors:
  light:
    primary: "#171717"
    on-primary: "#FFFFFF"
    accent: "#315EF7"
    accent-pressed: "#2448C9"
    accent-soft: "#E9EEFF"
    canvas: "#FAFAF9"
    surface: "#FFFFFF"
    surface-subtle: "#F4F4F2"
    surface-raised: "#FFFFFF"
    surface-sunken: "#EEEEEB"
    ink: "#171717"
    body: "#4F4F4B"
    muted: "#7C7C76"
    disabled: "#A7A7A1"
    hairline: "#E7E7E3"
    hairline-strong: "#CECEC8"
    focus: "#5B7CFF"
    selection: "#315EF7"
    selection-soft: "#DDE6FF"
    success: "#1F7A4C"
    success-soft: "#E7F5ED"
    warning: "#986600"
    warning-soft: "#FFF3D3"
    error: "#C93434"
    error-soft: "#FCE7E7"
    scrim: "#00000066"
    privacy-cover: "#FAFAF9"
  dark:
    primary: "#F5F5F3"
    on-primary: "#111111"
    accent: "#7D98FF"
    accent-pressed: "#A7B7FF"
    accent-soft: "#1C2852"
    canvas: "#0A0A0A"
    surface: "#111111"
    surface-subtle: "#171717"
    surface-raised: "#1D1D1D"
    surface-sunken: "#050505"
    ink: "#F5F5F3"
    body: "#C6C6C0"
    muted: "#8D8D86"
    disabled: "#62625D"
    hairline: "#2A2A2A"
    hairline-strong: "#444444"
    focus: "#9FB1FF"
    selection: "#7D98FF"
    selection-soft: "#253569"
    success: "#52AE7D"
    success-soft: "#123824"
    warning: "#D5AA52"
    warning-soft: "#3A2B0D"
    error: "#F06C6C"
    error-soft: "#451919"
    scrim: "#00000099"
    privacy-cover: "#0A0A0A"
  media:
    canvas: "#000000"
    chrome: "#0A0A0A"
    chrome-raised: "#171717"
    chrome-scrim: "#000000B8"
    chrome-scrim-soft: "#00000073"
    on-media: "#FFFFFF"
    on-media-muted: "#FFFFFFB8"
    hairline: "#FFFFFF24"
    selection: "#FFFFFF"
    danger: "#FF7B7B"
  brand:
    boundary-blue: "#315EF7"
    boundary-violet: "#7657F6"
    boundary-cyan: "#28B8A7"
    boundary-glow-soft: "#315EF71F"

typography:
  family:
    sans: "Geist Sans, Inter, SF Pro Text, Roboto, Noto Sans, system-ui, sans-serif"
    display: "Geist Sans, Inter, SF Pro Display, Roboto, Noto Sans, system-ui, sans-serif"
    mono: "Geist Mono, JetBrains Mono, SFMono-Regular, Roboto Mono, ui-monospace, monospace"
  display-large:
    fontSize: "40sp"
    fontWeight: 600
    lineHeight: "44sp"
    letterSpacing: "-1.20sp"
  display-medium:
    fontSize: "32sp"
    fontWeight: 600
    lineHeight: "36sp"
    letterSpacing: "-0.80sp"
  headline-large:
    fontSize: "28sp"
    fontWeight: 600
    lineHeight: "34sp"
    letterSpacing: "-0.56sp"
  headline-medium:
    fontSize: "24sp"
    fontWeight: 600
    lineHeight: "30sp"
    letterSpacing: "-0.36sp"
  headline-small:
    fontSize: "20sp"
    fontWeight: 600
    lineHeight: "26sp"
    letterSpacing: "-0.20sp"
  title-large:
    fontSize: "18sp"
    fontWeight: 600
    lineHeight: "24sp"
  title-medium:
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "22sp"
  title-small:
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
  body-large:
    fontSize: "17sp"
    fontWeight: 400
    lineHeight: "24sp"
  body-medium:
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "22sp"
  body-small:
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "18sp"
  label-large:
    fontSize: "15sp"
    fontWeight: 500
    lineHeight: "20sp"
  label-medium:
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
  label-small:
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: "16sp"
  caption-mono:
    fontSize: "11sp"
    fontWeight: 450
    lineHeight: "16sp"
  code:
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "18sp"

spacing:
  none: "0dp"
  hairline: "1dp"
  xxs: "2dp"
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "20dp"
  2xl: "24dp"
  3xl: "32dp"
  4xl: "40dp"
  5xl: "48dp"
  6xl: "64dp"
  screen-compact: "16dp"
  screen-medium: "24dp"
  screen-expanded: "32dp"

rounded:
  none: "0dp"
  xs: "4dp"
  sm: "6dp"
  md: "10dp"
  lg: "14dp"
  xl: "20dp"
  sheet: "24dp"
  pill: "999dp"

motion:
  instant: "0ms"
  press: "90ms"
  control: "120ms"
  state: "180ms"
  navigation: "240ms"
  sheet: "300ms"
  media-transform: "280ms"
  easing-standard: "cubic-bezier(0.2, 0, 0, 1)"
  easing-exit: "cubic-bezier(0.4, 0, 1, 1)"
  reduced-motion: "Replace spatial transforms with crossfades no longer than 120ms."

components:
  top-app-bar:
    height-compact: "56dp"
    height-expanded: "64dp"
    background: "canvas"
    divider: "hairline"
    typography: "title-large"
  bottom-navigation:
    visual-height: "64dp plus system inset"
    active: "ink"
    inactive: "muted"
    indicator: "2dp boundary line; no filled capsule by default"
  navigation-rail:
    width: "80dp"
    active-indicator: "primary"
  button-primary:
    minHeight: "48dp"
    background: "primary"
    text: "on-primary"
    typography: "label-large"
    rounded: "md"
  button-secondary:
    minHeight: "48dp"
    background: "surface"
    border: "hairline-strong"
    typography: "label-large"
    rounded: "md"
  button-destructive:
    minHeight: "48dp"
    background: "error"
    typography: "label-large"
    rounded: "md"
  icon-button:
    visualSize: "40dp"
    touchTarget: "48dp"
    rounded: "pill"
  text-field:
    minHeight: "52dp"
    background: "surface"
    border: "hairline-strong"
    focusBorder: "focus"
    rounded: "md"
  secure-field:
    minHeight: "56dp"
    revealAction: "Explicit and never enabled by default"
    errorBehavior: "Neutral authentication language; no real/decoy oracle"
  search-field:
    minHeight: "48dp"
    background: "surface-subtle"
    rounded: "md"
  segmented-control:
    minHeight: "40dp"
    background: "surface-subtle"
    selected: "surface-raised"
    rounded: "md"
  filter-chip:
    minHeight: "36dp"
    touchTarget: "48dp"
    selectedBackground: "ink"
    rounded: "pill"
  media-tile:
    background: "surface-subtle"
    rounded: "xs"
    selectionStroke: "2dp selection plus checkmark"
  album-card:
    background: "surface"
    border: "hairline"
    rounded: "lg"
    padding: "md"
  list-row:
    minHeight: "56dp"
    divider: "hairline"
    padding: "0dp lg"
  settings-row:
    minHeight: "56dp"
    background: "transparent"
  selection-toolbar:
    minHeight: "56dp"
    background: "surface-raised"
    border: "hairline"
  bottom-sheet:
    background: "surface-raised"
    roundedTop: "sheet"
    maxWidthExpanded: "640dp"
  dialog:
    background: "surface-raised"
    rounded: "xl"
    maxWidth: "480dp"
  snackbar:
    background: "surface-inverse"
    rounded: "md"
    durationPolicy: "Persistent for security-relevant actionable failures"
  progress-row:
    minHeight: "64dp"
    background: "surface"
    progressColor: "accent"
  integrity-banner:
    background: "warning-soft or error-soft"
    requirement: "Icon, heading, explanation, and explicit action"
  privacy-cover:
    background: "privacy-cover"
    content: "Neutral public or branded surface; never a private thumbnail"
  media-viewer-chrome:
    background: "media.chrome-scrim"
    text: "media.on-media"
    autoHide: "After inactivity, except while accessibility focus is active"
  audio-player:
    background: "surface"
    waveform: "Encrypted derived asset"
    controls: "48dp minimum targets"
  empty-state:
    maxTextWidth: "360dp"
    illustration: "Optional abstract boundary mark; no lock cliché"
---

# Chur Design System

> **Status:** proposed design direction  
> **Audience:** product design, KMP/CMP, Android, iOS, QA, accessibility, security, and agentic implementation contributors  
> **Related:** [README](README.md) · [Architecture](docs/ARCHITECTURE.md) · [Cryptography](docs/CRYPTOGRAPHY.md) · [Android](docs/ANDROID.md) · [iOS](docs/IOS.md)

Chur is a media-first private archive that can present a genuine public utility interface while keeping the encrypted vault behind an authenticated session. This document specifies how the product should look, behave, adapt, and communicate across Android and iOS.

The file deliberately follows the `design.md` pattern: machine-readable tokens first, followed by human-readable rationale, component behavior, screen guidance, responsive rules, accessibility requirements, and implementation constraints. It is an original Chur system, not a visual clone of Vercel.

The central visual rule is:

> **The interface supplies structure and confidence. User media supplies most of the color. Security supplies behavior, not decoration.**

---

## 1. Scope and authority

`DESIGN.md` is normative for:

- visual hierarchy and token semantics;
- shared Compose component appearance;
- public-shell, session-gate, private-vault, and decoy-vault presentation;
- compact, medium, expanded, landscape, and foldable composition;
- the presentation of privacy-sensitive transitions and errors whose behavior other documents define;
- accessibility, motion, content, and visual-regression expectations;
- prompts and constraints used by design or coding agents.

It is not a substitute for:

- the cryptographic specification;
- platform lifecycle and storage policy;
- byte-level protocols;
- App Store or Google Play policy review;
- user research or accessibility testing with assistive technology.

`DESIGN.md` is rank 3 in the authority hierarchy of [`docs/README.md`](docs/README.md#authority-hierarchy) and is normative for presentation only. Privacy-sensitive transitions, lock behavior, and error semantics are owned by [`docs/product/DISCREET_MODE.md`](docs/product/DISCREET_MODE.md), [`docs/security/PLAINTEXT_LIFECYCLE.md`](docs/security/PLAINTEXT_LIFECYCLE.md), and [`docs/ERROR_MODEL.md`](docs/ERROR_MODEL.md); this document specifies only how their required states appear.

When visual convenience conflicts with deterministic locking, non-oracular authentication, platform accessibility, or plaintext minimization, the security and accessibility invariant wins.

---

## 2. Source adaptation

The design direction adapts four useful traits from the Vercel-inspired `DESIGN.md` reference:

1. near-monochrome surfaces with precise neutral steps;
2. a 4-unit spacing rhythm;
3. geometric sans typography with restrained weights and optional mono technical labels;
4. hairlines and stacked, quiet elevation instead of generic heavy shadows.

Chur intentionally rejects the parts that do not fit a private mobile media product:

- marketing-page section bands as the routine application shell;
- pill-shaped primary actions everywhere;
- decorative gradients near authentication or private media;
- desktop-first navigation assumptions;
- browser hover as a necessary affordance;
- web typography values copied directly into Compose without Dynamic Type or font-scale testing.

---

## 3. Design thesis: Boundary Minimalism

The system is called **Boundary Minimalism**.

A boundary in Chur has three jobs:

- show where one surface or state ends;
- show what can be acted upon;
- show when the application has crossed from public to authenticated private context.

The boundary is expressed through:

- 1dp hairlines;
- measured spacing;
- restrained corner radii;
- clear surface polarity;
- compact selection strokes;
- an interrupted-frame brand mark;
- immediate privacy covers and lock-state transitions.

It is not expressed through:

- padlocks on every screen;
- glowing shields;
- hacker-terminal imagery;
- constant red/green security indicators;
- fake vault wheels;
- runes or mythological ornament.

### Core principles

1. **Media is the content.** The gallery grid should recede behind photos and video.
2. **Privacy has no spectacle.** Unlock, lock, integrity, and recovery are calm and precise.
3. **Public means credible.** Notes, journal, or calculator modes must look and behave like complete utilities.
4. **Locked means gone.** Private content is covered before animation and removed from restorable navigation state.
5. **Real and decoy look identical.** Visual or timing differences must not reveal the selected identity.
6. **Native where it matters.** Pickers, authentication, share sheets, keyboard behavior, and accessibility conventions stay platform-native.
7. **Adaptive by composition.** Larger screens add context and panes rather than scaling every element.
8. **Security states are explicit.** Locked, importing, committed, verification-required, corrupt, and recovery-required are not interchangeable spinners.
9. **Failure remains calm.** Explain the next action without exposing sensitive implementation details.
10. **Agent-operable.** Components and tokens have semantic names so automated contributors do not invent new visual languages per screen.

---

## 4. Product surfaces

Chur has four presentation domains.

### 4.1 Public shell

The public shell may be Notes, Journal, Calculator, Files, or another approved utility. It is a real workspace with its own data, navigation, empty states, editing behavior, and settings.

Requirements:

- no private counts, names, recents, thumbnails, or background tasks appear;
- no permanent lock, shield, or secret-vault affordance reveals the private function;
- public content does not adopt the media viewer palette;
- a public-shell change must not change the cryptographic identity;
- hidden access gestures must remain discoverable to the owner through onboarding and settings, not visible as suspicious permanent chrome.

### 4.2 Session gate

The session gate includes password, platform authentication, recovery, re-enrollment, migration, and lockout-safe error presentation.

Requirements:

- one focused task per compact screen;
- no private media behind fields, even blurred;
- no different copy, spacing, timing, color, or vibration for real and decoy credentials;
- biometric UI remains platform-native;
- password failure copy is neutral, for example `Unable to unlock`;
- recovery consequences are explained before destructive action.

### 4.3 Private vault

The private vault is a media-first application with Library, Albums, Search, Viewer, Import, and Settings.

Default compact navigation:

```text
Library · Albums · Search · Settings
```

Import is a contextual primary action, not necessarily a permanent fifth destination.

### 4.4 Privacy surfaces

Privacy surfaces include:

- app-switcher cover;
- screenshot/capture warning where supported;
- locked placeholders;
- neutral notifications;
- export disclosure;
- background-task status;
- panic lock.

A privacy cover must be visually complete before the private content can be snapshotted.

---

## 5. Brand identity

### 5.1 Name and voice

Use **Chur** in title case. Avoid `CHUR`, `Chur Crypto`, and `Chur Secure` in ordinary product UI.

The name suggests a boundary around what belongs to the user. The visual identity should imply ownership and authorized passage without literal folklore costume.

### 5.2 Mark direction

Preferred direction: an interrupted boundary or squared `C`.

```text
┌─────────┐
│       ┌─┘
│       │
└───────┘
```

The opening represents authorized passage, not weakness.

Do not use as the primary mark:

- padlock;
- shield;
- keyhole;
- fingerprint;
- crossed-out eye;
- camera inside a lock;
- rune collage;
- medieval gate;
- fake Cyrillic lettering.

### 5.3 Boundary Glow

The only optional decorative device is a large, low-opacity glow:

```text
boundary-blue → boundary-violet → boundary-cyan
```

Allowed:

- onboarding;
- product introduction;
- large brand previews;
- design-system demonstrations.

Forbidden:

- password and recovery fields;
- private media grids;
- public utility surfaces;
- integrity and success messages;
- routine app backgrounds;
- app-icon micro-gradients.

---

## 6. Color

### 6.1 Philosophy

```text
Neutral structure + user media + one boundary accent
```

Primary actions are near-black on light surfaces and near-white on dark surfaces. Blue is reserved for focus, selection, progress, links, and explicit active state.

This prevents Chur from becoming a generic blue dashboard and leaves the strongest chroma to user content.

### 6.2 Surface ladder

Light:

```text
canvas #FAFAF9
surface #FFFFFF
surface-subtle #F4F4F2
surface-sunken #EEEEEB
ink #171717
```

Dark:

```text
canvas #0A0A0A
surface #111111
surface-subtle #171717
surface-raised #1D1D1D
ink #F5F5F3
```

Media viewer:

```text
canvas #000000
chrome scrim #000000B8
content #FFFFFF
```

### 6.3 Semantic rules

- Locked state is neutral, not red.
- Successful biometric authentication does not trigger a large green celebration.
- Decoy state has no special tint.
- Integrity uncertainty is warning; confirmed corruption is error.
- Migration is neutral progress unless action is required.
- Empty and no-results states are neutral.
- Color is always paired with text, iconography, state semantics, or selection geometry.

### 6.4 Contrast targets

- ordinary text: at least 4.5:1;
- large text and essential icons: at least 3:1;
- focus indicators: at least 3:1 against adjacent surfaces;
- viewer controls: test against both black chrome and representative bright/dark media;
- selection: stroke plus checkmark, never color alone.

---

## 7. Typography

### 7.1 Typeface strategy

The system uses Geist-like geometric clarity while preserving multilingual coverage and native accessibility.

Preferred stack:

```text
Geist Sans → Inter → SF Pro / Roboto → Noto Sans → system sans
Geist Mono → JetBrains Mono → SF Mono / Roboto Mono → system mono
```

Requirements:

- verify glyph coverage for all shipped locales;
- use clean fallback for Cyrillic, Georgian, Arabic, CJK, and other scripts;
- prefer platform typography where it materially improves Dynamic Type or locale support;
- security-critical readability must not depend on a custom font;
- display weight stops at 600;
- body copy is never monospaced;
- mono is reserved for diagnostic IDs, format versions, and advanced technical details.

### 7.2 Hierarchy rules

- headings use sentence case;
- large headings use modest negative tracking;
- body copy uses normal tracking;
- routine labels are not all caps;
- metadata truncates only after preserving the most distinguishing information;
- filenames can wrap where disclosure is already intended;
- password and recovery material use secure text behavior, not typographic gimmicks.

### 7.3 Font scaling

Every screen must be tested at:

```text
100% · 130% · 160% · 200%
```

At large scale:

- controls grow vertically;
- labels wrap;
- bottom navigation may switch to rail or simplified labels;
- dialogs become full-screen sheets;
- media grids keep usable tiles rather than preserving column count;
- no essential action is clipped.

---

## 8. Layout and spacing

### 8.1 Grid

Use a 4dp base rhythm. The 2dp token is reserved for optical correction and hairline-adjacent adjustments.

Default horizontal gutters:

| Width class | Gutter |
|---|---:|
| Compact | 16dp |
| Medium | 24dp |
| Expanded | 32dp |

### 8.2 Compact

Typical phone portrait:

- single pane;
- bottom navigation;
- edge-to-edge media grid;
- sheets for contextual actions;
- one focused unlock or recovery task;
- viewer chrome over black media canvas.

### 8.3 Medium

Large phone landscape, small tablet, foldable posture:

- navigation rail where useful;
- two-column settings or albums;
- Library may show persistent filters;
- viewer metadata may use a side sheet;
- public Notes may expose list plus editor.

### 8.4 Expanded

Tablet and large landscape:

```text
Navigation rail | collection/list pane | detail/viewer pane
```

Rules:

- do not stretch compact cards to full width;
- cap forms at 480–640dp;
- cap body copy at readable measure;
- preserve media aspect ratio;
- allow viewer metadata to remain visible without covering content;
- add context rather than oversized empty space.

### 8.5 Foldables

- treat hinges as layout boundaries;
- do not place unlock fields or primary actions across a hinge;
- dual-pane Library/Viewer is preferred when posture allows;
- moving between postures must not surface private snapshots;
- lock remains global to the session, not per pane.

---

## 9. Shape, stroke, and elevation

### 9.1 Shape

Chur uses moderate radii:

- 4–6dp for media and precise utility surfaces;
- 10dp for fields and buttons;
- 14–20dp for cards and dialogs;
- 24dp for sheets;
- pills only for chips, compact status, and circular controls.

Primary actions are not marketing pills.

### 9.2 Hairlines

Hairlines are the primary structure:

- grouped row boundaries;
- field outlines;
- cards over same-tone backgrounds;
- selection and focus geometry;
- navigation separation.

Avoid nested borders that create visual noise.

### 9.3 Elevation

Use the smallest elevation that communicates modality.

| Level | Treatment | Use |
|---|---|---|
| 0 | no shadow | canvas, media grid, app bars |
| 1 | hairline only | cards, rows, fields |
| 2 | hairline + very soft stacked shadow | floating toolbar, dropdown |
| 3 | soft stacked shadow + scrim | sheet, dialog |

Do not use one generic heavy Material shadow on every card.

---

## 10. Navigation

### 10.1 Vault destinations

Compact default:

```text
Library
Albums
Search
Settings
```

Viewer, selection, import, export, recovery, and integrity are contextual flows.

### 10.2 Public shell navigation

Public navigation follows the selected utility. It must not resemble the private vault navigation so closely that it feels fake, but it remains part of the same token system.

Examples:

- Notes: folders/list/editor;
- Journal: timeline/calendar/editor;
- Calculator: single-purpose keypad with history;
- Files: locations/list/detail.

### 10.3 Navigation state and privacy

- private destinations are never restored after process death;
- a lock transition destroys private back-stack projections;
- notification and deep-link handling enters through the public/locked root;
- no deep link bypasses the session gate;
- viewer state is not rendered beneath the privacy cover during relaunch.

---

## 11. Library and media grid

### 11.1 Grid behavior

- media tiles are edge-efficient and visually quiet;
- grid gaps use 2–4dp depending on density;
- selection uses a 2dp outline plus checkmark;
- duration, Live/RAW/spatial indicators use compact overlays only when necessary;
- failed thumbnail and integrity states use deliberate placeholders;
- grid loading uses stable geometry to avoid layout jumps.

### 11.2 Timeline grouping

Group by meaningful time ranges:

```text
Today
Yesterday
August 2026
July 2026
Earlier
```

Do not expose precise timestamps in public surfaces or notifications.

### 11.3 Empty Library

Recommended structure:

```text
Boundary mark or neutral media placeholder
Your private library is empty
Import photos, videos, or audio stored on this device.
[Import]
```

Avoid security marketing copy after onboarding.

### 11.4 Selection mode

Selection replaces ordinary top actions with:

- count;
- select all where safe;
- album/move;
- export;
- delete;
- more.

Destructive actions require precise scope:

```text
Remove from album
Delete from this vault
Delete exported/source copy
```

These are never collapsed into one ambiguous `Delete`.

---

## 12. Albums and search

### 12.1 Albums

Album cards use media mosaics without decorative tint. The UI does not expose cryptographic Security Collections as ordinary Albums.

Album card content:

- cover mosaic;
- album name;
- item count;
- optional shared/status metadata when future sharing exists.

### 12.2 Search

Search is local and private.

Rules:

- query text is never persisted outside the unlocked session unless an explicit encrypted history feature is approved;
- no query appears in logs, screenshots, notifications, or analytics;
- suggestions do not leak real-vault content into public/decoy identity;
- loading and indexing states explain local processing without technical alarm;
- filters use chips with 48dp touch targets.

No-results copy:

```text
No matching items
Try another word or remove a filter.
```

---

## 13. Media viewer

### 13.1 Photo

- black canvas;
- media centered at correct aspect ratio;
- tap toggles chrome;
- zoom and pan follow platform expectations;
- metadata appears in a sheet or side pane;
- screenshots/capture policy remains explicit and platform-honest.

### 13.2 Video

- controls use native-feeling playback behavior;
- seek begins only after authenticated ranges are available;
- buffering distinguishes network/source wait from integrity failure;
- integrity failure stops playback and offers quarantine/verify action;
- duration and controls remain readable over varied content.

### 13.3 Audio

Audio viewer includes:

- title or safe fallback label;
- encrypted cover art or neutral artwork;
- waveform as an encrypted derived asset;
- elapsed/remaining time;
- play, seek, speed, and output controls;
- optional transcript only when explicitly enabled and encrypted.

### 13.4 Chrome auto-hide

Do not auto-hide while:

- TalkBack/VoiceOver focus is inside controls;
- keyboard navigation is active;
- a menu or sheet is open;
- a destructive confirmation is pending;
- an integrity warning needs acknowledgment.

---

## 14. Unlock and session gate

### 14.1 Unlock screen

Compact hierarchy:

```text
Chur or neutral shell identity
Short instruction
Secure field or native biometric action
Primary unlock action
Recovery / other method
Privacy-safe error region
```

The screen must not reveal:

- private item count;
- last opened album;
- real/decoy identity;
- exact reason a candidate slot failed;
- whether a different credential exists.

### 14.2 Biometrics

Biometric prompts are native. Chur may explain why authentication is requested before the system prompt, but must not draw a fake fingerprint/Face ID dialog.

### 14.3 Panic lock

Panic lock:

- is immediate;
- covers content before other animation;
- stops players and invalidates session handles;
- returns to the configured public surface;
- has no celebratory feedback;
- does not delete data.

### 14.4 Auto-lock settings

Use explicit choices:

```text
Immediately
After 30 seconds
After 1 minute
After 5 minutes
When device locks
```

Explain the interaction with background playback, imports, exports, and platform limitations.

---

## 15. Import

### 15.1 Entry

Use the system picker. Do not recreate the user's Photos or Files library inside Chur before permission is granted.

### 15.2 Progress

An import row shows:

- neutral item preview when safe;
- phase;
- byte or item progress where known;
- cancel action;
- privacy-safe failure.

Phases:

```text
Preparing
Encrypting
Verifying
Adding to library
Complete
```

Do not report success before the encrypted object and catalog transaction commit.

### 15.3 Original deletion

After success, present a separate choice:

```text
Keep original
Review source deletion
```

Do not imply secure physical erase. Explain that deletion is handled by the operating system and may remain in recently deleted, backups, or other copies.

### 15.4 Derived assets

Thumbnail, preview, poster frame, waveform, OCR, or index generation may continue after the original commits. UI distinguishes:

```text
Original protected
Preview still processing
```

A derivative failure must not look like loss of the original.

---

## 16. Export and sharing

### 16.1 Disclosure

Export creates plaintext outside the vault. This consequence is stated before the first export and in sensitive destinations.

Recommended copy:

```text
The exported copy is no longer protected by Chur. The destination app or service may retain it.
```

### 16.2 Destination flow

Use native share/save UI. Chur does not draw a fake destination picker.

### 16.3 Progress and failure

- show verification before exposure where policy requires complete verification;
- delete or revoke partial output when the destination allows it;
- distinguish cancellation from integrity failure;
- do not expose scratch paths or filenames in errors.

### 16.4 Future encrypted sharing

Encrypted collection sharing is visually distinct from plaintext export:

```text
Export a copy
Share encrypted access
```

The latter remains deferred until identity and sharing protocols are implemented and audited.

---

## 17. Recovery

Recovery screens use more space and more explicit explanation than routine settings.

### 17.1 Recovery setup

Flow:

1. explain what recovery can and cannot restore;
2. require device authentication;
3. reveal mnemonic/QR only on explicit action;
4. prevent accidental screenshots only where platform support is honest;
5. ask the user to verify saved material;
6. confirm that the recovery slot committed;
7. offer secure exit.

### 17.2 Recovery material

- never truncate;
- never auto-copy;
- clearly label copy risk;
- use readable grouping and checksum feedback;
- offer accessible spoken alternatives without reading secrets automatically;
- clear from UI and clipboard according to explicit policy.

### 17.3 Irrecoverable mode

Device-bound-only mode needs a high-friction confirmation explaining that device loss, uninstall, or key invalidation may permanently destroy access.

Do not use vague copy such as `Maximum security` without loss semantics.

---

## 18. Real and decoy vaults

The real and decoy experiences must share:

- theme;
- component geometry;
- typography;
- unlock timing envelope;
- navigation structure;
- error wording;
- haptics;
- loading behavior;
- empty and settings states.

They must not share private data or caches, but the design must not reveal that separation.

Forbidden:

- decoy badge;
- different accent;
- fewer navigation destinations by default;
- `Demo` or `Safe mode` label;
- visibly shorter authentication delay;
- special exit animation;
- obvious sample-content styling.

Decoy content should be credible and editable, not a static gallery of stock images.

---

## 19. Public Notes, Journal, and Calculator

### 19.1 Notes

- familiar list/detail model;
- true create, edit, delete, search, and folders/tags as product scope allows;
- no private-vault metadata in suggestions;
- standard text-editor spacing and selection;
- calm neutral empty state.

### 19.2 Journal

- date-oriented entries;
- optional calendar/timeline;
- restrained editorial typography;
- no accidental import of private media without session authentication.

### 19.3 Calculator

- credible keypad geometry;
- 48dp minimum targets;
- history only if it behaves like a real calculator;
- no stereotypical `enter secret PIN and press equals` as the only access mechanism;
- secret access is configured, documented to the owner, and does not compromise ordinary calculations.

### 19.4 Consistency boundary

Public utilities use Chur tokens but preserve domain conventions. The calculator may use a denser dark surface; Notes may use warm light canvas. They should not all look like the vault with labels changed.

---

## 20. Integrity and corruption

### 20.1 State hierarchy

```text
Verification recommended
Verification in progress
Verified
Incomplete
Corrupt
Quarantined
Unsupported format
Migration required
```

Each state has distinct language and actions. These are presentation names, not stored values: each one is derived from the object row's `state` and `integrity_summary` by the table in [`docs/format/CATALOG_SCHEMA_V1.md`](docs/format/CATALOG_SCHEMA_V1.md) §5.1, which owns both enums. A name that does not appear in that table is not renderable.

### 20.2 Integrity banner

A banner contains:

- icon;
- concise heading;
- one-sentence explanation;
- primary next action;
- optional details link.

Examples:

```text
Verification required
This item has not been checked completely on this device.
[Verify]
```

```text
Item could not be verified
Chur stopped reading this item because its encrypted data is incomplete or altered.
[Quarantine] [Details]
```

Do not show stack traces, chunk numbers, object keys, paths, or cryptographic jargon in default UI.

### 20.3 Quarantine

Quarantined objects remain visually separated from the normal library and are never silently retried in viewers. Recovery/repair actions explain whether they can alter the object.

---

## 21. Loading, empty, and offline states

### Loading

- preserve final geometry;
- use subtle neutral skeletons for media grids;
- avoid pulsing effects under reduced motion;
- never render an old private thumbnail while a new session loads.

### Empty

Explain the available action without marketing repetition.

### Offline

A local vault remains usable. Offline is not an error unless a requested remote operation depends on connectivity.

### Locked background work

Ciphertext upload/download may continue in future versions, but UI must not imply that private media is currently decrypted.

---

## 22. Motion and haptics

### 22.1 Motion principles

- lock first, animate second;
- opacity and scale changes are subtle;
- no parallax on authentication;
- no spring bounce for integrity or recovery;
- media shared-element transitions must not delay privacy cover;
- navigation motion respects platform direction and back behavior.

### 22.2 Durations

- press: 90ms;
- simple state: 120–180ms;
- navigation: about 240ms;
- sheets: about 300ms;
- media transform: about 280ms;
- privacy cover: immediate or the shortest platform-safe transition.

### 22.3 Reduced motion

When reduced motion is enabled:

- replace spatial transforms with short crossfades;
- disable decorative glow animation;
- avoid zooming media into place;
- keep progress numerically or textually understandable;
- never remove state feedback entirely.

### 22.4 Haptics

Use platform haptics sparingly:

- selection mode entered;
- destructive action confirmed;
- unlock accepted through platform auth;
- critical integrity failure.

Do not create different real/decoy haptics.

---

## 23. Accessibility

### 23.1 Touch targets

- Android/shared Compose interactive target: at least 48×48dp;
- iOS-native shell controls: at least 44×44pt;
- visible icon may be smaller inside the target;
- adjacent media selection targets must not overlap ambiguously.

### 23.2 Screen readers

Examples:

```text
Photo, selected, taken 14 August 2026
Video, 2 minutes 18 seconds, not selected
Importing item 3 of 12, 46 percent
Vault locked
Verification required, action available
```

Do not announce:

- cryptographic object IDs;
- private path names;
- real/decoy identity;
- hidden access gestures on public screens;
- complete recovery material without explicit user action.

### 23.3 Focus

- every modal traps focus appropriately;
- focus returns to the invoking control;
- privacy cover receives focus before background snapshot where feasible;
- viewer auto-hide pauses during accessibility focus;
- hardware keyboard traversal is logical on tablets.

### 23.4 Accessible authentication

Do not require memory, transcription, or puzzle solving as the only authentication path. Support password managers and platform credential behavior where compatible with the threat model. Explain errors without revealing which secret was close or valid for another identity.

### 23.5 Contrast and non-color cues

All selection, integrity, destructive, and disabled states require a non-color cue.

---

## 24. Localization

- allow 30–40% text expansion;
- support RTL mirroring for navigation and directional icons;
- do not mirror media itself;
- verify Cyrillic and Georgian samples early;
- use locale-aware dates while keeping cryptographic/diagnostic IDs locale-independent;
- avoid concatenated sentence fragments;
- pluralize item counts correctly;
- security and recovery copy requires human review in every shipped locale.

Test strings should include:

```text
Русский: Защищённая библиотека
ქართული: დაცული ბიბლიოთეკა
Arabic RTL sample
Long German action labels
CJK filename and album names
```

---

## 25. KMP and Compose implementation

### 25.1 Source of tokens

The frontmatter is documentation-first. Production tokens should be represented as typed common code, generated from a dedicated token source or kept synchronized by tests.

Conceptual API:

```kotlin
@Immutable
data class ChurColors(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val body: Color,
    val accent: Color,
    val hairline: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val privacyCover: Color,
)

@Immutable
data class ChurDimensions(
    val screenGutter: Dp,
    val touchTarget: Dp,
    val cardRadius: Dp,
    val sheetRadius: Dp,
)

@Composable
fun ChurTheme(
    darkTheme: Boolean,
    mediaMode: Boolean = false,
    content: @Composable () -> Unit,
)
```

### 25.2 Material mapping

Material 3 may provide semantics and primitives, but Chur owns final tokens.

```text
Material colorScheme.primary       ← Chur primary
Material colorScheme.secondary     ← Chur accent
Material colorScheme.background    ← Chur canvas
Material colorScheme.surface       ← Chur surface
Material colorScheme.outline       ← Chur hairline-strong
Material colorScheme.error         ← Chur error
```

Do not accept dynamic color automatically for private and public surfaces. It could weaken identity consistency, contrast, real/decoy equivalence, or screenshot-review predictability. Dynamic color requires a separate ADR and security/design review.

### 25.3 Component ownership

Shared Compose owns:

- token application;
- ordinary app bars/navigation;
- media grid and album components;
- unlock explanatory content;
- settings and progress presentation;
- common privacy and integrity surfaces.

Native shells own:

- BiometricPrompt / LocalAuthentication UI;
- system pickers;
- share/save destinations;
- permission prompts;
- task/scene privacy integration;
- native media output and platform-only accessibility details.

### 25.4 Platform divergence

Permitted differences:

- back gesture and app-bar affordances;
- sheet detents;
- system typography fallback;
- native context menus;
- keyboard and pointer conventions;
- platform authentication wording controlled by the OS;
- iOS toolbar placement versus Android top/bottom actions.

Forbidden divergence:

- different real/decoy styling;
- different integrity semantics;
- different plaintext-export disclosure;
- different destructive scope terminology;
- different token meaning.

### 25.5 Insets and edge-to-edge

- content consumes safe areas/system bars intentionally;
- media viewer is edge-to-edge;
- fields and primary actions never hide under IME or home indicator;
- bottom navigation includes system inset;
- privacy cover fills the entire scene/window, including system-bar-adjacent area where possible.

---

## 26. Component behavior summary

### Buttons

- primary: one per decision surface;
- secondary: neutral outline or raised surface;
- destructive: reserved for irreversible or externally consequential action;
- text button: tertiary action only;
- loading button retains width and label context;
- disabled state remains readable and explains prerequisites where necessary.

### Fields

- persistent label where ambiguity is costly;
- helper/error text reserves layout space when feasible;
- password reveal is explicit and accessible;
- clipboard actions for secrets are deliberate;
- autofill/password-manager policy is documented per field.

### Chips and segmented controls

- chips filter or represent optional compact state;
- segmented controls switch views of one conceptual dataset;
- neither replaces bottom navigation;
- selected state uses geometry and semantics, not only tint.

### Sheets and dialogs

Use sheet for contextual actions and progressive detail. Use dialog for short, high-consequence decisions. Use full-screen flow for recovery, migration, onboarding, and complex import/export.

### Snackbars

Security-relevant actionable errors remain visible until dismissed or acted upon. Routine confirmations may time out. Snackbar text never includes private filenames unless already visible and necessary.

---

## 27. Content and voice

Chur copy is:

- direct;
- factual;
- calm;
- consequence-aware;
- free of military/security hype;
- precise about local versus external copies.

Prefer:

```text
Unable to unlock
Try again or use recovery.
```

Avoid:

```text
Access denied: invalid cryptographic secret!
```

Prefer:

```text
Delete from this vault?
This removes Chur's local key and encrypted object. Copies exported elsewhere are not affected.
```

Avoid:

```text
Permanently shred file
```

Prefer:

```text
Item could not be verified
```

Avoid:

```text
Security breach detected
```

---

## 28. Do and do not

### Do

- let media carry color;
- use near-monochrome surfaces and one accent;
- use hairlines before shadows;
- maintain 48dp shared touch targets;
- cover private content before animation;
- make real and decoy visually identical;
- distinguish range playback from full verification in copy where relevant;
- explain plaintext export and recovery loss semantics;
- add panes on large screens instead of giant cards;
- use native authentication, picker, and share UI;
- test large text, RTL, screen readers, keyboard, and reduced motion;
- make every loading and error state privacy-safe.

### Do not

- use locks, shields, fingerprints, or runes as repeated decoration;
- put gradients behind authentication or media;
- style primary buttons as oversized web-marketing pills;
- store private state in previews, screenshots, navigation restoration, or logs;
- differentiate real and decoy through UI;
- use dynamic color without a separate decision;
- rely on color alone;
- claim secure erase, universal screenshot prevention, or undetectable hidden storage;
- create fake system prompts;
- show stale thumbnails during session transitions;
- make every card elevated;
- let agents invent new colors or radii for one screen.

---

## 29. Design-agent implementation prompt

Use the following as a baseline for design or coding agents:

```text
Implement this Chur screen in Kotlin Multiplatform and Compose Multiplatform.

Use DESIGN.md as the source of truth for presentation only. Resolve every other conflict with the authority hierarchy in docs/README.md: byte-exact format specifications first, then accepted ADRs, then the focused security, interop, assurance, sync, and product specifications, then docs/CRYPTOGRAPHY.md, then docs/ARCHITECTURE.md.

Behavior this screen must present but does not define is owned by:
- docs/product/DISCREET_MODE.md — public shell, session gate, and external surfaces;
- docs/security/PLAINTEXT_LIFECYCLE.md — when plaintext may exist and when it is destroyed;
- docs/ERROR_MODEL.md — stable error identities, redaction, and retry behavior;
- docs/ARCHITECTURE.md — ownership, sessions, and lock lifecycle.

Constraints:
- KMP/CMP owns shared UI and state presentation.
- Native Android/iOS APIs remain responsible for authentication, pickers, sharing, and task/scene privacy.
- Rust remains authoritative for private data, integrity, sessions, and object state.
- Use Chur semantic tokens; do not introduce local hex colors, spacing, radii, or typography.
- Keep the UI monochrome and let media provide color.
- Maintain at least 48dp shared touch targets and platform accessibility semantics.
- Support compact, medium, and expanded layouts.
- Cover private content before any lock/background animation.
- Do not expose real/decoy identity, private filenames in public state, cryptographic details, or secret values.
- Include loading, empty, error, locked, large-text, reduced-motion, and screen-reader states.
- Use platform-native authentication, picker, and share surfaces instead of drawing imitations.
```

Screen-specific prompts must add:

- target destination and width classes;
- state model;
- security consequences;
- expected platform divergence;
- accessibility labels;
- screenshot-test cases.

---

## 30. Design QA matrix

Every feature PR affecting UI should cover applicable rows:

| Area | Required checks |
|---|---|
| Themes | light, dark, media mode |
| Width | compact, medium, expanded, landscape/foldable where relevant |
| Text | default, 130%, 160%, 200% |
| Locale | English, Russian, Georgian, RTL sample, long-string sample |
| Input | touch, keyboard, pointer where supported |
| Accessibility | TalkBack, VoiceOver, focus order, content descriptions, contrast |
| Motion | normal and reduced motion |
| Session | public locked, unlocking, unlocked, locking, process restoration |
| Identity | real and decoy visual equivalence |
| Privacy | app switcher, notifications, screenshot/capture policy, stale cache |
| Data | loading, empty, large library, missing preview, corrupt/quarantined item |
| Operations | import, cancel, commit, export, failure, cleanup |
| Platform | Android native shell and iOS native shell |

Screenshot/golden tests should never commit genuine private user data. Use deterministic synthetic fixtures.

---

## 31. Design decisions still requiring ADR or prototype

1. Final primary app icon and interrupted-boundary geometry.
2. Public shell shipped at launch: Notes, Journal, Calculator, or multiple choices.
3. Exact owner access gesture and discoverability model.
4. Whether custom Geist fonts are bundled or only used as optional Latin display fonts.
5. Dynamic color policy.
6. Exact compact navigation destinations and import placement.
7. Library grid density and adaptive column algorithm.
8. Shared-element media transitions versus simpler privacy-first navigation.
9. Screenshot/capture warning behavior on iOS.
10. Alternate launcher/icon policy on both platforms.
11. Multi-window and multi-scene behavior.
12. Recovery mnemonic/QR visual encoding.
13. Search and future local-AI presentation.
14. Live Photo, RAW pair, spatial media, and compound-object badges.
15. Background operation presentation while locked.
16. Public and decoy sample-content onboarding.
17. Tablet three-pane breakpoints.
18. Exact semantic colors after contrast testing on real displays.
19. Haptic policy.
20. Design-token code generation and linting.

---

## 32. References

This document is an original adaptation informed by:

- [awesome-design-md](https://github.com/VoltAgent/awesome-design-md) — the `DESIGN.md` convention and machine-readable-plus-rationale structure;
- [Vercel design analysis](https://getdesign.md/vercel/design-md) — monochrome precision, typography, spacing, hairlines, and restrained elevation;
- [Vercel-inspired DESIGN.md source](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/vercel/DESIGN.md);
- [Geist](https://github.com/vercel/geist-font);
- [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps);
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/);
- [Apple privacy and human-interface guidance](https://developer.apple.com/design/human-interface-guidelines/privacy).

The references guide presentation and documentation structure. Chur's security behavior, product model, KMP implementation constraints, real/decoy equivalence, and privacy lifecycle come from the project's own architecture.

---

## 33. Summary

Chur uses a calm, media-first visual system built from:

```text
near-monochrome precision
+ user media as primary color
+ one boundary accent
+ strict typography
+ a 4dp rhythm
+ hairlines before shadows
+ adaptive Compose layouts
+ native platform behavior
+ explicit privacy and integrity states
```

The public shell must be credible. The private vault must be quiet and efficient. Real and decoy identities must be visually indistinguishable. Locking must remove private content before motion. Recovery and export must explain consequences precisely. Android and iOS should feel native without becoming separate products.

When decoration conflicts with clarity, clarity wins. When convenience conflicts with privacy, privacy wins. When a shared abstraction conflicts with platform accessibility, accessibility wins.
