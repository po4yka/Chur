package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.humanSize

/** Non-private progress for the operation currently owned by the user. */
data class ActiveOperation(
    val id: Long,
    /**
     * The kind of work: "import", "export", "backup", "restore" or
     * "verification". It is a key, not copy: the card reads [title].
     */
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
     * What the progress card reads: the work in the user's words and where it
     * is, as in "Exporting · 1.0 MB of 5.2 MB".
     *
     * An import names its item and its phase in the words of `DESIGN.md`
     * §15.2, "Importing item 3 of 12 · Encrypting", and leaves the bytes to
     * the bar: a count of bytes is not a unit the user picked. Other work
     * counts in coarse sizes, or in items for a check, so the card keeps its
     * progress readable as text, §22.3.
     */
    val description: String get() = status?.let { "$title · $it" } ?: "$title…"

    /**
     * What a screen reader hears, `DESIGN.md` §23.2.
     *
     * [description] can count bytes, and the count changes on every poll, so
     * a live region that read it would never stop talking. This says the same
     * in steps of ten percent, which change a few times in an operation, as in
     * "Importing item 3 of 12, 40 percent" or "Exporting, 40 percent". It names
     * the kind of work and nothing it works on, as [description] does.
     */
    val spoken: String get() = when {
        !cancelling && stage == 2 && total > 0 -> "$title, $percent percent"
        else -> status?.let { "$title, $it" } ?: title
    }

    /**
     * The work in the user's words. [name] is a key the controller chose, and
     * a reader hearing "verification, 40 percent" heard the key.
     */
    private val title: String get() = when (name) {
        "import" -> if (items > 1) "Importing item $item of $items" else "Importing"
        "export" -> "Exporting"
        "backup" -> "Writing backup"
        "restore" -> "Restoring"
        "verification" -> "Checking items"
        else -> "Working"
    }

    /** Where the work is, or `null` while it runs with no count. */
    private val status: String? get() = when {
        cancelling -> "Cancelling"
        items > 0 -> phase
        stage >= 3 -> "Finishing"
        stage == 1 -> "Preparing"
        total <= 0 -> null
        name == "verification" -> "$processed of $total"
        else -> "${humanSize(processed)} of ${humanSize(total)}"
    }

    /** The native stage in the phase words of `DESIGN.md` §15.2. */
    private val phase: String get() = when {
        stage >= 3 -> "Adding to library"
        stage == 2 -> "Encrypting"
        else -> "Preparing"
    }

    private val percent: Long get() = processed.coerceIn(0, total) * 10 / total * 10
}
