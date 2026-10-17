package dev.po4yka.chur.app.vault

/** UI states remain distinct while AVPlayer waits for authenticated bytes or a decoder. */
internal enum class IosPlaybackPhase { INITIAL, BUFFERING, PLAYING, PAUSED, ENDED }

internal fun iosPlaybackPhase(
    started: Boolean,
    advancing: Boolean,
    waiting: Boolean,
    position: Float,
    duration: Float,
): IosPlaybackPhase = when {
    waiting -> IosPlaybackPhase.BUFFERING
    advancing -> IosPlaybackPhase.PLAYING
    !started -> IosPlaybackPhase.INITIAL
    duration > 0 && position >= duration -> IosPlaybackPhase.ENDED
    else -> IosPlaybackPhase.PAUSED
}
