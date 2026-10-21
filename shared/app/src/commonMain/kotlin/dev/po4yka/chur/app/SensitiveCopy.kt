package dev.po4yka.chur.app

import androidx.compose.runtime.Composable

/**
 * Copies one secret code, such as a sync invitation or a pairing request, by an
 * explicit user action. The platform marks the clip as sensitive, so that the
 * clipboard preview and clipboard history do not show it. A selectable text
 * would copy it without that mark.
 */
@Composable
internal expect fun rememberSensitiveCopy(): (label: String, text: String) -> Unit
