package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.DeleteGlyph
import dev.po4yka.chur.app.theme.DiagnosticTextStyle
import dev.po4yka.chur.app.theme.ExportGlyph
import dev.po4yka.chur.app.theme.FavoriteGlyph
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection

/**
 * The media viewer, `DESIGN.md` §13.
 *
 * The viewer has its own ladder, §6.2: a black canvas, a scrim for the chrome,
 * and white content. It does not use the application surfaces, because media is
 * the subject and a light surface behind a photograph changes how the
 * photograph reads.
 *
 * The detail sheet is where the private text lives, and only there: §16.1 of
 * `CATALOG_SCHEMA_V1.md` keeps a filename out of the grid so a page of two
 * hundred rows never carries two hundred filenames, and this screen fetches one
 * object's record.
 */
@Composable
fun ViewerScreen(
    projection: ObjectProjection,
    detail: ObjectDetail?,
    preview: ImageBitmap?,
    showDetail: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onToggleDetail: () -> Unit,
    player: (@Composable (Modifier) -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize().background(ViewerColors.canvas)) {
        if (player != null) {
            // A video or a recording is played rather than shown. The player is
            // a slot rather than a call, because it is the one part of this
            // screen that reads a repository, and this file's rule is that a
            // screen is a pure function of a state value.
            player(Modifier.fillMaxSize())
        } else if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Decrypting",
                    color = ViewerColors.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // §13: the chrome sits over a scrim so controls stay legible against
        // both bright and dark media, which §6.4 requires them to be tested on.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(ViewerColors.chromeScrim)
                .padding(ChurSpacing.two),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(BackGlyph, contentDescription = "Back", tint = ViewerColors.content)
            }
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleDetail) {
                Text(
                    if (showDetail) "Hide info" else "Info",
                    color = ViewerColors.content,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(ViewerColors.chromeScrim)
                .padding(ChurSpacing.two),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    FavoriteGlyph,
                    contentDescription = if (projection.favorite) "Remove favourite" else "Favourite",
                    tint = ViewerColors.content,
                )
            }
            IconButton(onClick = onExport) {
                Icon(ExportGlyph, contentDescription = "Export", tint = ViewerColors.content)
            }
            IconButton(onClick = onDelete) {
                Icon(DeleteGlyph, contentDescription = "Delete", tint = ViewerColors.content)
            }
        }

        if (showDetail && detail != null) {
            DetailSheet(
                detail = detail,
                projection = projection,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp()),
            )
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

/**
 * The one place a filename, a caption, and a tag reach the screen.
 *
 * §16.1 of the catalog is the reason it is one place: a caller that fetched
 * this record per row would be defeating the rule the projection exists for.
 */
@Composable
private fun DetailSheet(
    detail: ObjectDetail,
    projection: ObjectProjection,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ViewerColors.chromeScrim)
            .padding(ChurSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ChurSpacing.one),
    ) {
        Text(
            text = detail.filename.ifBlank { "No filename" },
            color = ViewerColors.content,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.caption.isNotBlank()) {
            Text(
                detail.caption,
                color = ViewerColors.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = buildString {
                append(detail.contentType)
                if (detail.width > 0 && detail.height > 0) {
                    append("  ·  ${detail.width} × ${detail.height}")
                }
                append("  ·  ${humanSize(detail.plaintextSize)}")
            },
            color = ViewerColors.content,
            style = DiagnosticTextStyle,
        )
        // §8.1 of the catalog: a substituted capture time is not a capture
        // time, and the interface declines to present one it does not have.
        Text(
            text = if (detail.captureTimeSubstituted) {
                "No capture date recorded"
            } else {
                "Captured"
            },
            color = ViewerColors.content,
            style = MaterialTheme.typography.bodySmall,
        )
        val state = PresentedState.of(projection)
        if (state != PresentedState.ORDINARY) {
            Text(state.label, color = ViewerColors.content, style = MaterialTheme.typography.bodySmall)
        }
        if (detail.tags.isNotEmpty()) {
            Text(
                detail.tags.joinToString(", ") { it.second },
                color = ViewerColors.content,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A size a person reads.
 *
 * It is deliberately coarse: the exact byte count of a private object is a
 * value `DISCREET_MODE.md` §30 would rather not have on a screenshot, and a
 * reader does not need it.
 */
fun humanSize(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit += 1
    }
    return if (unit == 0) {
        "${bytes} ${units[0]}"
    } else {
        val rounded = ((value * 10).toLong()) / 10.0
        "$rounded ${units[unit]}"
    }
}
