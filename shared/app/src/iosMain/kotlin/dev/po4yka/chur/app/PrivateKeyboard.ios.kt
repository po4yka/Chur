package dev.po4yka.chur.app

import androidx.compose.runtime.Composable

/**
 * iOS: nothing to add around [content].
 *
 * UIKit has no per-field request not to learn, and Compose exposes none.
 * On iOS, the `KeyboardOptions` of each field carry the policy:
 * [secretKeyboardOptions] maps to secure entry, and [privateKeyboardOptions]
 * maps to `UITextAutocorrectionTypeNo`.
 */
@Composable
internal actual fun PrivateTextInput(enabled: Boolean, content: @Composable () -> Unit) {
    content()
}
