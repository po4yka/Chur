package dev.po4yka.chur.app

import androidx.compose.runtime.Composable
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly

/** iOS has no sensitive mark: the clip stays on this device and expires after two minutes. */
@Composable
internal actual fun rememberSensitiveCopy(): (label: String, text: String) -> Unit = { _, text ->
    UIPasteboard.generalPasteboard.setItems(
        listOf(mapOf("public.utf8-plain-text" to text)),
        mapOf<Any?, Any?>(
            UIPasteboardOptionLocalOnly to true,
            UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(120.0),
        ),
    )
}
