package dev.po4yka.chur.app

/** Public commands only: no vault identity, query, content or credential payload. */
enum class LauncherAction(val shortcutId: String) {
    IMPORT("import"),
    SEARCH("search"),
    LOCK_AND_HIDE("lock-and-hide"),
    NEW_PRIVATE_NOTE("new-private-note"),
    ;

    val externalAction: String get() = "dev.po4yka.chur.action.$shortcutId"

    companion object {
        fun fromExternalAction(value: String?): LauncherAction? = entries.firstOrNull { it.externalAction == value }
    }
}

/** An authorized, process-local delivery; never persist it in platform saved state. */
data class LauncherRequest(val token: Long, val action: LauncherAction, val generation: Long)
