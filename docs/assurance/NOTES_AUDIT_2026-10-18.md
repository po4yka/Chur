# Public and private notes audit — 2026-10-18

> Historical snapshot from before the Markdown implementation. Public persistence fixes and the shared Markdown source editor/preview have since shipped, using the approved multiplatform-markdown-renderer 0.46.0 and code highlighting. See the [current notes contract](../product/MARKDOWN_NOTES.md) for implemented behavior, schema/ABI, limitations, and validation boundaries. Rich attachments, OCR, WYSIWYG editing, retained history, and synchronization remain future work; recommendations and validation results below describe the original research date.

Scope: source audit of `main` at `560e18521e30e8f4fcbaff7430cd14dee0e49ec3`, covering the public Notes shell and notes inside either selected vault identity. This report proposes product and architecture changes; it does not implement them, approve new dependencies, or change existing privacy policy. The working tree was clean when inspection started. Unrelated concurrent changes appeared during the audit and were preserved; the observed test runs are working-tree results, not certification of a frozen commit. The inspected diffs in `ChurController` and `VaultRepository` concerned media/album import identifiers, not the note methods discussed here.

## Assessment

Both editors currently provide **plain text**, not Markdown rendering or rich-text editing. Private notes have a substantially stronger storage and lifecycle implementation than the public shell. Neither surface approaches Apple Notes feature coverage yet. Adding a Markdown renderer alone would leave editing, attachments, organization, recovery, interoperability, and synchronization unfinished.

Recommended direction: preserve Markdown source as the authoritative text representation, deliver a capable source editor and native preview first, and add visual editing only when its supported constructs survive round trips. Share presentation and document behavior while retaining independent public and private persistence and session policies. For an immediately rich visual editor, evaluate a bundled Milkdown editor against that native approach before choosing; do not silently convert existing notes through a lossy HTML representation.

The accompanying [feature research](NOTES_FEATURE_PARITY_RESEARCH_2026-10-18.md) and [library research](NOTES_EDITOR_LIBRARIES_RESEARCH_2026-10-18.md) contain upstream sources, qualifications, and alternatives. The recommendations below are design judgments, not claims that an upstream library already implements Chur's requirements.

## Actionable findings

### P1 — A failed public write can suppress the later successful retry

`FileNoteStore.put()` changes the cached map before `save()` writes the file. If the write throws, `all()` still returns the uncommitted value. `ChurController.putNote()` then compares a subsequent save against that value and skips it as unchanged. A disk-full or file-replacement failure can therefore leave the last edit only in memory even after the storage problem is resolved; closing/reopening the editor does not reliably force persistence of the same text. Restart can bring back the older disk contents. Deletion and disclosure acknowledgement also mutate cached state before persistence, so their failed writes have related inconsistency risks.

Evidence: [`FileNoteStore.kt`](../../shared/feature-notes/src/commonMain/kotlin/dev/po4yka/chur/notes/FileNoteStore.kt), lines 33–54 and 82–87; [`ChurController.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/ChurController.kt), lines 2526–2532. This is a confirmed source-level failure path; no disk-failure injection was performed during this audit.

Fix before expanding the editor: build a prospective snapshot, persist it, and publish cache/disclosure changes only after success. Preserve a visible dirty/error state until acknowledgement. Capture a failed write followed by the same-value retry and a process reopen in regression coverage. File replacement is atomic at the platform adapter level; that does not make the surrounding cache update transactional or establish power-loss durability.

### P2 — Public pinning exists in the model but is unreachable in the UI

The public `Note` has `pinned`, and sorting honors it. The row only opens the note; the editor exposes Back and Delete, with title/body fields. No public pin/unpin control was found in the Android/iOS shared notes flow. README's statement that public Notes supports pinning overstates the visible feature.

Evidence: [`Notes.kt`](../../shared/feature-notes/src/commonMain/kotlin/dev/po4yka/chur/notes/Notes.kt), `Note` and `Notes.ordered`; [`NotesScreens.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/notes/NotesScreens.kt), lines 164–184 and 283–319; [`README.md`](../../README.md), line 87. Expose pin/unpin and its state using the existing persistence field.

### P2 — Public file version is decoded but never validated

`NoteFileV1.version` defaults to 1, but `load()` accepts the decoded object without checking it. A file marked as a different version with the same known fields can be accepted and later rewritten as version 1. This is a latent format-integrity defect, especially relevant to the proposed document-format extension; it is not evidence that existing files are corrupt.

Evidence: [`FileNoteStore.kt`](../../shared/feature-notes/src/commonMain/kotlin/dev/po4yka/chur/notes/FileNoteStore.kt), lines 69–76 and 101–104. Validate the supported version before loading editable data; use an explicit migration for a changed representation and reject unsupported future versions without writing over them.

## Current capability matrix

“Absent” below means absent from the inspected note model and note workflows. Existing media/file features elsewhere in Chur do not establish note integration.

| Capability | Public Notes | Private notes |
| --- | --- | --- |
| Text and title | Plain strings; title falls back to first body line | Plain strings; explicit title or “Untitled note” |
| Persistence | One unencrypted JSON file; whole-file rewrite | Rust-owned SQLCipher tables; notes introduced in schema v9, current catalog v11 |
| Saving | 500 ms debounce and save on editor disposal | Explicit Save; compare-and-swap revision; atomic row/index updates |
| Draft recovery | Saved-state text up to 20,000 UTF-16 units; longer drafts close on restoration and reopen from stored content | Session memory only; unsaved text is dropped on lock, disposal, identity change, or process death |
| Search | Case-insensitive substring over all loaded titles/bodies | Encrypted FTS5; AND of literal word prefixes, 256-byte/32-term query limit |
| Listing and scale | All notes loaded, searched, and sorted in memory | 50-row UI pages, up to 100 native rows; 160-scalar previews; maximum 10,000 notes including Trash |
| Note size | No explicit note-size bound in the inspected public model/store | Title 256 UTF-8 bytes; body 256 KiB; NUL rejected |
| Pinning | Model/sort only; no visible control found | List Pin/Unpin and editor checkbox |
| Trash/recovery | Confirmed immediate deletion; no Trash | 30-day Trash; restore, explicit purge, automatic expiry |
| Export/import | No dedicated note import/export flow found | Saved-revision TXT export; selected encrypted note archives and encrypted import/restore |
| Backup | Ordinary platform backup under public policy | Encrypted catalog backup and selected encrypted archives |
| Clipboard and input | Ordinary public text-field behavior | Copy/Cut blocked; explicit Paste; private keyboard hints; no saved-state registry |
| Markdown / rich text | Absent | Absent; markup deliberately remains literal text |
| Checklists, tables, code formatting | Absent | Absent |
| Folders, note tags, smart folders | Absent | Absent; media albums/tags are separate entities |
| Links/backlinks and attachments | Absent as note features | Absent as note features |
| Images, scanning, drawing, audio | Absent as note features | Media import/OCR infrastructure exists separately; no note attachment or recording/transcription workflow |
| Find/replace, outline, durable history | No explicit note feature found | No explicit note feature found; revision token is not revision history |
| Sync and collaboration | No notes sync workflow found | Explicitly excluded from current note contract and existing media sync/sharing |

Primary implementation evidence: [public model](../../shared/feature-notes/src/commonMain/kotlin/dev/po4yka/chur/notes/Notes.kt), [public editor](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/notes/NotesScreens.kt), [private UI](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/PrivateNotesScreen.kt), [private controller](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/PrivateNotesController.kt), [native storage](../../rust/crates/chur-catalog/src/notes.rs), [private product contract](../product/PRIVATE_NOTES.md), [selective archives](../product/SELECTIVE_ENCRYPTED_EXPORT.md), and [content search contract](../product/CONTENT_SEARCH.md).

## Define “full Markdown” as a testable product contract

There is no single standard covering every feature called Markdown. Use [CommonMark 0.31.2](https://spec.commonmark.org/0.31.2/) for the baseline and explicitly select [GFM extensions](https://github.github.com/gfm/) for tables, task lists, strikethrough, and extended autolinks. Footnotes, math, wiki links, callouts, diagrams, and YAML front matter require separately specified extensions; they are not all part of GFM.

Recommended coverage for both surfaces:

- Headings, nested ordered/unordered lists, quotes, emphasis, escapes, thematic breaks, inline/fenced code, reference/inline links, and local images; GFM tables and interactive task lists.
- Source editing, read mode, formatting toolbar, indentation/list continuation, find/replace, undo/redo, heading outline, code highlighting, and keyboard shortcuts. Visual mode is another editing view over the same supported document contract.
- Explicit follow-on extensions: footnotes, local math rendering, stable internal note links/backlinks, callouts, and optional diagrams. Preserve unsupported syntax verbatim and identify what preview cannot display.
- `.md` import/export preserving the original text when no edits occur; UTF-8 and Unicode coverage; bundled attachments for portable export. Treat title/metadata separately so exporting and reimporting does not duplicate headings.
- Keep raw HTML source if encountered, but display it inertly unless a narrowly reviewed rendering policy is introduced. Do not execute scripts, fetch remote images/fonts/styles, embed arbitrary frames, or follow links automatically. Text preservation and active-content execution are different requirements.

Raw Markdown source can remain byte-identical in source mode. A visual edit may normalize equivalent syntax; document that guarantee as semantic preservation for supported constructs, never as arbitrary byte preservation. A renderer or a CommonMark parser alone does not supply editing, reversible serialization, mobile selection behavior, attachment custody, or accessibility.

Apple-style rich documents also contain information Markdown cannot represent universally, such as handwriting, rich attachment presentations, highlights, or complex tables. Keep those as versioned typed attachments/blocks with stable references and an explicit export mapping. Do not claim lossless plain `.md` export for such a document.

## Recommended architecture

### One editing experience, two independent storage boundaries

Share pure editing commands, formatting UI, rendering components, and document semantics. Inject public/private load/save/attachment/link actions. Keep public `NoteStore` and private session controllers distinct. The public module's existing `churPublicShellIsolation` gate prohibits project dependencies; shared UI currently belongs to `shared/app`. Any module extraction must preserve or deliberately revise that gate, rather than quietly making public Notes depend on the vault.

Use an explicit, versioned document format discriminator. Existing notes must migrate as **plain text**, not suddenly render literal `#`, `*`, or HTML as document instructions. For new Markdown notes, store the source as authoritative text plus typed metadata/references; parsed trees and rendered content are derived. Avoid independently editable Markdown, HTML, and editor JSON copies. If a structured rich format is later made canonical, make that a conscious format decision with documented Markdown export loss, not a hidden implementation detail.

Private persistence remains Rust-owned. New document metadata, references, search text, and relevant revision state must commit consistently under the selected identity and revision. Keep large attachments in encrypted object storage with owned references, not base64 in the 256 KiB body or across oversized FFI controls. Public attachments need a separate public storage root and backup policy.

### Candidate editor strategies

| Strategy | Why consider it | Cost and decision gate |
| --- | --- | --- |
| Compose source editor + multiplatform Markdown preview | Fits the current shared UI; retains arbitrary source; best incremental starting point | Renderer support is not editor support. Implement formatting actions and validate the chosen dialect on both platforms; bounded parsing/layout and source-to-preview behavior remain application work |
| Bundled Milkdown in platform WebViews | Strong Markdown-first visual editing candidate with CommonMark/GFM foundation | Adds JS bridge, WebView lifecycle, mobile IME/selection and privacy work; source normalization/unsupported constructs must be measured |
| Compose Rich Editor | Native shared visual editing, avoiding a WebView | Its documented Markdown coverage is insufficient as the sole full-Markdown representation; select only after tables and unsupported-syntax preservation meet the contract |

Tiptap/ProseMirror and Lexical are credible rich-editor alternatives, but require Markdown adapters and extension decisions. CodeMirror 6 is a source-editor alternative, not an Apple Notes rich-document product. Native Android/iOS renderer libraries create two platform implementations. Rust parsers can help derive search text or validate imports, but do not replace an editor. See the [library comparison](NOTES_EDITOR_LIBRARIES_RESEARCH_2026-10-18.md) for primary sources and licensing boundaries.

Prefer the source-plus-preview route for the first shipped Markdown capability. If visual editing is mandatory from the first release, compare Milkdown with Compose Rich Editor using real Android/iOS typing, composition, screen-reader, and losslessness evidence before selecting. The cost is an additional editor evaluation; the benefit is avoiding a stored format that later destroys user syntax or blocks the desired features. No library or production dependency is added by this audit.

### Saving, recovery, and private input

Private explicit Save and unsaved-text loss are current intentional policy, not defects in that implementation. For Apple Notes-like durability, introduce **encrypted autosave/drafts while unlocked** as a product-contract change. Commit on bounded typing debounce; show saving/saved/failed state. Lock must immediately detach the editor, invalidate late work, clear render/undo/search state, and never wait for a last-minute save. Already committed encrypted drafts can survive restart; uncommitted keystrokes have an honestly bounded loss window.

Background draft work must not call the current note repository surface as a keepalive: `withNoteSession()` calls `touch()` after native work. Autosave needs a deliberate no-activity persistence path, with actual user input remaining the idle-timer signal. Avoid postponing lock by periodic saves. Resolve revision conflicts with a recoverable local draft or explicit merge; the present “reopen saved version” message is insufficient once concurrent devices exist.

Keep public saved-state restoration separate. A replacement editor must not inherit public restoration, platform clipboard writes, drag/share actions, context menus, or accessibility export actions in private mode. Current Compose clipboard wrappers do not automatically police a WebView or native replacement editor. Apply policy to every editor integration and clear its history and retained document on lock. Managed strings and platform input/layout copies still cannot promise physical memory erasure.

### Attachments, search, and organization

Add stable note-to-attachment references with consistent visibility, identity, revision, and lifecycle rules. Shared attachments must survive deletion of one referring note; Trash, expiry, purge, backup, selected archives, import ID remapping, and later synchronization all need defined behavior. A note-link resolver must not fall through from one identity or public store to another.

Reuse existing encrypted media/document import and OCR for private attachments, but add note integration explicitly. Search should index readable text derived from the document, note metadata, and permitted attachment OCR; avoid indexing only Markdown punctuation, raw URLs, or hidden renderer state. Keep derived indexes encrypted and session-bound, and retain a source-editing search that can find literal syntax. Public search can remain simple initially; move away from whole-file rewrites/all-notes scans when attachments, history, or measured scale make that storage model inappropriate.

Folders, tags, smart folders, links, and history need note-domain models. Existing media tags/albums can inform conventions but do not currently organize notes. Add a note history table/snapshot policy rather than treating the optimistic revision number as a recovery feature. Define retention, quota, restore, and backup behavior for history.

### Synchronization and collaboration

Notes currently live in the encrypted catalog and are explicitly absent from media synchronization. Syncing a database file is not a safe shortcut for live editable-note synchronization. Add note-specific encrypted operations, identity/object authorization, attachment transfer, deletion tombstones, multi-device conflict handling, and offline recovery. Review metadata exposure and replay/rollback behavior under the existing trust model.

Start with whole-document revisions and a user-recoverable conflict strategy unless simultaneous coediting is a concrete first requirement. Yjs or Automerge can support a later collaborative representation; neither supplies Chur's encryption, access control, revocation, transport, retention, or metadata privacy. CRDT choice must follow the canonical document model, not force it accidentally.

## Feature-first delivery order

| Order | Runnable outcome | Completion evidence and major cost |
| --- | --- | --- |
| 1 | Reliable public persistence, visible pinning, safe format-version handling | Failed write/same-value retry/reopen, failed delete, future-version rejection, visible pin/unpin. Small focused changes |
| 2 | Real Markdown notes in both surfaces: source editor, preview, toolbar, task lists, tables, local code formatting, `.md` import/export | Same dialect and document corpus on Android/iOS; source retention; no public/private crossing. Moderate UI and format work |
| 3 | Daily-use organization and recovery: folders/tags, find/replace, outline, undo/redo, encrypted private drafts and retained history | Crash/restart and lock behavior, idle timeout, conflicting revision recovery, migration/backup. Substantial persistence and UX work |
| 4 | Rich note attachments: images/files, document scans/OCR, attachment search, portable packages | Real attachment round trip through Trash/purge, selected archive, restore and import; no orphan/lost references. Substantial cross-layer work |
| 5 | Notes synchronization and optional sharing | Two real clients edit offline, reconnect, preserve both divergent edits, transfer attachments, and honor lock/identity/revocation. Separate protocol work |
| 6 | Advanced Apple-like workflows: drawing/handwriting, audio recording/transcription, math, quick capture and system integrations | Per-platform device acceptance; local-processing/privacy and language availability measured. Several distinct features, not an editor plugin |

Only feature 1 fixes current defects. The remaining rows expand accepted scope. A renderer-only or refusal-only milestone does not complete Markdown parity. No issues, PRs, dependency installations, or implementation tasks were created during this audit.

## Acceptance boundaries for the eventual implementation

- Round-trip nested lists, tables with escapes, task states, code fences, reference links, Unicode/emoji/RTL, unsupported extensions, and existing plain-text notes. Switch editing modes repeatedly without silent content loss.
- Exercise platform IMEs, dictation/paste, selection, large text, external keyboards, VoiceOver/TalkBack, narrow layouts, and navigation with errors. Platform text-field undo behavior is not proof of application history or consistent toolbar undo.
- Measure parsing, typing latency, memory and scrolling at the actual 256 KiB private-body limit and a large note collection. Bound pathological nesting, image size and work; do not just raise limits or block the UI thread.
- Verify no implicit network fetch from Markdown/images/previews. Review URI schemes, path traversal, active HTML/SVG, import/package expansion limits, and resource-loading permissions at the chosen renderer/editor boundary.
- Exercise lock/panic/identity switch during parsing, typing, export, OCR and autosave; verify cleanup of caches, editor history, bridge messages and late callbacks. Recheck clipboard and saved-state policy for the actual selected editor.
- Observe backup/restore, selected export/import, attachment retention and source/format migration on real stores. Require actual two-device evidence before claiming synchronization or collaboration.

## Validation performed for this audit

Source inspection covered the public model/store and Android/iOS file adapters, both shared editors, private controller/input policy/export reference, repository session guard, Rust note storage/search, relevant tests, product contracts, and schema/architecture boundaries. Two delegated research lanes checked current primary sources for product features and open-source libraries; their reports distinguish documentation from implementation evidence.

Observed command:

```sh
build-gate -- ./gradlew --no-daemon :shared:feature-notes:testAndroidHostTest :shared:feature-notes:churPublicShellIsolation :shared:app:testAndroidHostTest --console=plain
```

The combined command exited **1**: the app host suite completed 331 tests with one failure in `SyncSessionHostTest.a_lock_during_a_backoff_ends_the_run_and_what_it_showed`. After panic, its assertion expected a null server URL and observed `https://sync.invalid`. This was not diagnosed or fixed as part of the notes audit; it is an observed validation failure, not a proven notes defect or a proven harmless timing issue. The six app note-related test classes in that run completed **30 tests, zero failures/errors/skips**: draft, saved state, private clipboard, private export, private input policy, and private controller. Passing those classes does not make the combined command green or cover the newly identified public write-failure path.

The `churPublicShellIsolation` task completed without a reported failure in the combined run. A separate `build-gate -- ./gradlew --no-daemon :shared:feature-notes:testAndroidHostTest :shared:feature-notes:churPublicShellIsolation --console=plain` attempt remained queued behind other machine-wide builds for over two minutes. Its waiting process was stopped; the gate was not bypassed and other builds were not interrupted. Consequently the public persistence **test suite has no successful result from this audit**. Rerun that exact command when the gate is available. A direct whitespace/final-newline check of all three new reports passed. The reports were reviewed for scope and source attribution; no tracked source file was edited by this audit.

No UI was launched, no physical-device behavior or power-loss durability was measured, and no new editor/library was built. The Rust workspace, private native persistence/backup integration suite and iOS suite were not rerun. Existing tests inspected in source are not counted as passed merely because their files exist. The three findings above remain source-level findings; they were not fixed in this audit. Only the three `NOTES_*_2026-10-18.md` reports were created by this audit; unrelated source edits belong to concurrent work.
