package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.imports.Waveform

/**
 * Draws the audio waveform of `docs/interop/MEDIA_PIPELINE.md` §6.1.
 *
 * The record is a peak envelope rather than a picture, which is the whole point
 * of §6.1's decision: the drawing resamples to whatever width it has, follows
 * the viewer's own palette, and needs no second derivative for a second theme.
 *
 * The record is bounded at 4096 buckets and a strip is a few hundred pixels
 * wide, so buckets are folded rather than interpolated: each column takes the
 * loudest bucket that falls in it, which is what a peak envelope means and what
 * an averaged column would quietly stop being.
 */
@Composable
fun WaveformStrip(
    record: ByteArray?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val peaks = record?.let { Waveform.peaksOf(it) } ?: return
    if (peaks.isEmpty()) return

    Box(modifier = modifier.fillMaxWidth().padding(ChurSpacing.gutter)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(WAVEFORM_HEIGHT)) {
            val columns = (size.width / COLUMN_STRIDE).toInt().coerceAtLeast(1)
            val middle = size.height / 2f
            for (column in 0 until columns) {
                val from = column.toLong() * peaks.size / columns
                val to = ((column + 1).toLong() * peaks.size / columns).coerceAtMost(
                    peaks.size.toLong(),
                )
                var loudest = 0
                var index = from
                while (index < to) {
                    val value = peaks[index.toInt()].toInt() and 0xff
                    if (value > loudest) loudest = value
                    index++
                }
                // A silent column still draws a hairline, so the strip reads as
                // a recording with a quiet passage rather than as a gap in the
                // drawing.
                val half = (loudest / 255f) * middle
                val x = column * COLUMN_STRIDE + COLUMN_STRIDE / 2f
                drawLine(
                    color = color,
                    start = Offset(x, middle - half),
                    end = Offset(x, middle + half),
                    strokeWidth = COLUMN_WIDTH,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val WAVEFORM_HEIGHT = 96.dp
private const val COLUMN_STRIDE = 4f
private const val COLUMN_WIDTH = 2f
