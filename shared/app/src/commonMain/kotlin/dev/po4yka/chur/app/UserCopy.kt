package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus

/**
 * The sentence a user reads for a boundary failure, `docs/ERROR_MODEL.md`.
 *
 * "Layer mapping" puts user-facing copy in the feature layer, after the status
 * is stable, so this is the one place a [ChurStatus] becomes text. Each line
 * says what happened and the next step from the table's "User action" column,
 * in the voice of `DESIGN.md` §27, and never the code's own name: a name such
 * as `SOURCE_NOT_SEEKABLE` is a protocol field, not guidance. The copy is a
 * function of the status alone, so a feature that needs to know which status
 * it was branches on the status, never on this text ("Testing": localized copy
 * does not affect program flow).
 *
 * Every credential and slot failure reads the same. "Authentication errors"
 * makes their external result equivalent, `DESIGN.md` §14.1 forbids saying why
 * a candidate slot failed, and the test matrix of `security/DECOY_VAULT.md`
 * §11 wants error copy that no identity can tell apart.
 * [ChurStatus.KDF_MEMORY_UNAVAILABLE] keeps its own line: the memory floor is a
 * constant of the unlock procedure, identical for every identity (§5 of the
 * same document), and the user can act on it. An unrecognized code already arrives as
 * [ChurStatus.INTERNAL_FAILURE], because `ChurStatus.fromValue` folds it there.
 *
 * [ChurStatus.MIGRATION_REQUIRED] asks for a newer Chur rather than a retry.
 * Opening or restoring a vault already runs every migration this build knows,
 * so the status reaches the app only for a format newer than the build, and
 * the migration the table's "run migration" names runs in the updated app.
 */
fun userCopy(status: ChurStatus): String = when (status) {
    ChurStatus.AUTHENTICATION_FAILED,
    ChurStatus.PLATFORM_KEY_UNAVAILABLE,
    ChurStatus.PLATFORM_KEY_INVALIDATED,
    ChurStatus.RECOVERY_REQUIRED,
    -> "Unable to unlock. Try again or use recovery."
    ChurStatus.VAULT_LOCKED,
    ChurStatus.SESSION_EXPIRED,
    -> "The vault locked. Unlock it to continue."
    ChurStatus.PROTECTED_DATA_UNAVAILABLE -> "Unlock your device, then try again."
    ChurStatus.KDF_MEMORY_UNAVAILABLE -> "Not enough free memory. Close other apps, then try again."
    ChurStatus.CANCELLED -> "Cancelled."
    ChurStatus.INVALID_INPUT -> "Chur could not use what was entered. Check it and try again."
    ChurStatus.RESOURCE_LIMIT_EXCEEDED -> "This is larger than Chur supports. Choose another file."
    ChurStatus.PERMISSION_DENIED -> "Chur does not have access. Allow access or choose another file."
    ChurStatus.NOT_FOUND -> "This item is no longer available. Refresh and try again."
    ChurStatus.CONFLICT -> "This changed on another device. Refresh and try again."
    ChurStatus.SYNC_CHAIN_FORK -> "The sync server sent conflicting changes. Sync stopped."
    ChurStatus.SYNC_HEAD_ROLLBACK -> "The sync server sent older data than this device has. Sync stopped."
    ChurStatus.UNSUPPORTED_VERSION,
    ChurStatus.UNSUPPORTED_SUITE,
    -> "This version of Chur cannot open it. Update Chur or choose another file."
    ChurStatus.NON_CANONICAL_ENCODING -> "This file is damaged or is not a Chur file. Choose another file."
    ChurStatus.ABI_INCOMPATIBLE -> "This copy of Chur is incomplete. Update or reinstall the app."
    ChurStatus.MIGRATION_REQUIRED -> "This vault needs a newer version of Chur. Update Chur, then try again."
    ChurStatus.MIGRATION_FAILED -> "The vault update did not finish. Try again."
    ChurStatus.VAULT_INCOMPLETE -> "The last change did not finish. Try again."
    ChurStatus.VAULT_CORRUPT -> "The vault could not be verified. Restore it from a backup."
    ChurStatus.OBJECT_INCOMPLETE -> "This item is incomplete. Import it again."
    ChurStatus.OBJECT_CORRUPT -> "Item could not be verified. Restore it from a backup."
    ChurStatus.CATALOG_CORRUPT -> "The library could not be verified. Restore it from a backup."
    ChurStatus.IO_FAILURE -> "Chur could not read or write storage. Free some space and try again."
    ChurStatus.STORAGE_UNAVAILABLE -> "Not enough storage. Free space or choose another location."
    ChurStatus.SOURCE_NOT_SEEKABLE -> "This file cannot be read directly. Copy it to the device first."
    ChurStatus.SOURCE_DOWNLOAD_REQUIRED -> "Download this file in its app first, then try again."
    ChurStatus.NETWORK_FAILURE -> "Cannot reach the server. Try again later."
    ChurStatus.INTERNAL_FAILURE -> "Something went wrong. Try again."
}

/**
 * The sentence a user reads when the sync server refuses a request.
 *
 * A server refusal reuses the stable codes, and two of them mean something
 * else there. `docs/sync/SERVER_OPERATOR.md` answers every failed token check
 * with [ChurStatus.AUTHENTICATION_FAILED], a wrong bootstrap secret included,
 * so [userCopy]'s unlock line would send the user to a password and a recovery
 * phrase that cannot help. Nothing needs unlocking: the fix is the secret this
 * device set up sync with, and the same holds for
 * [ChurStatus.PERMISSION_DENIED]. The line is the same for every identity, so
 * the `security/DECOY_VAULT.md` §11 rule on error copy still holds. Every other
 * status reads as [userCopy] has it.
 */
fun syncCopy(status: ChurStatus): String = when (status) {
    ChurStatus.AUTHENTICATION_FAILED,
    ChurStatus.PERMISSION_DENIED,
    -> "The sync server did not accept this device. Check the bootstrap secret and set up sync again."
    else -> userCopy(status)
}
