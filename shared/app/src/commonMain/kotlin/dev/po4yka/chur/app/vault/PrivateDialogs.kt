package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing

/** Bound only around private routes: a public dialog never advertises a private action. */
val LocalPrivatePanic = staticCompositionLocalOf<(() -> Unit)?> { null }

/** A modal owns its window's input; the covered screen's lock cannot receive it. */
@Composable
fun PrivateAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val panic = LocalPrivatePanic.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
                panic?.let { hide ->
                    TextButton(onClick = hide, modifier = Modifier.fillMaxWidth()) { Text("Lock and hide") }
                }
                title?.invoke()
            }
        },
        text = text,
    )
}

/** Private menus keep their exit outside the list's scroll container. */
@Composable
fun PrivateDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val panic = LocalPrivatePanic.current
    if (panic == null) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
        return
    }
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val safeInsets = WindowInsets.safeDrawing
    val maxHeight = with(density) {
        (windowHeight - safeInsets.getTop(density) - safeInsets.getBottom(density)).toDp()
    }.minus(112.dp).coerceAtLeast(48.dp)
    // Material3 reserves 48 dp above and below the popup, plus 8 dp of content
    // padding at each end. Bound its single child to that available height so
    // only the inner list scrolls; anchoring, focus, keyboard and insets remain
    // owned by DropdownMenu.
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        Column(Modifier.width(IntrinsicSize.Max).heightIn(max = maxHeight)) {
            TextButton(onClick = panic, modifier = Modifier.fillMaxWidth()) { Text("Lock and hide") }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        }
    }
}
