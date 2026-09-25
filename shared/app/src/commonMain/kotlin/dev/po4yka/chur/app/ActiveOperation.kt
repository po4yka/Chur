package dev.po4yka.chur.app

/** Non-private progress for the operation currently owned by the user. */
data class ActiveOperation(
    val id: Long,
    val name: String,
    val processed: Long = 0,
    val total: Long = 0,
    val stage: Int = 1,
    val cancelling: Boolean = false,
    val cancellable: Boolean = true,
) {
    val fraction: Float? get() = total.takeIf { it > 0 }
        ?.let { (processed.coerceIn(0, it).toDouble() / it).toFloat() }

    val description: String get() = when {
        cancelling -> "Cancelling $name…"
        stage >= 3 -> "Finishing $name…"
        stage == 1 -> "Preparing $name…"
        total > 0 -> "$name: $processed of $total ${if (name == "verification") "objects" else "bytes"}"
        else -> "$name…"
    }
}
