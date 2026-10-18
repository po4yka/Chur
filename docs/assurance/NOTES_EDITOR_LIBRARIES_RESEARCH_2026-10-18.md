# Notes editor and Markdown library research

> Historical snapshot from before the Markdown implementation. Public persistence fixes and the shared Markdown source editor/preview have since shipped, using the approved multiplatform-markdown-renderer 0.46.0 and code highlighting. See the [current notes contract](../product/MARKDOWN_NOTES.md) for implemented behavior, schema/ABI, limitations, and validation boundaries. Rich attachments, OCR, WYSIWYG editing, retained history, and synchronization remain future work; recommendations and validation results below describe the original research date.

Date: 2026-10-18. Scope: library and architecture research for public and hidden notes; no dependency installation, implementation, builds, or device verification. Sources are official documentation, upstream source, licenses, and release metadata. Context7 CLI library resolution and documentation retrieval were used first for the libraries below, then checked against upstream sources where limitations or maintenance status mattered. An initial exact-name Context7 search for ComposeRichEditor returned no result; a spaced-name search succeeded. No Context7 quota error occurred. GitHub API rate limiting affected the final ProseMirror metadata lookup; the owning repository's migration notice and current upstream changelog supplied the missing maintenance evidence.

## Decision

Keep Markdown source as the durable representation for Markdown notes, and use a shared editor surface with separate public/private persistence ports. Evaluate two serious paths: a native source editor plus Compose preview, and an entirely bundled Milkdown editor inside platform WebViews. Compose Rich Editor is worth a smaller native-rich-text evaluation, but its documented table gap disqualifies it as an immediate full-GFM solution. These are recommendations, not verified integrations.

Do not conflate a parser, a renderer, and an editor. A parser recognizes syntax; a renderer displays it; an editor must additionally support selection, composing text, undo, clipboard policy, document mutations and persistence. None of the libraries below supplies the complete Apple Notes product, including encrypted attachments, scan capture, handwriting, OCR search, recording/transcription, organization, sharing permissions, and synchronization.

In Chur, place shared editor/rendering primitives in the UI/application layer; retain public `feature-notes` and hidden-vault persistence boundaries. Public domain code must not acquire a dependency on the private Rust FFI merely to share Markdown behavior. Hidden notes must retain their existing no-copy/cut, no-SavedState and lock-clears-memory policies. These constraints were supplied by the accompanying code audit; this document does not independently re-audit their implementation.

## What “full Markdown” should mean

Adopt a named, versioned dialect: CommonMark + the five GFM extensions, then explicitly list extras such as footnotes, math, wikilinks, highlights and attachment references. Footnotes and math are not part of the GFM specification. GFM's tables, task lists, strikethrough, extended autolinks and disallowed-raw-HTML rules are specified separately from CommonMark. Arbitrary rich text cannot be losslessly represented in ordinary Markdown: merged cells, drawing strokes, fonts and object geometry need another representation. See the [GFM specification](https://github.github.com/gfm/) and [CommonMark specification](https://spec.commonmark.org/).

Source fidelity and semantic fidelity are different acceptance bars. A parse/serialize cycle can retain meaning while changing list markers, link-reference spelling, spacing and code fences. Opening a note or preview must never itself rewrite the source. Unsupported syntax must remain available verbatim, with an explicit limitation in visual editing, rather than silently becoming simplified content. This is a proposed Chur contract; it is stronger than the guarantees made by most rich editors.

## Native and shared Kotlin candidates

| Candidate | Actual role and platforms | Useful coverage | Limits for Chur |
| --- | --- | --- | --- |
| [Compose Rich Editor](https://github.com/MohamedRejeb/compose-rich-editor) | Actual WYSIWYG editor; Compose Android, iOS, Desktop and Web | Common inline styling, headings, lists, links, images, Markdown/HTML import-export; document JSON and rich-text-aware history | Official Markdown docs still list tables as future work. Unsupported syntax becomes plain text; image dimensions are lost in Markdown; partial embedded HTML support. Task-list, math and footnote editing are not established by the retrieved support list. |
| [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | Compose renderer, Android/iOS and other CMP targets; JetBrains Markdown parser | GFM tables/task lists/strikethrough/autolinks; configurable components, images and optional code highlighting; asynchronous parsing | No editing model or Markdown serialization. Rendering task lists does not implement persisted checkbox edits. Math/footnote behavior needs explicit fixtures; a release fixed dropped math spans by rendering them as text, which is not mathematical layout. |
| [Markwon](https://github.com/noties/Markwon) | Android-native Spannable renderer; optional source-editor module | CommonMark, tables, task lists and LaTeX plugins; no WebView required | Android only; not shared CMP/iOS. Editor support decorates source rather than supplying a complete rich document model. Checkboxes require application persistence. Old release cadence makes it a weak default for a new cross-platform surface. |

Compose Rich Editor's [Markdown support list](https://github.com/MohamedRejeb/compose-rich-editor/blob/main/docs/markdown_import_export.md) is the decisive limitation, not its general “supports Markdown” description. Its [v1.1.0 release](https://github.com/MohamedRejeb/compose-rich-editor/releases/tag/v1.1.0) introduced stable document JSON and grouped history, and fixed an IME caret issue. The [compatibility table](https://mohamedrejeb.github.io/compose-rich-editor/#compatibility) is version-specific and lags the observed v1.2.0 release: do not select the newest artifact without matching Chur's Kotlin/Compose/AGP toolchain. JSON avoids some Markdown representation loss but creates an editor-specific durable schema; it does not add missing table support.

The renderer's [release history](https://github.com/mikepenz/multiplatform-markdown-renderer/releases) distinguishes actual math rendering from preservation as text. [Markwon task-list documentation](https://github.com/noties/Markwon/blob/master/docs/docs/v4/ext-tasklist/README.md) explicitly leaves state persistence to the consuming application.

## Native Apple alternatives

| Candidate | Actual role | Assessment |
| --- | --- | --- |
| [swift-markdown](https://github.com/swiftlang/swift-markdown) | Markdown parser/AST building, rewriting and formatting based on cmark-gfm; immutable, copy-on-write data | Useful for an Apple-native adapter or transformations. It is not a text-input widget or a complete note editor. An iOS-only parser introduces another dialect implementation to keep aligned with Android/Rust. |
| [MarkdownUI](https://github.com/gonzalezreal/swift-markdown-ui) | SwiftUI renderer, including GFM tables/task lists | Explicitly in maintenance mode; new development moved to [Textual](https://github.com/gonzalezreal/textual). Neither is evidence of a full editor. A new platform-native rendering path should evaluate Textual rather than assume MarkdownUI remains the active upstream. |

These are alternatives if Chur deliberately adopts native iOS views. They do not deliver shared Compose editing. Source references: [Swift Markdown architecture](https://github.com/swiftlang/swift-markdown/blob/main/README.md), [MarkdownUI maintenance notice](https://github.com/gonzalezreal/swift-markdown-ui/blob/main/README.md), and [Textual repository](https://github.com/gonzalezreal/textual).

## Rust candidates

| Candidate | Role | Suitable responsibility |
| --- | --- | --- |
| [pulldown-cmark](https://github.com/pulldown-cmark/pulldown-cmark) | Pull/event parser with source positions; CommonMark plus opt-in tables/task lists/footnotes/math and other extensions | Search/plain-text extraction, heading/link extraction, validation, or a parser feeding a custom renderer. It supplies neither a mobile editor nor a persistent rich-document model. Parsing math tokens is not equation layout. |
| [Comrak](https://github.com/kivikakk/comrak) | CommonMark/GFM parser and formatter with mutable AST; HTML/XML/CommonMark output, opt-in extras including footnotes/math/wikilinks | More suitable for AST transformations and controlled Markdown serialization. Formatting back to CommonMark is normalization, not proof of byte-preserving round-trip. No input widget, clipboard policy or encrypted attachment store is supplied. |

Avoid two independently authoritative document models. If Rust is selected for indexing, it can consume committed Markdown while the UI remains the editing authority. If Rust becomes the shared parser contract, define token/AST boundaries and dialect fixtures deliberately; FFI and renderer work are real costs. Introducing either crate solely to add a preview is unnecessary if the chosen CMP renderer already parses the needed dialect. See the [pulldown-cmark README and extension options](https://github.com/pulldown-cmark/pulldown-cmark), and [Comrak extensions and output formats](https://github.com/kivikakk/comrak).

## Web editor candidates

All of these execute in a browser/DOM environment, not directly as Compose native widgets. Android WebView and iOS WKWebView can host bundled assets, but platform integration, accessibility and secrecy must be verified in the host application.

| Candidate | Actual strengths | Important trade-off |
| --- | --- | --- |
| [Milkdown](https://github.com/Milkdown/milkdown) | Markdown-first WYSIWYG on ProseMirror + remark; CommonMark + GFM presets, tables, checklists, footnotes, history and Yjs collaboration plugin | Best first rich-Markdown candidate. Markdown parsing/serialization still normalizes content and needs custom handling of unsupported constructs; not byte-preserving by default. Math and arbitrary file/drawing blocks require separately validated components or extensions. |
| [Tiptap](https://github.com/ueberdosis/tiptap) | Rich-document editor on ProseMirror; configurable schema, table/task/code/image nodes; headless UI, official bidirectional Markdown extension | Official Markdown support exists now, but is beta. Docs warn about comments and table-cell content limitations. Core is open source; some product features/extensions require subscriptions. Do not infer an open-source full suite from the editor's MIT license. |
| [ProseMirror](https://github.com/ProseMirror/prosemirror-view) | Lower-level semantic editor infrastructure with schemas, transactions, view and collaboration ecosystem | Strong foundation, but direct use makes Chur responsible for more schema, commands, UI, serialization and mobile integration than Milkdown/Tiptap. Markdown is an adapter, not its canonical model. |
| [Lexical](https://github.com/facebook/lexical) | Framework-independent editor core, history, tables/lists/custom nodes, JSON/HTML/Markdown and Yjs integrations; accessibility is a stated project focus | Traditional `@lexical/markdown` uses configured transforms rather than proving full CommonMark/GFM. New `@lexical/mdast` uses micromark/mdast and supports GFM but is explicitly experimental, including at v0.52.0. Needs a larger compatibility evaluation than its basic Markdown examples imply. |
| [CodeMirror 6](https://github.com/codemirror/dev) | Source editor with Markdown language support, history/search/keymaps, mobile-native selection and screen-reader focus | Good rich source-editing option, not an Apple Notes WYSIWYG replacement. Raw source is naturally retained until an actual edit; native app integration still needs the WebView boundary. |

Milkdown's versioned [GFM preset](https://github.com/Milkdown/milkdown/blob/v7.22.3/docs/api/preset-gfm.md) includes table commands and footnote schemas; its [history](https://github.com/Milkdown/milkdown/blob/v7.22.3/docs/api/plugin-history.md) and [collaboration plugin](https://github.com/Milkdown/milkdown/blob/v7.22.3/docs/api/plugin-collab.md) are separate components. A named GFM preset is stronger evidence than a generic Markdown badge, but still does not prove every spec example round-trips through editing.

Tiptap's current [Markdown introduction](https://tiptap.dev/docs/editor/markdown) explicitly documents the beta state and limits; [upstream README](https://github.com/ueberdosis/tiptap) distinguishes the open editor from Pro extensions. Its richer JSON document can represent things standard Markdown cannot, so choose the storage authority before exposing unrestricted formatting.

Lexical's [supported Markdown helpers](https://lexical.dev/docs/packages/lexical-markdown) and [experimental mdast package at the released tag](https://github.com/facebook/lexical/blob/v0.52.0/packages/lexical-mdast/README.md) must be evaluated separately. The latter retains some original syntax choices but does not promise arbitrary-source identity; unsupported constructs can degrade to content when the corresponding extensions are absent.

CodeMirror and ProseMirror moved their authoritative development repositories to `code.haverbeke.berlin` in 2026. Archived GitHub mirrors do not mean the projects stopped maintenance. Current [CodeMirror view changelog](https://code.haverbeke.berlin/codemirror/view/src/branch/main/CHANGELOG.md) contains iOS selection, composition and VoiceOver fixes; [ProseMirror view changelog](https://code.haverbeke.berlin/prosemirror/prosemirror-view/src/branch/main/CHANGELOG.md) contains Android input/composition fixes. This is evidence of active maintenance and of why browser editing still requires device testing.

## Maintenance and licensing snapshot

Observed release publication dates, not a claim of artifact compatibility or a security audit. Links identify the exact releases retrieved via first-party metadata. Transitive licenses and optional modules must be reviewed when a concrete dependency set is selected.

| Project | License observed | Release evidence |
| --- | --- | --- |
| Compose Rich Editor | Apache-2.0 | [v1.2.0, 2026-09-05](https://github.com/MohamedRejeb/compose-rich-editor/releases/tag/v1.2.0) |
| multiplatform-markdown-renderer | Apache-2.0, with inherited MIT portions noted upstream | [v0.46.0, 2026-10-13](https://github.com/mikepenz/multiplatform-markdown-renderer/releases/tag/v0.46.0) |
| Markwon | Apache-2.0 | [v4.6.2, 2021-02-08](https://github.com/noties/Markwon/releases/tag/v4.6.2); repository push metadata last 2024-04-17 |
| swift-markdown | Apache-2.0; cmark-derived notices apply | [0.9.0, 2026-09-21](https://github.com/swiftlang/swift-markdown/releases/tag/0.9.0) |
| MarkdownUI / Textual | MIT | [MarkdownUI 2.4.1, 2024-10-13](https://github.com/gonzalezreal/swift-markdown-ui/releases/tag/2.4.1); [Textual 0.5.0, 2026-06-15](https://github.com/gonzalezreal/textual/releases/tag/0.5.0) |
| pulldown-cmark | MIT | [v0.13.4, 2026-05-20](https://github.com/pulldown-cmark/pulldown-cmark/releases/tag/v0.13.4) |
| Comrak | BSD-2-Clause style terms and component notices in [COPYING](https://github.com/kivikakk/comrak/blob/v0.56.0/COPYING) | [v0.56.0, 2026-10-06](https://github.com/kivikakk/comrak/releases/tag/v0.56.0) |
| Milkdown | MIT | [v7.22.3, 2026-10-10](https://github.com/Milkdown/milkdown/releases/tag/v7.22.3) |
| Tiptap core | MIT; separate terms for Pro components | [v3.32.0, 2026-10-13](https://github.com/ueberdosis/tiptap/releases/tag/v3.32.0) |
| Lexical | MIT | [v0.52.0, 2026-09-28](https://github.com/facebook/lexical/releases/tag/v0.52.0) |
| CodeMirror 6 / ProseMirror | MIT | [CodeMirror view 6.43.14, 2026-10-07](https://code.haverbeke.berlin/codemirror/view/src/branch/main/CHANGELOG.md); [ProseMirror view 1.42.6, 2026-09-25](https://code.haverbeke.berlin/prosemirror/prosemirror-view/src/branch/main/CHANGELOG.md) |
| Yjs | [MIT](https://github.com/yjs/yjs/blob/main/LICENSE) | [v14.0.0-rc.29, 2026-10-15](https://github.com/yjs/yjs/releases/tag/v14.0.0-rc.29); this is a prerelease, not a recommended production pin |
| Automerge | MIT | [JS 3.5.0, 2026-09-16](https://github.com/automerge/automerge/releases/tag/js/automerge-3.5.0); JS release numbering does not select the Rust crate version |

## Offline and hidden-note constraints

The following are proposed integration requirements, not claims that an untested host configuration is secure.

1. Ship editor JS/CSS/fonts and any math/highlight resources inside the application. No CDN, telemetry, remote embed or automatic URL preview is necessary for editing. Block network requests in hidden-note rendering by default, including Markdown images, CSS, iframes and plugin requests. Resolve links explicitly through host policy.
2. Keep persistence in the existing application-owned stores. A WebView should not silently add IndexedDB, localStorage, service-worker caches or browser history containing note data. On Apple platforms [WKWebsiteDataStore.nonPersistent](https://developer.apple.com/documentation/webkit/wkwebsitedatastore/nonpersistent()) is a useful host capability, not proof of complete memory erasure. On Android, the official [local-content guide](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content) describes bundled resources via WebViewAssetLoader; the loader alone does not block network access or define secret persistence policy.
3. Treat pasted HTML, Markdown URLs, SVG and attachments as untrusted input. Limit accepted URI schemes, external navigation and bridge messages. Define the native bridge as narrow document edits and attachment reads, with session/revision checks; do not expose general filesystem or arbitrary command access.
4. For hidden notes, tear down editor state, DOM, selection, undo/history, decrypted attachment buffers, pending bridges and callbacks on lock. Preserve disabled copy/cut/export and saved-state restrictions across both native and web editing. Runtime GC means clearing references is not a proof of zeroized copies; evaluate actual retention and platform lifecycle behavior.
5. Store attachments as stable application-owned identifiers, not absolute paths or arbitrary remote URLs. An image loader/attachment resolver should read authorized encrypted content while unlocked. The editor only supplies references and presentation; attachment encryption, thumbnails, size limits, cleanup and atomic updates belong to Chur.

## Collaboration: later capability, separate security design

[Yjs](https://github.com/yjs/yjs) is a good fit for ProseMirror/Milkdown/Tiptap/Lexical integrations. [Automerge](https://github.com/automerge/automerge) is attractive if a Rust-owned local-first document model is an explicit product direction; its [rich-text model](https://automerge.org/docs/reference/documents/rich-text/) has marks and block markers, with ProseMirror integrations linked upstream. Neither is a notes application or an authorization/encryption system.

Yjs's standard [offline guide](https://docs.yjs.dev/getting-started/allowing-offline-editing) persists document updates to IndexedDB and discusses replay/replication. That is useful for a web app but unsuitable as an unreviewed extra plaintext store for hidden Chur notes. If CRDTs are added, persist encrypted snapshots/update logs through the host, and explicitly define key distribution, access revocation, deleted-content retention and attachment synchronization. CRDT history/update data can retain content beyond the currently visible note. Public collaboration must not accidentally grant a sync provider access to hidden documents. A single-user Markdown editor does not need CRDT complexity to ship.

## Three realistic architectures

### A. Markdown source + native Compose preview — recommended first delivery

Keep source text canonical, add a native source-editing toolbar and preview with multiplatform-markdown-renderer. Parsing and preview must never serialize over source. A persisted checkbox action should update the corresponding source range through a version-checked edit, rather than changing only a visual checkmark. Use custom preview components for application-owned attachments. Add math/footnotes only when renderer behavior and a portable dialect are established.

Benefit: shared CMP UI, lowest new runtime/security surface, exact no-edit source preservation, and straightforward private-store integration. Cost: WYSIWYG editing remains absent; advanced source-editor behaviors and native IME/undo still require implementation. This is a coherent Markdown product, but it earns only partial Apple Notes editing parity. CodeMirror is an optional replacement source surface if native editing proves insufficient; that introduces the same WebView boundary as B.

### B. Bundled Milkdown + source mode — recommended rich-Markdown candidate

Host identical bundled editor assets in Android/iOS WebViews; keep persistence behind application ports. Use CommonMark/GFM presets and explicit custom attachment nodes. Retain exact source until a deliberate visual edit; store enough source/range information to preserve unsupported regions, or prevent destructive visual-mode conversion with a clear explanation. Test edit/save/reopen semantic equivalence before claiming round-trip support. Tiptap is the stronger alternate if richer document blocks dominate, with beta Markdown restrictions explicitly accepted.

Benefit: existing table/checklist/history/schema machinery and a route to later collaboration. Cost: two platform hosts, bridge protocol, mobile selection/IME/accessibility, secure lifecycle teardown, dependency bundling and source-preserving visual editing are substantial work. Rich editing must not be advertised as lossless merely because import/export methods exist. Native previews and editor views must use the same declared syntax contract.

### C. Native rich-document format + Markdown import/export

Evaluate Compose Rich Editor's document JSON, or platform-native editors behind a Chur-owned schema, only if Apple Notes-style rich objects take priority over Markdown as the primary format. Store structured blocks and attachment identifiers; generate Markdown as an explicit export with representation limits. Preserve imported source separately until an edit invalidates it, rather than claiming byte-preserving JSON-to-Markdown conversion.

Benefit: room for rich layout and object metadata outside Markdown. Cost: custom durable schema/migrations and object fidelity, unsupported library blocks, and possible Android/iOS divergence. Compose Rich Editor's missing tables mean that candidate currently needs upstream feature delivery or substantial custom work. This path is coherent only with an explicit rich-document product contract; it is not a shortcut to “full Markdown.”

## Proof-of-concept acceptance criteria before choosing a dependency

These are proposed future behavior checks, not a new test suite or a completed PoC. No builds or device tests were run for this research.

| Area | Required observation |
| --- | --- |
| Source preservation | Open/preview/close without edits preserves source bytes; unusual list markers, reference links, fences, whitespace and unknown extensions survive. A deliberate edit changes only its intended region or has explicitly documented normalization. |
| Syntax | CommonMark/GFM corpus plus nested lists, tables with escaped pipes, checked/unchecked tasks, fenced code with language, links, images, hard breaks; separately chosen footnote/math fixtures. Render, edit, save and reopen on Android and iOS. |
| Rich interactions | Table row/column/cell editing, task toggling, nested-list Enter/Backspace, links, attachment insertion/deletion, undo/redo across formatting and paste. These must persist real content changes. |
| Input | Russian/English, CJK composition, emoji/combining characters, dictation, autocorrect, hardware keyboard, selection handles and multiline paste on real Android/iOS keyboards. No caret jumps or duplicate/composition loss. |
| Accessibility | VoiceOver and TalkBack read headings, links, list/task state and table cells; formatting controls have names/states; large text and keyboard-only traversal work. Upstream accessibility claims do not establish Chur accessibility. |
| Durability | Debounced autosave, rapid reopen, background/process loss and concurrent save/lock callbacks do not overwrite a newer revision or report unsaved content as saved. |
| Hidden policy | Lock while editing/pasting/loading an attachment clears the visible editor and old callbacks cannot repopulate it. No clipboard export, SavedState restoration, network requests, browser storage, unencrypted thumbnails or plaintext indexing escapes the private-store policy. |
| Resource use | Agree an interactive latency and memory budget, then observe representative long notes and many attachments on lower-memory target devices. Do not infer speed from upstream benchmark slogans. |
| Import/export | `.md` + attachments package survives export/import; unsafe paths and disallowed URLs are rejected; unavailable capabilities are reported explicitly. Rich-only objects have named export behavior. |

The concrete next decision is A versus B, with the same canonical source, attachment references, public/private persistence ports and privacy policy. Prototype only the riskiest interactions from this table before approving production dependencies. No library selection, added dependency, collaboration service, format migration or application change is authorized or implemented by this report.
