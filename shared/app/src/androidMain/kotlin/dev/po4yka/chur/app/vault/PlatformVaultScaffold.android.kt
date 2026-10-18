package dev.po4yka.chur.app.vault

import android.app.UiModeManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * A renderer owned by the unlocked shell, never by the Activity or a singleton.
 * Sources contain only the shared content. Native video surfaces remain secure
 * and outside the capture; they are never copied to make the material work.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal actual fun PlatformVaultScaffold(
    state: VaultUiState,
    actions: VaultActions,
    navigationBusy: Boolean,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = LocalChurColors.current
    val direction = LocalLayoutDirection.current
    val highContrast = rememberHighContrastChrome()
    val effectsEnabled = Build.VERSION.SDK_INT >= 31 && !highContrast
    val source = rememberHazeState()
    // Exact source semantics keep this retention policy authoritative even on
    // newer OS versions. The experimental native window-backdrop flag stays off.
    val input = remember(source) {
        HazeInput.Sources(source, retention = HazeSourceRetention.ClearWhenUnavailable)
    }
    val glassStyle = remember(colors.surface) {
        GlassStyle.regular.then {
            backgroundColor(colors.surface)
            tint(colors.surface.copy(alpha = 0.54f))
            shape(RoundedCornerShape(0.dp))
            chromaticAberrationStrength(0f)
        }
    }
    val blurStyle = remember(colors.surface) {
        HazeBlurStyle {
            backgroundColor(colors.surface)
            blurRadius(24.dp)
            colorEffects(listOf(HazeColorEffect.tint(colors.surface.copy(alpha = 0.72f))))
            noiseFactor(0f)
        }
    }
    val material = when {
        !effectsEnabled -> Modifier.background(colors.surface)
        Build.VERSION.SDK_INT >= 33 -> Modifier.hazeGlass(input = input, style = glassStyle)
        else -> Modifier.hazeBlur(input = input, style = blurStyle)
    }
    Scaffold(
        containerColor = colors.canvas,
        topBar = { Box(Modifier.fillMaxWidth().then(material)) { topBar() } },
        bottomBar = { Box(Modifier.fillMaxWidth().then(material)) { bottomBar() } },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { padding ->
        CompositionLocalProvider(LocalVaultBottomInset provides padding.calculateBottomPadding()) {
            // Disposal removes the source layer immediately on route changes.
            // The entire source/effect graph leaves composition at vault lock.
            key(state.destination, state.openAlbum?.id, state.libraryScopeTitle) {
                Box(Modifier.fillMaxSize().then(if (effectsEnabled) Modifier.hazeSource(source) else Modifier)) {
                    content(
                        PaddingValues(
                            start = padding.calculateStartPadding(direction),
                            top = padding.calculateTopPadding(),
                            end = padding.calculateEndPadding(direction),
                        ),
                    )
                }
            }
        }
    }
}

/** Public Android contrast preferences; no hidden settings or extra permission. */
@Composable
private fun rememberHighContrastChrome(): Boolean {
    val context = LocalContext.current
    val uiMode = remember(context) { context.getSystemService(UiModeManager::class.java) }
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var contrast by remember(uiMode) {
        mutableStateOf(if (Build.VERSION.SDK_INT >= 34) uiMode?.contrast ?: 0f else 0f)
    }
    var highContrastText by remember(accessibility) {
        mutableStateOf(Build.VERSION.SDK_INT >= 36 && accessibility?.isHighContrastTextEnabled == true)
    }
    DisposableEffect(uiMode, accessibility, context) {
        val contrastListener = if (Build.VERSION.SDK_INT >= 34 && uiMode != null) {
            UiModeManager.ContrastChangeListener { contrast = it }.also {
                uiMode.addContrastChangeListener(context.mainExecutor, it)
                contrast = uiMode.contrast
            }
        } else null
        val textListener = if (Build.VERSION.SDK_INT >= 36 && accessibility != null) {
            AccessibilityManager.HighContrastTextStateChangeListener { highContrastText = it }.also {
                accessibility.addHighContrastTextStateChangeListener(context.mainExecutor, it)
                highContrastText = accessibility.isHighContrastTextEnabled
            }
        } else null
        onDispose {
            if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) {
                uiMode?.removeContrastChangeListener(contrastListener)
            }
            if (Build.VERSION.SDK_INT >= 36 && textListener != null) {
                accessibility?.removeHighContrastTextStateChangeListener(textListener)
            }
        }
    }
    return contrast > 0f || highContrastText
}
