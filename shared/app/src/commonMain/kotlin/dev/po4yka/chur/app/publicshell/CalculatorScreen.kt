package dev.po4yka.chur.app.publicshell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.SettingsGlyph

/** Public arithmetic only. Settings is the same visible entry used by the Notes shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalChurColors.current
    var state by rememberSaveable(stateSaver = listSaver<CalculatorState, String>(
        save = { it.save() }, restore = { CalculatorState.restore(it) },
    )) { mutableStateOf(CalculatorState()) }
    Scaffold(
        modifier = modifier,
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(title = { Text("Calculator") }, actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(SettingsGlyph, contentDescription = "Settings")
                }
            })
        },
    ) { padding ->
        // Vertical scroll preserves every key on small windows, landscape, and large fonts.
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
            ) {
                Text(
                    text = state.pendingExpression.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.inkMuted,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
                Text(
                    text = state.error ?: state.display,
                    style = if (state.error == null) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge,
                    color = if (state.error == null) colors.ink else MaterialTheme.colorScheme.error,
                    softWrap = state.error != null,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).semantics {
                        contentDescription = "Calculator result: ${state.error ?: state.display}"
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            Spacer(Modifier.heightIn(min = ChurSpacing.three))
            for (row in calculatorRows) {
                Row(
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                ) {
                    for (key in row) {
                        val action = key.isOperator || key == CalculatorKey.EQUALS
                        Button(
                            onClick = { state = state.press(key) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                                contentDescription = key.label
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (action) colors.accent else colors.surfaceRaised,
                                contentColor = if (action) MaterialTheme.colorScheme.onPrimary else colors.ink,
                            ),
                        ) {
                            Text(key.symbol, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

private val calculatorRows = listOf(
    listOf(CalculatorKey.CLEAR, CalculatorKey.BACKSPACE, CalculatorKey.PERCENT, CalculatorKey.DIVIDE),
    listOf(CalculatorKey.SEVEN, CalculatorKey.EIGHT, CalculatorKey.NINE, CalculatorKey.MULTIPLY),
    listOf(CalculatorKey.FOUR, CalculatorKey.FIVE, CalculatorKey.SIX, CalculatorKey.SUBTRACT),
    listOf(CalculatorKey.ONE, CalculatorKey.TWO, CalculatorKey.THREE, CalculatorKey.ADD),
    listOf(CalculatorKey.SIGN, CalculatorKey.ZERO, CalculatorKey.DECIMAL, CalculatorKey.EQUALS),
)
