package dev.po4yka.chur.app.spirit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.LocalChurMotion
import kotlin.math.roundToInt

/** A bounded overlay: the rest of the screen has no pointer input layer. */
@Composable
internal fun SpiritOverlay(modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val size = 144.dp
    val sizePx = with(LocalDensity.current) { size.toPx() }
    val reduced = LocalChurMotion.current.reducedMotion
    var model by remember { mutableStateOf(SpiritInteraction()) }
    var hasRoom by remember { mutableStateOf(false) }
    var positioned by remember { mutableStateOf(false) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    SideEffect { if (model.render.reducedMotion != reduced) model = model.motion(reduced) }
    Box(modifier.onSizeChanged {
        hasRoom = it.width >= sizePx && it.height >= sizePx
        model = model.bounds(it.width.toFloat(), it.height.toFloat(), sizePx).up(cancelled = true)
        if (!positioned) { model = model.copy(y = model.maxY); positioned = true }
    }) {
        if (hasRoom) Box(Modifier.offset { IntOffset(model.x.roundToInt(), model.y.roundToInt()) }.size(size)
            .onGloballyPositioned { coordinates[0] = it }
            .semantics {
                contentDescription = "Chur companion. Drag to move, activate to greet."
                customActions = listOf(
                    CustomAccessibilityAction("Hide Chur") { onDismiss(); true },
                    CustomAccessibilityAction("Reset position") { model = model.up(true).copy(x = 0f, y = model.maxY); true },
                )
                onClick("Greet Chur") { model = model.tap(); true }
            }
            .pointerInput(sizePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The renderer can move between input events. Use the actual committed layout,
                    // never the newest reducer offset (which might not be laid out yet).
                    val layout = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val start = layout.localToRoot(down.position)
                    model = model.down(start.x, start.y, down.uptimeMillis)
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (hasSecondarySpiritPointer(down.id.value, event.changes.filter { it.pressed }.map { it.id.value }) || change == null || change.isConsumed) { model = model.up(true); break }
                            if (!change.pressed) { model = model.up(); change.consume(); break }
                            val currentLayout = coordinates[0]?.takeIf { it.isAttached }
                            if (currentLayout == null) { model = model.up(true); break }
                            val point = currentLayout.localToRoot(change.position)
                            model = model.move(point.x, point.y, viewConfiguration.touchSlop, change.uptimeMillis, sizePx)
                            change.consume()
                        }
                    } finally {
                        model = model.up(cancelled = true)
                    }
                }
            }) {
            SpiritSurface(model.render, Modifier.fillMaxSize())
        }
    }
}

@Composable
internal expect fun SpiritSurface(state: SpiritRenderState, modifier: Modifier)
