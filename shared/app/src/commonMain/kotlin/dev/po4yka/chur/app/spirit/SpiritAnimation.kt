package dev.po4yka.chur.app.spirit


/** Eye targets are surface-conforming silhouettes, not scaled black discs. */
object SpiritEyes {
    private val levels = floatArrayOf(0f, .0025f, .005f, .01f, .025f, .075f, .15f, .3f, .5f, .7f, .85f, 1f)
    fun weights(aperture: Float): FloatArray {
        val result = FloatArray(11)
        val value = if (aperture.isFinite()) aperture.coerceIn(0f, 1f) else 1f
        for (i in 0 until levels.lastIndex) {
            if (value <= levels[i + 1]) {
                val t = (value - levels[i]) / (levels[i + 1] - levels[i])
                result[i] = 1f - t
                if (i + 1 < result.size) result[i + 1] = t
                return result
            }
        }
        return result
    }
    fun aperture(sample: SpiritClipSample, reduced: Boolean): Float {
        if (reduced) return if (sample.name == "sleep_loop") 0f else 1f
        val duration = when (sample.name) {
            "idle_soft" -> 4f; "notice" -> .6f; "tap_react", "release_settle" -> .8f
            "hold_enter" -> .2f; "hold_loop" -> 1.2f; "sleep_enter" -> 1f
            "sleep_loop" -> 3f; "wake" -> .7f; else -> 1f
        }
        val t = (sample.seconds / duration).coerceIn(0f, 1f)
        val u = t * t * (3f - 2f * t)
        fun blink(start: Float, closedFrom: Float, closedUntil: Float, end: Float): Float {
            fun smooth(v: Float): Float { val x = v.coerceIn(0f, 1f); return x*x*(3f-2f*x) }
            val seconds = sample.seconds
            return when {
                seconds < start || seconds > end -> 1f
                seconds < closedFrom -> 1f - smooth((seconds-start)/(closedFrom-start))
                seconds <= closedUntil -> 0f
                else -> smooth((seconds-closedUntil)/(end-closedUntil))
            }
        }
        return when (sample.name) {
            "idle_soft" -> blink(2.60f, 2.66f, 2.78f, 2.90f)
            "tap_react" -> blink(.09f, .15f, .25f, .36f)
            "hold_enter" -> 1f - .16f * u
            "hold_loop" -> .84f
            "release_settle" -> .84f + .16f * u
            "sleep_enter" -> 1f - u
            "sleep_loop" -> 0f
            "wake" -> u
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}

/** Native clocks pause with the host; no coroutine keeps an invisible mascot alive. */
data class SpiritClipSample(val name: String, val seconds: Float)
fun sampleSpiritClip(requested: String, elapsed: Float, reduced: Boolean): SpiritClipSample {
    if (reduced) return SpiritClipSample(if (requested == "sleep_enter" || requested == "sleep_loop") "sleep_loop" else "idle_soft", 0f)
    val t = elapsed.coerceAtLeast(0f)
    return when (requested) {
        "hold_enter" -> if (t < .2f) SpiritClipSample(requested, t) else SpiritClipSample("hold_loop", (t - .2f) % 1.2f)
        "tap_react", "release_settle" -> if (t < .8f) SpiritClipSample(requested, t) else SpiritClipSample("idle_soft", (t - .8f) % 4f)
        "hold_loop" -> SpiritClipSample("hold_loop", t % 1.2f)
        "sleep_enter" -> if (t < 1f) SpiritClipSample(requested, t) else SpiritClipSample("sleep_loop", (t - 1f) % 3f)
        "sleep_loop" -> SpiritClipSample("sleep_loop", t % 3f)
        "wake" -> if (t < .7f) SpiritClipSample(requested, t) else SpiritClipSample("idle_soft", (t - .7f) % 4f)
        "notice" -> if (t < .6f) SpiritClipSample(requested, t) else SpiritClipSample("idle_soft", (t - .6f) % 4f)
        else -> SpiritClipSample("idle_soft", t % 4f)
    }
}
