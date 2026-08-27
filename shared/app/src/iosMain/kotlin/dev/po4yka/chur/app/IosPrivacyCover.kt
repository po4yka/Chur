@file:OptIn(ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIVisualEffectView
import platform.UIKit.UIWindow

/**
 * The iOS cover: a blur over the key window.
 *
 * iOS has no `FLAG_SECURE`. The system takes its snapshot after
 * `applicationWillResignActive` and before the application is suspended, so the
 * cover is added there and removed on `applicationDidBecomeActive`; a cover
 * added later is added after the picture was taken.
 *
 * The blur is a system material rather than an opaque rectangle because the
 * switcher entry should look like an application that is being careful, not
 * like one that crashed, which is the same reasoning `DISCREET_MODE.md` applies
 * to the public shell.
 */
class IosPrivacyCover : PrivacyCover {
    private var cover: UIView? = null

    override fun setEnabled(enabled: Boolean) {
        if (enabled) attach() else detach()
    }

    /** Adds the cover, which the scene delegate calls on resigning active. */
    fun attach() {
        if (cover != null) return
        val window = keyWindow() ?: return
        val effect = UIVisualEffectView(
            effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleSystemMaterial),
        )
        effect.setFrame(window.bounds)
        // The window can resize while the application is inactive, on a split
        // view or a rotation, and a cover that did not follow it would leave an
        // uncovered strip in the snapshot.
        effect.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight,
        )
        window.addSubview(effect)
        cover = effect
    }

    /** Removes the cover, which the scene delegate calls on becoming active. */
    fun detach() {
        cover?.removeFromSuperview()
        cover = null
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.windows
            .filterIsInstance<UIWindow>()
            .firstOrNull { it.isKeyWindow() }
}
