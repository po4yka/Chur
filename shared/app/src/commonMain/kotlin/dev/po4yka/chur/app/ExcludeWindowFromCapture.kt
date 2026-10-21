package dev.po4yka.chur.app

import androidx.compose.runtime.Composable

/**
 * Excludes the window that composes this call from autofill and content capture.
 *
 * A dialog, sheet or popup is a separate platform window, outside the activity
 * window that [PrivacyCover] covers, so each private one calls this from inside
 * its own content. `ANDROID.md` §20.3.
 */
@Composable
internal expect fun ExcludeWindowFromCapture()
