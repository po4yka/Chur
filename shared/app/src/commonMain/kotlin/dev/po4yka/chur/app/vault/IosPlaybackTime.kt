package dev.po4yka.chur.app.vault

internal fun iosPlaybackTime(seconds: Float): String {
    val total = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0f)?.toLong() ?: 0L
    val second = (total % 60).toString().padStart(2, '0')
    val minute = (total / 60 % 60).toString().padStart(2, '0')
    return if (total >= 3_600) "${total / 3_600}:$minute:$second" else "${total / 60}:$second"
}

internal fun iosPlaybackTimeDescription(position: Float, duration: Float): String {
    val elapsed = position.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return if (duration.isFinite() && duration > 0f) {
        "${iosPlaybackTime(elapsed.coerceAtMost(duration))} elapsed, " +
            "${iosPlaybackTime((duration - elapsed).coerceAtLeast(0f))} remaining"
    } else {
        "${iosPlaybackTime(elapsed)} elapsed, duration unavailable"
    }
}
