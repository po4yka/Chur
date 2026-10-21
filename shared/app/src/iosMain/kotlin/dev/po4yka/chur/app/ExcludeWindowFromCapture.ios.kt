package dev.po4yka.chur.app

import androidx.compose.runtime.Composable

/** iOS has no window-level switch. Its AutoFill reads only text fields that declare a content type. */
@Composable
internal actual fun ExcludeWindowFromCapture() = Unit
