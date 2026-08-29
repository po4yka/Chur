package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun platformSyncHttpClient(): HttpClient = HttpClient(Darwin)
