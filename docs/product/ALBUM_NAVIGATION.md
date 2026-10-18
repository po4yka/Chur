# Album navigation

> **Status:** Proposed product and platform contract

## Navigation

An open album shows **Albums → ancestors → current album**. The Albums control returns to the album organizer. Each ancestor opens that album. The current album has a selected accessibility state and is not a link. Immediate child albums appear below the path in the same sibling order as the organizer.

The Albums control stays visible while the path scrolls horizontally. Album names remain available in full to accessibility and horizontal scrolling. The header scrolls vertically when large text needs more space and leaves space for the media browser.

Back first clears a media selection. The next Back opens the immediate parent, or the organizer for a top-level album. Back from the organizer returns to Library. Breadcrumbs and child links are hidden during selection. The organizer retains its filter, order, and list/grid choice when the user returns.

Album destination controls show the full path and let the user search it. Equal full paths include the album identifier so each destination remains distinct. Moving an album excludes that album and its descendants from the destination list.

**Select loaded items** selects the fetched media rows, including pages already loaded. It does not select results that have not been fetched. Removing a selection from an album is one transaction; a failed removal keeps the whole selection in that album. Originals and membership in other albums remain intact.

## Catalog and privacy

Navigation resolves album IDs against the current catalog, not names or a saved ancestor stack. A rename updates the path. A move updates the parent used by Back. Duplicate names remain separate destinations. If the current album or an ancestor is missing, or the hierarchy contains a cycle or ambiguous IDs, navigation returns to Albums.

The open album and scroll positions are local to the unlocked UI session. They must not persist private names or IDs in platform saved navigation state. Lock or session replacement clears the open album. Navigation does not change album membership, media filters, export scope, or the catalog format.

Album creation, names, and membership changes use the existing encrypted own-device operation log. Membership added before the first media upload remains in the local catalog until the signed object history is ready. Received membership becomes visible after the file is verified locally. Parent links, manual order, and album deletion remain local under catalog v7; they have no operation in sync v1.

## Validation

Common tests cover deep paths, duplicate names, catalog changes, missing ancestors, cycles, and sibling order. Android presentation tests use synthetic public album names to check links, selection, narrow layouts, large text, and Back. Platform builds check both Android and iOS integration. Device acceptance remains separate from emulator tests and compilation.
