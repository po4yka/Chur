package dev.po4yka.chur.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.po4yka.chur.app.ChurController

/** Request local peer access only when the user starts a direct foreground connection. */
@Composable
internal fun rememberLocalNetworkAccess(controller: ChurController): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        controller.endHostActivity()
        if (granted) action?.invoke()
        else controller.report("Allow local network access in app Settings to connect directly to your other device.")
    }
    DisposableEffect(controller) {
        onDispose { if (pending != null) { pending = null; controller.endHostActivity() } }
    }
    return { then ->
        if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) then()
        else if (pending == null) {
            pending = then
            controller.beginHostActivity()
            try { launcher.launch(permission) } catch (_: Exception) {
                pending = null
                controller.endHostActivity()
                controller.report("The local network permission prompt could not be opened.")
            }
        }
    }
}
