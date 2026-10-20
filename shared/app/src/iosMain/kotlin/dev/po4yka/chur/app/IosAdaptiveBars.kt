@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import platform.UIKit.UIBarButtonItem

/** SDK-owned bar presentation; Kotlin/Native's UIKit bindings lag the host SDK. */
object IosAdaptiveBars {
    var configureCustomItem: ((UIBarButtonItem) -> Unit)? = null
}
