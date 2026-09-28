package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

internal actual fun platformSyncHttpClient(): HttpClient = HttpClient(OkHttp)

internal actual suspend fun resolveHost(host: String): List<ByteArray> = withContext(Dispatchers.IO) {
    try {
        InetAddress.getAllByName(host).map { it.address }
    } catch (_: Exception) {
        emptyList()
    }
}
