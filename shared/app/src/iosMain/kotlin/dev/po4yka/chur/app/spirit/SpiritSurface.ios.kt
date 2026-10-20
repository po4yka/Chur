@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.spirit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UIView
import platform.UIKit.UIViewContentModeScaleAspectFit

/** Host-owned Metal renderer. Only public mascot state crosses this boundary. */
public interface IosSpiritRenderer {
    public val view: UIView
    public fun update(clip: String, clipSerial: Int, motionSerial: Int, reducedMotion: Boolean, dragX: Float, dragY: Float)
    public fun dispose()
}

/** Install once on the main thread, before creating the Compose controller. */
public object IosSpiritRendererHost {
    public var factory: (() -> IosSpiritRenderer?)? = null
}

@Composable
internal actual fun SpiritSurface(state: SpiritRenderState, modifier: Modifier) {
    // Allocate only when UIKit actually creates a view, never in remember's
    // calculation (an abandoned composition must not leak a Metal engine).
    // Per-view ownership also makes delayed onRelease safe across recreation.
    val renderers = remember { mutableMapOf<UIView, IosSpiritRenderer>() }
    UIKitView(
        modifier = modifier,
        factory = {
            val renderer = IosSpiritRendererHost.factory?.invoke()
            (renderer?.view ?: staticSpiritView()).also {
                if (renderer != null) renderers[it] = renderer
                it.userInteractionEnabled = false
                it.isAccessibilityElement = false
                it.accessibilityElementsHidden = true
            }
        },
        update = { view ->
            renderers[view]?.update(state.clip, state.clipSerial, state.motionSerial, state.reducedMotion, state.dragX, state.dragY)
        },
        onRelease = { view -> renderers.remove(view)?.dispose() },
        properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
    )
}

private fun staticSpiritView(): UIView {
    val image = UIImage.imageNamed("chur_interactive_fallback")
    return if (image != null) {
        UIImageView(image = image).apply { contentMode = UIViewContentModeScaleAspectFit }
    } else {
        UILabel(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
            text = "Chur"
            textAlignment = NSTextAlignmentCenter
            textColor = UIColor.secondaryLabelColor
        }
    }
}
