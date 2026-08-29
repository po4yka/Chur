package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformSyncHttpClient(): HttpClient = HttpClient(OkHttp)
