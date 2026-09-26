package dev.po4yka.chur.app

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Android: `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` on every input
 * session under [content].
 *
 * The flag (API 26, below the minSdk 29 of ADR-0017) is the platform's
 * incognito request. The keyboard does not add the typed text to its
 * dictionary or keep it in its history. Compose 1.12 has no `KeyboardOptions`
 * field for the flag, because `PlatformImeOptions` carries only
 * `privateImeOptions`. So the flag is added where Compose builds the
 * `EditorInfo`. The flag is a request, not a boundary: a keyboard that
 * ignores it is the malicious-keyboard row of `ARCHITECTURE.md` §37.2.
 */
@Composable
internal actual fun PrivateTextInput(enabled: Boolean, content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(if (enabled) withoutPersonalizedLearning(request) else request)
        },
        content = content,
    )
}

/**
 * [request], with the no-learning flag added after the request fills the `EditorInfo`.
 *
 * Compose assigns `imeOptions` outright when it fills the `EditorInfo`, so a
 * flag set before [request] runs is lost.
 */
internal fun withoutPersonalizedLearning(request: PlatformTextInputMethodRequest): PlatformTextInputMethodRequest =
    PlatformTextInputMethodRequest { outAttributes ->
        request.createInputConnection(outAttributes).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }
