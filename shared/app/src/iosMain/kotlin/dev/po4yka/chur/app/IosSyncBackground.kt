@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate

/**
 * The iOS half of the background schedule, `SYNC_PROTOCOL_V1.md` §7.
 *
 * While locked the vault may still receive signed opaque bytes, so the Apple
 * host registers one `BGAppRefreshTask` and hands the launch to the shared
 * controller. The task runs [ChurController.runBackgroundSync], which is safe
 * in either lock state: staging is permitted locked, and the records it moves
 * apply at the next unlock.
 *
 * The Xcode project completes the platform half, which Kotlin cannot: the
 * identifier in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, the
 * `fetch` background mode, and two calls — [register] once at launch, and
 * [schedule] each time the scene resigns active. `apps/iosApp/README.md` is
 * the checklist.
 */
public object IosSyncBackground {
    /** The identifier the Info.plist entry and the registration must share. */
    public const val TASK_IDENTIFIER: String = "dev.po4yka.chur.sync.refresh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registers the one refresh task the system can launch this host for.
     *
     * Call it before the first `submit`, as `application:didFinishLaunching…`
     * runs, because the registration is refused afterwards. The handler marks
     * the task complete in both outcomes — the system stops rescheduling a
     * task whose completion it never sees.
     */
    public fun register(controller: ChurController) {
        BGTaskScheduler.Companion.sharedScheduler().registerForTaskWithIdentifier(
            identifier = TASK_IDENTIFIER,
            usingQueue = null,
        ) { task: BGTask? ->
            if (task == null) return@registerForTaskWithIdentifier
            var completed = false

            fun complete(success: Boolean) {
                if (!completed) {
                    completed = true
                    task.setTaskCompletedWithSuccess(success)
                }
            }
            val run =
                scope.launch {
                    try {
                        controller.runBackgroundSync()
                        complete(success = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        complete(success = false)
                    }
                }
            task.expirationHandler = {
                run.cancel()
                complete(success = false)
            }
        }
    }

    /** Asks the system for one refresh run at its own convenience. */
    public fun schedule(earliestBeginDate: NSDate? = null) {
        val request = BGAppRefreshTaskRequest(identifier = TASK_IDENTIFIER)
        request.earliestBeginDate = earliestBeginDate
        BGTaskScheduler.Companion.sharedScheduler().submitTaskRequest(
            taskRequest = request,
            error = null,
        )
    }
}
