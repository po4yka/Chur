# Album navigation

> **Status:** Proposed product and platform contract

## Navigation

An open album shows **Albums → ancestors → current album**. The Albums control returns to the album organizer. Each ancestor opens that album. The current album has a selected accessibility state and is not a link. Immediate child albums appear below the path in the same sibling order as the organizer.

The Albums control stays visible while the path scrolls horizontally. Album names remain available in full to accessibility and horizontal scrolling. The header scrolls vertically when large text needs more space and leaves space for the media browser.

Back first clears a media selection. The next Back opens the immediate parent, or the organizer for a top-level album. Back from the organizer returns to Library. Breadcrumbs and child links are hidden during selection. The organizer retains its filter, order, and list/grid choice when the user returns.

## Catalog and privacy

Navigation resolves album IDs against the current catalog, not names or a saved ancestor stack. A rename updates the path. A move updates the parent used by Back. Duplicate names remain separate destinations. If the current album or an ancestor is missing, or the hierarchy contains a cycle or ambiguous IDs, navigation returns to Albums.

The open album and scroll positions are local to the unlocked UI session. They must not persist private names or IDs in platform saved navigation state. Lock or session replacement clears the open album. Navigation does not change album membership, media filters, export scope, or the catalog format.

## Validation

Common tests cover deep paths, duplicate names, catalog changes, missing ancestors, cycles, and sibling order. Android presentation tests use synthetic public album names to check links, selection, narrow layouts, large text, and Back. Platform builds check both Android and iOS integration. Device acceptance remains separate from emulator tests and compilation.
