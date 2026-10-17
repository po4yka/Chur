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

/** An interaction holds the controls until it ends; the idle timeout starts after it. */
internal fun iosPlaybackControlsHeld(
    pinned: Boolean,
    voiceOver: Boolean,
    keyboard: Boolean,
    focused: Boolean,
    scrubbing: Boolean,
): Boolean = pinned || voiceOver || keyboard || focused || scrubbing

/** Opening an untouched paused page preserves the viewer's hidden chrome. */
internal fun iosPlaybackShouldReveal(
    started: Boolean,
    phase: IosPlaybackPhase,
    held: Boolean,
): Boolean = held || (started && phase != IosPlaybackPhase.PLAYING)
