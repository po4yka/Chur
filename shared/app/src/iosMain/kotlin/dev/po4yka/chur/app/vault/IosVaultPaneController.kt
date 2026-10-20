@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.separatorColor

/** Two native navigation owners share one route and move its existing scene leases. */
internal class IosVaultPaneController(
    private val library: UIViewController,
    private val viewer: UIViewController,
) : UIViewController(nibName = null, bundle = null) {
    private var layout = AdaptiveVaultLayout(AdaptiveRegion(0.0, 0.0, 0.0, 0.0), null)

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.separatorColor
        listOf(library, viewer).forEach { child ->
            addChildViewController(child)
            view.addSubview(child.view)
            child.didMoveToParentViewController(this)
        }
        placeChildren()
    }

    fun apply(layout: AdaptiveVaultLayout) {
        this.layout = layout
        placeChildren()
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        placeChildren()
    }

    private fun placeChildren() {
        if (!isViewLoaded()) return
        library.view.setFrame(layout.library.frame())
        viewer.view.hidden = layout.viewer == null
        viewer.view.accessibilityElementsHidden = layout.viewer == null
        layout.viewer?.let { viewer.view.setFrame(it.frame()) }
    }
}

private fun AdaptiveRegion.frame() = CGRectMake(x, y, width, height)
