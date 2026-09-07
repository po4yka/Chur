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
import dev.po4yka.chur.sync.SyncCoordinator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The Android background schedule, `SYNC_PROTOCOL_V1.md` §7.
 *
 * One periodic WorkManager job asks the engine for a cycle while the process
 * is alive. The worker is deliberately thin about what can happen around it:
 * a process the system started for this job has no composition root yet, and
 * the one-runtime rule of `docs/interop/FFI_CONTRACT.md` §14 forbids opening a
 * second one beside `MainActivity`'s, so a cycle without a bound engine is a
 * quiet no rather than a second runtime. `MainActivity` binds the engine and
 * enqueues the work; from then on every periodic run, foregrounded or not,
 * pulls and stages while the vault happens to be locked and applies nothing
 * until the user unlocks.
 */
object ChurSync {
    /**
     * The engine the activity bound, or `null` before it did.
     *
     * It is process state rather than activity state because the worker
     * outlives no process but can outlive the activity.
     */
    var coordinator: SyncCoordinator? = null

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
 * The cycle carries the bounded backoff of §10 inside it, so the worker's own
 * result adds no second backoff: a finished cycle is a finished run, and the
 * next periodic schedule is the retry the user experiences.
 */
internal class ChurSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val coordinator = ChurSync.coordinator ?: return Result.success()
        try {
            coordinator.syncNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The engine records a bounded status for its own failures; what
            // reaches here is a schedule-level accident, and the log is where
            // a report of one starts. The next run is the recovery.
            Log.w("ChurSync", "the background sync cycle failed", failure)
        }
        return Result.success()
    }
}
