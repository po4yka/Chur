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
    /**
     * The picked item an import is on, from 1, and how many the picker
     * returned; 0 for work that is not an import of picked items.
     */
    val item: Int = 0,
    val items: Int = 0,
) {
    val fraction: Float? get() = total.takeIf { it > 0 }
        ?.let { (processed.coerceIn(0, it).toDouble() / it).toFloat() }

    /**
     * What the progress card reads.
     *
     * An import names its item and its phase in the words of `DESIGN.md`
     * §15.2, "Importing item 3 of 12 · Encrypting", and leaves the bytes to
     * the bar: a count of bytes is not a unit the user picked.
     */
    val description: String get() = when {
        cancelling -> "Cancelling $name…"
        items > 0 -> "$importing · $phase"
        stage >= 3 -> "Finishing $name…"
        stage == 1 -> "Preparing $name…"
        total > 0 -> "$name: $processed of $total ${if (name == "verification") "objects" else "bytes"}"
        else -> "$name…"
    }

    /**
     * What a screen reader hears, `DESIGN.md` §23.2.
     *
     * [description] can count bytes, and the count changes on every poll, so
     * a live region that read it would never stop talking. This says the same
     * in steps of ten percent, which change a few times in an operation, and
     * an import says which item it is on, as in "Importing item 3 of 12, 40
     * percent". It names the kind of operation and nothing it works on, as
     * [description] does.
     */
    val spoken: String get() = when {
        cancelling -> description
        items > 0 -> "$importing, ${if (stage == 2 && total > 0) "$percent percent" else phase}"
        stage >= 3 || stage == 1 || total <= 0 -> description
        else -> "$name, $percent percent"
    }

    private val importing: String get() = if (items > 1) "Importing item $item of $items" else "Importing"

    /** The native stage in the phase words of `DESIGN.md` §15.2. */
    private val phase: String get() = when {
        stage >= 3 -> "Adding to library"
        stage == 2 -> "Encrypting"
        else -> "Preparing"
    }

    private val percent: Long get() = processed.coerceIn(0, total) * 10 / total * 10
}
