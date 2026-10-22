package dev.po4yka.chur.app

/** A public, device-wide presentation choice, independent of vault identity. */
enum class PublicShell(val storedValue: String, val label: String) {
    NOTES("notes", "Notes"),
    CALCULATOR("calculator", "Calculator"),
    CAMERA("camera", "Camera"),
    PET("pet", "Chur"),
}

class PublicShellSetting(private val reader: () -> String?, private val writer: (String) -> Unit) {
    fun read(): PublicShell {
        val value = reader()?.takeIf { it.length <= 32 }?.trim()
        return PublicShell.entries.firstOrNull { it.storedValue == value } ?: PublicShell.NOTES
    }
    fun write(shell: PublicShell) = writer(shell.storedValue)

    companion object {
        fun unset(): PublicShellSetting = PublicShellSetting({ null }, {})
    }
}
