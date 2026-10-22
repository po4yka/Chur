package dev.po4yka.chur.app.notes

/** Public storage is not encrypted and enters system backup. */
object Disclosure {
    const val FIRST_WRITE = "Notes are not encrypted by this app. Anyone with this unlocked phone can read them, and they are copied by the system backup."
    const val SETTINGS = "Notes, calculation history, photos and videos are not encrypted by this app. Anyone with access to this unlocked device can read them. They are copied by the system backup."
    const val EMPTY_STATE = "Write something down."
    const val VAULT_ENTRY = "Private vault"
}
