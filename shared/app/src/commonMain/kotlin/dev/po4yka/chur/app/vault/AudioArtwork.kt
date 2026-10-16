package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.AudioGlyph
import dev.po4yka.chur.app.theme.ViewerColors

/** The audio canvas is independent of play/pause state and never supplies transport controls. */
@Composable
fun AudioArtwork(
    preview: ImageBitmap?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(modifier.padding(contentPadding), contentAlignment = Alignment.Center) {
        if (preview != null) Image(bitmap = preview, contentDescription = "Audio artwork",
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().testTag("audio-artwork-image"))
        else Icon(AudioGlyph, contentDescription = "Audio recording", tint = ViewerColors.content,
            modifier = Modifier.size(96.dp).testTag("audio-artwork-placeholder"))
    }
}
