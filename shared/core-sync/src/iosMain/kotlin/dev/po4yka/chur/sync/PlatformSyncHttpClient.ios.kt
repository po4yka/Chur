@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

internal actual fun platformSyncHttpClient(): HttpClient = HttpClient(Darwin)

internal actual suspend fun resolveHost(host: String): List<ByteArray> = withContext(Dispatchers.IO) {
    memScoped {
        val first = alloc<CPointerVar<addrinfo>>()
        if (getaddrinfo(host, null, null, first.ptr) != 0) return@memScoped emptyList()
        try {
            generateSequence(first.value) { it.pointed.ai_next }.mapNotNull { entry ->
                val address = entry.pointed.ai_addr ?: return@mapNotNull null
                when (entry.pointed.ai_family) {
                    AF_INET -> address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.readBytes(4)
                    AF_INET6 -> address.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr.readBytes(16)
                    else -> null
                }
            }.toList()
        } finally {
            freeaddrinfo(first.value)
        }
    }
}
