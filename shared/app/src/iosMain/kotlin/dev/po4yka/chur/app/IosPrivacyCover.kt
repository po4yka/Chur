@file:OptIn(ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.app.theme.ChurDarkColors
import dev.po4yka.chur.app.theme.ChurLightColors
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow

/**
 * The iOS cover: an opaque canvas over the key window.
 *
 * iOS has no `FLAG_SECURE`. The system takes its snapshot after
 * `sceneWillResignActive` and before the scene is suspended. So the scene
 * delegate adds the cover there when [ChurController.privacyCoverNeeded] says
 * the screen is private, and removes it on `sceneDidBecomeActive`. A cover
 * added later is added after the picture was taken. The delegate asks again on
 * `sceneDidEnterBackground`, because UIKit takes the persisted switcher picture
 * after that call returns, and a session can open between the two calls: a
 * vault creation whose key derivation ends after the scene resigned active.
 *
 * [setEnabled] does nothing on this platform. Android's flag cannot be seen
 * while the window is in front. This cover is a view, and a view added while
 * the scene is active hides the session and takes its touches. The controller
 * turns the cover on as it enters the vault, so a password unlock left the
 * vault covered until the next trip to the background. `IOS.md` §22.2 allows
 * a cover "while inactive/backgrounded", and that is the only time this view
 * exists.
 *
 * The cover is opaque, in the `privacy-cover` colour of `DESIGN.md`, which is
 * the canvas of the current appearance. A blur of a grid or of a photo keeps
 * its shapes and colours, and `IOS.md` §22.1 lets the snapshot hold neither
 * thumbnails nor viewer content. `DESIGN.md` §4.4 also wants the cover
 * visually complete. The cover has no mark, because `PrivateBoundaryMark` is
 * for private surfaces, and anybody who holds the device can see a switcher
 * entry.
 */
class IosPrivacyCover : PrivacyCover {
    private var cover: UIView? = null

    override fun setEnabled(enabled: Boolean) {
        // Intentionally empty: the scene delegate decides, see above.
    }

    /**
     * Adds the cover, which the scene delegate calls on resigning active and
     * on entering the background; a second call does nothing.
     */
    fun attach() {
        if (cover != null) return
        val window = keyWindow() ?: return
        val dark = window.traitCollection.userInterfaceStyle ==
            UIUserInterfaceStyle.UIUserInterfaceStyleDark
        val canvas = if (dark) ChurDarkColors.canvas else ChurLightColors.canvas
        val view = UIView()
        view.setBackgroundColor(
            UIColor(
                red = canvas.red.toDouble(),
                green = canvas.green.toDouble(),
                blue = canvas.blue.toDouble(),
                alpha = 1.0,
            ),
        )
        view.setFrame(window.bounds)
        // The window can resize while the application is inactive, on a split
        // view or a rotation, and a cover that did not follow it would leave an
        // uncovered strip in the snapshot.
        view.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight,
        )
        window.addSubview(view)
        cover = view
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
