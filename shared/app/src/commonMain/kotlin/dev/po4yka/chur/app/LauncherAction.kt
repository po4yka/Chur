package dev.po4yka.chur.app

/** Public commands only: no vault identity, query, content or credential payload. */
enum class LauncherAction(val shortcutId: String) {
    PUBLIC_NOTES("public-notes"),
    PUBLIC_CALCULATOR("public-calculator"),
    PUBLIC_CAMERA("public-camera"),
    IMPORT("import"),
    SEARCH("search"),
    LOCK_AND_HIDE("lock-and-hide"),
    NEW_PRIVATE_NOTE("new-private-note"),
    ;

    val publicShell: PublicShell? get() = when (this) {
        PUBLIC_NOTES -> PublicShell.NOTES
        PUBLIC_CALCULATOR -> PublicShell.CALCULATOR
        PUBLIC_CAMERA -> PublicShell.CAMERA
        else -> null
    }
    val externalAction: String get() = "dev.po4yka.chur.action.$shortcutId"

    companion object {
        fun fromExternalAction(value: String?): LauncherAction? = entries.firstOrNull { it.externalAction == value }
    }
}

/** An authorized, process-local delivery; never persist it in platform saved state. */
data class LauncherRequest(val token: Long, val action: LauncherAction, val generation: Long)
