package dev.po4yka.chur.app

/** Opt-in choice to gate the public shell as well as the encrypted vault. */
class AppLockSetting(
    private val reader: () -> Boolean,
    private val writer: (Boolean) -> Unit,
) {
    fun read(): Boolean = reader()
    fun write(enabled: Boolean) = writer(enabled)

    companion object {
        fun unset(): AppLockSetting = AppLockSetting({ false }, {})
    }
}
