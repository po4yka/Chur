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

    /**
     * What a screen reader hears, `DESIGN.md` §23.2.
     *
     * [description] counts bytes, and the count changes on every poll, so a
     * live region that read it would never stop talking. This says the same
     * in steps of ten percent, which change a few times in an operation. The
     * operation has no item count, so percent is the only number it can give.
     * It names the kind of operation and nothing it works on, as [description]
     * does.
     */
    val spoken: String get() = when {
        cancelling || stage >= 3 || stage == 1 || total <= 0 -> description
        else -> "$name, ${processed.coerceIn(0, total) * 10 / total * 10} percent"
    }
}
