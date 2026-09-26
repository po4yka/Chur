package dev.po4yka.chur.app

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * The keyboard of a text field on a private route that holds no secret: a
 * search, an album or tag name, a server address, an enrollment.
 *
 * `DESIGN.md` §12.2 keeps query text inside the unlocked session and §19.1
 * keeps private-vault metadata out of suggestions. Autocorrection is off
 * because it is the only lever the common `KeyboardOptions` exposes on iOS
 * (`UITextAutocorrectionTypeNo`, which also turns off UIKit's default spell
 * checking). On Android, [PrivateTextInput] makes the no-learning request once
 * for the whole route.
 */
internal fun privateKeyboardOptions(keyboardType: KeyboardType = KeyboardType.Unspecified): KeyboardOptions =
    KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType)

/**
 * The keyboard of a secret: a password, a PIN, a recovery phrase, a bootstrap secret.
 *
 * `PASSWORD_PROFILE.md` §2: a secure platform text field, with autocorrection,
 * suggestions, and capitalization off. The password type tells the platform
 * that the field is secure and wants no suggestions: `TYPE_TEXT_VARIATION_PASSWORD`
 * on Android, secure entry on iOS. Neither keyboard learns from such a field.
 */
internal fun secretKeyboardOptions(pin: Boolean = false, imeAction: ImeAction = ImeAction.Unspecified): KeyboardOptions =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password,
        imeAction = imeAction,
    )

/**
 * Asks the keyboard not to learn from any text field under [content] while [enabled].
 *
 * A keyboard that learns a typed word suggests it again in the public shell,
 * in the decoy vault, and in every other app. That breaks `DESIGN.md` §12.2
 * and §19.1. [ChurApp] enables it on every route except the public shell.
 */
@Composable
internal expect fun PrivateTextInput(enabled: Boolean, content: @Composable () -> Unit)
