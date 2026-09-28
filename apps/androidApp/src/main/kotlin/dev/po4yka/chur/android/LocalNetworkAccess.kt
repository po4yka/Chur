package dev.po4yka.chur.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.sync.isLocalSyncEndpoint
import kotlinx.coroutines.launch
import java.net.InetAddress

/** Android 17, the first release that enforces local network protection. */
private const val LOCAL_NETWORK_PROTECTION = 37

/**
 * What Settings and a refusal say while a local sync server is out of reach.
 *
 * It names where to allow it, because inside the vault "Settings" would read
 * as the screen the line is on.
 */
internal const val LOCAL_NETWORK_OFF: String =
    "Local network access is off. To sync with a server on your local network, " +
        "allow Nearby devices for this app in your phone's settings."

/**
 * Whether [serverUrl] is a local server that this app may not reach yet,
 * `ANDROID.md` §24.
 *
 * From Android 17, a connection to a local address needs
 * `ACCESS_LOCAL_NETWORK`, and without it the connection usually just times
 * out. Android guards traffic on a broadcast-capable link such as Wi-Fi or
 * Ethernet and never traffic through a VPN, so a server the user reaches
 * through their VPN, such as a subnet route, is never asked for. The address
 * ranges are [dev.po4yka.chur.sync.isLocalEndpoint]'s, and an IPv6 address on
 * a directly connected route of the active network counts too. The version,
 * the grant, and the network are checked first, so the name is resolved only
 * when the answer depends on it. A VPN that routes only some addresses
 * counts as a VPN for every server here, so a split tunnel that leaves the
 * server on the local link is not asked for; the VPN's own routes would tell.
 */
internal suspend fun localNetworkBlocked(context: Context, serverUrl: String): Boolean {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_PROTECTION) return false
    if (context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
        return false
    }
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    val transports = connectivity.getNetworkCapabilities(network) ?: return false
    if (transports.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
    val routes = connectivity.getLinkProperties(network)?.routes.orEmpty()
    return isLocalSyncEndpoint(serverUrl) { address ->
        address.size == 16 && onLink(routes, InetAddress.getByAddress(address))
    }
}

/**
 * Whether [address] is in a prefix that one of [routes] reaches directly.
 *
 * Only a route with no gateway and a real prefix counts. A cellular
 * link can report its default route with `::` as the gateway, and such a
 * `::/0` route has no gateway yet matches every address, so a public server
 * would count as local, against `ANDROID.md` §24.
 */
internal fun onLink(routes: List<RouteInfo>, address: InetAddress): Boolean =
    routes.any { !it.hasGateway() && it.destination.prefixLength > 0 && it.matches(address) }

/**
 * Asks for local network access before a sync action that needs it,
 * `ANDROID.md` §24.
 *
 * The function this returns runs `then` at once for a server this app can
 * reach. For a local server without the grant, it asks first, in context: when
 * the user connects or syncs, never at launch, and never from the background
 * worker, which has no screen to ask on. A grant runs `then`. A refusal runs
 * nothing and says how to allow access, with a way to the app's system
 * settings.
 *
 * The system request is a dialog that only pauses the activity, so it runs
 * inside a [ChurController.beginPrompt] bracket: the background lock of
 * `MainActivity.onPause` does not close the vault under it, and a user who
 * leaves the app meanwhile is locked out at once when the activity stops,
 * `ANDROID.md` §19.3. An answer to a prompt that ended so runs nothing.
 * `then` can hold the bootstrap secret, so it lives in plain state until the
 * answer and never reaches saved state. A lock disposes of the route, and of
 * `then` with it.
 */
@Composable
internal fun rememberLocalNetworkAccess(controller: ChurController): (serverUrl: String, then: () -> Unit) -> Unit {
    val context = LocalContext.current
    // The notice keeps its action for the life of the process, so the action
    // holds the application and not an activity a recreation destroys.
    val application = context.applicationContext
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var prompt by remember { mutableStateOf<Long?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val current = prompt?.let(controller::endPrompt) == true
        prompt = null
        val then = pending
        pending = null
        if (!current) return@rememberLauncherForActivityResult
        if (granted) {
            then?.invoke()
        } else {
            controller.report(LOCAL_NETWORK_OFF, "Open settings" to { openAppSettings(application) })
        }
    }
    return { serverUrl, then ->
        scope.launch {
            // The version check repeats the one inside for lint, which cannot
            // see through the call.
            if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PROTECTION && localNetworkBlocked(context, serverUrl)) {
                pending = then
                prompt = controller.beginPrompt()
                request.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else {
                then()
            }
        }
    }
}

/** Opens this app's system settings from [context], the application, so in a new task. */
private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
