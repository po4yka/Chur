package dev.po4yka.chur.app.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Observe the system setting without changing it or requesting write access. */
@Composable
internal actual fun rememberReducedMotion(): Boolean {
    val resolver: ContentResolver? = LocalContext.current.contentResolver
    var reducedMotion by remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        if (resolver == null) return@DisposableEffect onDispose {}
        var observing = false
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (observing) reducedMotion = readReducedMotion(resolver)
            }
        }
        observing = runCatching {
            for (setting in motionSettings) {
                resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
            }
        }.isSuccess
        // Read after registration so a change during setup cannot leave stale state.
        reducedMotion = !observing || readReducedMotion(resolver)
        onDispose {
            observing = false
            resolver.unregisterContentObserver(observer)
        }
    }
    return reducedMotion
}

private val motionSettings = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
)

private fun readReducedMotion(resolver: ContentResolver?): Boolean =
    runCatching {
        if (resolver == null) return@runCatching true
        motionSettings.any { setting ->
            // An unset scale uses Android's normal 1x default. A stored value
            // that cannot be parsed remains a conservative reduced-motion case.
            val stored = Settings.Global.getString(resolver, setting)
            val scale = if (stored == null) 1f else stored.toFloatOrNull() ?: 0f
            !scale.isFinite() || scale <= 0f
        }
    }.getOrDefault(true)
