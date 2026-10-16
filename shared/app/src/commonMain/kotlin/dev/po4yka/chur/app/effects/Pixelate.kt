package dev.po4yka.chur.app.effects

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Use [Modifier.mosaic] for the neutral Chur effect name. */
@Deprecated(
    message = "Use mosaic",
    replaceWith = ReplaceWith(
        "this.mosaic(blockRatio, minBlockSize, maxBlockSize, featherEdges)",
        "dev.po4yka.chur.app.effects.mosaic",
    ),
)
public fun Modifier.pixelate(
    blockRatio: Float = DefaultBlockRatio,
    minBlockSize: Dp = 4.dp,
    maxBlockSize: Dp = 14.dp,
    featherEdges: Boolean = true,
): Modifier = mosaic(blockRatio, minBlockSize, maxBlockSize, featherEdges)

/** Use [Mosaic] for the neutral Chur effect name. */
@Deprecated(
    message = "Use Mosaic",
    replaceWith = ReplaceWith(
        "Mosaic(modifier, blockRatio, minBlockSize, maxBlockSize, featherEdges, content)",
        "dev.po4yka.chur.app.effects.Mosaic",
    ),
)
@Composable
public fun Pixelate(
    modifier: Modifier = Modifier,
    blockRatio: Float = DefaultBlockRatio,
    minBlockSize: Dp = 4.dp,
    maxBlockSize: Dp = 14.dp,
    featherEdges: Boolean = true,
    content: @Composable () -> Unit,
) {
    Mosaic(modifier, blockRatio, minBlockSize, maxBlockSize, featherEdges, content)
}
