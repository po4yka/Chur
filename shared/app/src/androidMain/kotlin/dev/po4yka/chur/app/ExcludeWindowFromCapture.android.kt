package dev.po4yka.chur.app

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun ExcludeWindowFromCapture() {
    val view = LocalView.current
    DisposableEffect(view) {
        excludeFromCapture(view.rootView)
        onDispose { }
    }
}

/** Marks [root], the root view of one window, and all its descendants. */
internal fun excludeFromCapture(root: View) {
    root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 30) {
        root.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
}
