package dev.po4yka.chur.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSensitiveCopy(): (label: String, text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { label, text ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(sensitiveClipData(label, text))
        }
    }
}

/** A plain-text clip that carries the sensitive flag, which API 33 reads and earlier OEM previews honor. */
fun sensitiveClipData(label: String, text: String): ClipData = ClipData.newPlainText(label, text).apply {
    // ClipDescription.EXTRA_IS_SENSITIVE is API 33; the key is the same string before it.
    description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
}

private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
