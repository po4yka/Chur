package dev.po4yka.chur.android

import android.content.Context
import androidx.security.state.SecurityPatchState
import androidx.security.state.SecurityPatchState.Companion.COMPONENT_SYSTEM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The system security patch levels that the Settings line of `ANDROID.md`
 * §25.1 shows.
 *
 * [installed] is the level the system declares. [ready] is a newer level that
 * an on-device update client has staged, or `null` when none has.
 */
internal data class SystemPatchLevels(val installed: String, val ready: String?)

/** The outcome of the one user-started request that ADR-0059 permits. */
internal sealed interface PublishedCheck {
    data object NotChecked : PublishedCheck

    data object Checking : PublishedCheck

    data object Failed : PublishedCheck

    /** [latest] is the newest level in the bulletin; [covered] says the device has it. */
    data class Done(val latest: String, val covered: Boolean) : PublishedCheck
}

/**
 * Reads the installed level and asks the trusted update clients over IPC.
 *
 * It reaches no network. A device that declares no level, or whose value the
 * library cannot parse, gets `null`, and the row stays hidden.
 */
internal suspend fun readSystemPatchLevels(context: Context): SystemPatchLevels? =
    try {
        val state = SecurityPatchState(context)
        val installed = state.getDeviceSecurityPatchLevel(COMPONENT_SYSTEM)
        val available = state.fetchAvailableSecurityPatchLevel(COMPONENT_SYSTEM)
        SystemPatchLevels(installed.toString(), available.takeIf { it > installed }?.toString())
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Fetches the public vulnerability report for this Android version and compares
 * it with the device.
 *
 * ADR-0059 permits this request only after the user taps the row. It is a GET
 * to the one fixed HTTPS endpoint the library names. It sends no cookie and no
 * vault value, it follows no redirect, and it reads at most
 * [MAX_REPORT_BYTES]. The report stays in memory and is parsed as untrusted
 * input.
 */
internal suspend fun checkPublishedSystemPatchLevel(context: Context): PublishedCheck =
    withContext(Dispatchers.IO) {
        try {
            val url = URL(SecurityPatchState.createVulnerabilityReportUrl().toString())
            val connection = url.openConnection() as HttpsURLConnection
            val report =
                try {
                    connection.connectTimeout = REPORT_TIMEOUT_MS
                    connection.readTimeout = REPORT_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext PublishedCheck.Failed
                    connection.inputStream.use { it.readAtMost(MAX_REPORT_BYTES) }
                } finally {
                    connection.disconnect()
                } ?: return@withContext PublishedCheck.Failed
            val state = SecurityPatchState(context)
            state.loadVulnerabilityReport(report.decodeToString())
            // With the report loaded, the device level includes the supplemental
            // patches of the bulletin's risk-based releases, so a device whose
            // declared date is older can still hold every published fix.
            val device = state.getDeviceSecurityPatchLevel(COMPONENT_SYSTEM)
            val latest =
                state.getPublishedSecurityPatchLevel(COMPONENT_SYSTEM).maxOrNull()
                    ?: return@withContext PublishedCheck.Failed
            PublishedCheck.Done(latest.toString(), covered = device >= latest)
        } catch (_: IOException) {
            PublishedCheck.Failed
        } catch (_: IllegalStateException) {
            PublishedCheck.Failed
        } catch (_: IllegalArgumentException) {
            PublishedCheck.Failed
        }
    }

/** The Settings line, or `null` when the device declares no level. */
internal fun systemSecurityLine(levels: SystemPatchLevels?, check: PublishedCheck): String? {
    levels ?: return null
    val installed =
        levels.ready?.let { "Installed: ${levels.installed}. Update to $it is ready in system settings." }
            ?: "Installed: ${levels.installed}."
    val published =
        when (check) {
            PublishedCheck.NotChecked -> "Tap to compare with the Android Security Bulletin on osv.dev."
            PublishedCheck.Checking -> "Checking osv.dev…"
            PublishedCheck.Failed -> "Couldn't reach osv.dev. Tap to try again."
            is PublishedCheck.Done ->
                if (check.covered) "This has every fix published up to ${check.latest}." else "Latest published: ${check.latest}."
        }
    return "$installed $published"
}

/** Returns the stream's bytes, or `null` when it holds more than [limit]. */
internal fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(buffer, 0, read)
    }
}

/** The report for one SDK level was 72,858 bytes in September 2026. */
private const val MAX_REPORT_BYTES = 1024 * 1024
private const val REPORT_TIMEOUT_MS = 10_000
