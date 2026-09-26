package dev.po4yka.chur.app.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A quiet echo of the launcher cord on private surfaces only. */
@Composable
fun PrivateBoundaryMark(size: Dp, media: Boolean = false, modifier: Modifier = Modifier) {
    val colors = LocalChurColors.current
    // The source icon's deep red needs a lighter value against the dark canvas.
    val cord = if (colors.dark) Color(0xFFD54A47) else Color(0xFFA01818)
    Canvas(modifier = modifier.size(size)) {
        val edge = this.size.minDimension
        val ringWidth = 3.dp.toPx()
        drawArc(
            color = cord,
            startAngle = 55f,
            sweepAngle = 295f,
            useCenter = false,
            topLeft = Offset(ringWidth / 2f, ringWidth / 2f),
            size = Size(edge - ringWidth, edge - ringWidth),
            style = Stroke(width = ringWidth, cap = StrokeCap.Round),
        )
        if (media) {
            val line = 2.dp.toPx()
            val ink = colors.ink
            drawRoundRect(
                color = ink,
                topLeft = Offset(edge * 0.29f, edge * 0.31f),
                size = Size(edge * 0.42f, edge * 0.38f),
                cornerRadius = CornerRadius(line),
                style = Stroke(width = line),
            )
            drawCircle(ink, radius = edge * 0.035f, center = Offset(edge * 0.61f, edge * 0.41f))
            drawLine(ink, Offset(edge * 0.31f, edge * 0.63f), Offset(edge * 0.44f, edge * 0.50f), line)
            drawLine(ink, Offset(edge * 0.44f, edge * 0.50f), Offset(edge * 0.54f, edge * 0.59f), line)
            drawLine(ink, Offset(edge * 0.54f, edge * 0.59f), Offset(edge * 0.61f, edge * 0.53f), line)
            drawLine(ink, Offset(edge * 0.61f, edge * 0.53f), Offset(edge * 0.69f, edge * 0.61f), line)
        }
    }
}
