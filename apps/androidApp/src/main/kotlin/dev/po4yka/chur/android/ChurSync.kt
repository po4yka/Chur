package dev.po4yka.chur.android

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.po4yka.chur.app.GateResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The Android background schedule, `SYNC_PROTOCOL_V1.md` §7.
 *
 * One periodic WorkManager job asks the process-scoped [ChurHost] for a cycle.
 * A process started for the job creates that same host before opening the
 * runtime; the vault stays locked and inbound records are staged until unlock.
 */
object ChurSync {
    /** Schedules the periodic pull, keeping an existing schedule over it. */
    fun enqueue(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<ChurSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private const val WORK_NAME = "chur-sync"
    private const val PERIOD_HOURS = 6L
}

/**
 * One work unit: one engine cycle, `SyncCoordinator.syncNow`.
 *
 * The cycle carries the bounded transport backoff of §10 inside it. WorkManager
 * retries only when the host cannot start or an unexpected exception escapes.
 */
internal class ChurSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try {
            if (runGate(applicationContext) !is GateResult.Compatible) return Result.failure()
            val host = ChurHost.of(applicationContext)
            host.controller.vault.start()
            host.sync.syncNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Engine transport failures have their own bounded backoff. An
            // exception here means the scheduled work never completed.
            Log.w("ChurSync", "the background sync cycle failed", failure)
            return Result.retry()
        }
        return Result.success()
    }
}
