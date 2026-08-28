package dev.po4yka.chur.app.notes

/**
 * The public-shell disclosure of `docs/product/DISCREET_MODE.md`.
 *
 * The product deliberately asks the user to keep real content in the public
 * shell, because a shell nobody uses announces what it hides. That section
 * therefore requires the application to say what it costs, and fixes the shape
 * of what it says:
 *
 * - the first time the user writes public-shell content, the application states
 *   once that this content is not encrypted by Chur, and names the private
 *   vault as the protected alternative;
 * - public-shell settings carry the same statement permanently, beside the
 *   entry that reaches the vault;
 * - the copy must not present the disclosure as a security feature and must not
 *   imply that the public shell is private;
 * - the settings statement carries both backup halves: public-shell content is
 *   in the platform backup, and vault content is not and leaves the device only
 *   through the package of `docs/format/BACKUP_FORMAT_V1.md`.
 *
 * The strings live here rather than inside a composable so that a test can hold
 * them to those rules without rendering anything, and so that the two places
 * that must agree cannot drift apart.
 */
object Disclosure {

    /** Shown once, on the first public-shell write. */
    const val FIRST_WRITE: String =
        "Notes are not encrypted by Chur. Anyone with this unlocked phone can read them, " +
            "and they are copied by the system backup. The private vault is the protected " +
            "place; open it from Settings."

    /** Shown permanently in public-shell settings, beside the vault entry. */
    const val SETTINGS: String =
        "Notes are not encrypted by Chur and are copied by the system backup. Vault content " +
            "is encrypted, is excluded from the system backup, and leaves this device only " +
            "in a backup package you create yourself."

    /** The empty-state line of the notes list. */
    const val EMPTY_STATE: String = "Write something down."

    /** The label of the row that reaches the vault. */
    const val VAULT_ENTRY: String = "Private vault"
}
