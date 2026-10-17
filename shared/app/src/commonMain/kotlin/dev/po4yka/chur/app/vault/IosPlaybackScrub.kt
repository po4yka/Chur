package dev.po4yka.chur.app.vault

/** A drag preview stays independent of AVPlayer's sampled clock and pending seeks. */
internal data class IosPlaybackScrub(
    val preview: Float? = null,
    val target: Float? = null,
    val revision: Int = 0,
) {
    val active: Boolean get() = preview != null || target != null

    fun displayedPosition(confirmed: Float): Float = preview ?: target ?: confirmed

    fun previewAt(position: Float): IosPlaybackScrub = copy(preview = position)

    fun commit(): IosPlaybackScrub = preview?.let {
        copy(preview = null, target = it, revision = revision + 1)
    } ?: this

    /** A superseded native completion must not clear the next seek's target. */
    fun complete(completedRevision: Int): IosPlaybackScrub =
        if (completedRevision == revision) copy(target = null) else this
}
