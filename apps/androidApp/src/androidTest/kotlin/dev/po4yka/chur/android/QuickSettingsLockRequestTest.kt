package dev.po4yka.chur.android

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SystemUI acceptance with accessible button lookup, real touches, and no injected callback results. */
@RunWith(AndroidJUnit4::class)
class QuickSettingsLockRequestTest {
    @Test
    fun foreground_request_reports_the_actual_system_add_tile_result() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        dismissPendingTilePrompt()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val resumed = CountDownLatch(1)
        val component = ComponentName(activity, ChurLockTileService::class.java)
        val originallyPresent = Build.VERSION.SDK_INT >= 33 && tilePresent(component)
        val helper = QuickSettingsLock(activity)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                activity.lifecycle.addObserver(observer)
                if (activity.lifecycle.currentState == Lifecycle.State.RESUMED) resumed.countDown()
            }
            assertTrue("MainActivity must be resumed before the user-started request", resumed.await(10, TimeUnit.SECONDS))
            if (Build.VERSION.SDK_INT < 33) {
                request(activity, helper, "manual", R.string.quick_settings_lock_manual)
            } else {
                setTilePresent(component, false)
                request(activity, helper, "added", R.string.quick_settings_lock_added, button = "Add tile")
                awaitTilePresence(component, true)
                request(activity, helper, "already", R.string.quick_settings_lock_already_added)
                setTilePresent(component, false)
                request(activity, helper, "declined", R.string.quick_settings_lock_not_added, button = "Don't add tile")
                awaitTilePresence(component, false)
            }
        } finally {
            dismissPendingTilePrompt()
            instrumentation.runOnMainSync {
                activity.lifecycle.removeObserver(observer)
                activity.finish()
            }
            instrumentation.waitForIdleSync()
            if (Build.VERSION.SDK_INT >= 33) setTilePresent(component, originallyPresent)
        }
    }

    private fun request(activity: MainActivity, helper: QuickSettingsLock, expected: String, messageId: Int, button: String? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resultReady = CountDownLatch(1)
        val result = AtomicReference<String>()
        val callbacks = AtomicInteger()
        val callbackOnMain = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            val signal = "CHUR_TILE_REQUEST_READY expected=$expected"
            Log.i("ChurTileAcceptance", signal)
            instrumentation.sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$signal\n") })
            helper.request { message ->
                result.set(message)
                callbacks.incrementAndGet()
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                Log.i("ChurTileAcceptance", "CHUR_TILE_REQUEST_RESULT expected=$expected message=$message")
                resultReady.countDown()
            }
        }
        val inputDiagnostic = if (button != null) clickSystemPromptButton(button, resultReady, result) else "No dialog expected"
        val completed = resultReady.await(15, TimeUnit.SECONDS)
        val diagnostic = if (completed) "" else {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val nodes = root?.let(::accessibilityTree).orEmpty().filter { it.packageName?.toString() == "com.android.systemui" }
            "; dialogRemains=${isOwnTilePrompt(nodes)}, " +
                "tilePresent=${tilePresent(ComponentName(activity, ChurLockTileService::class.java))}, " +
                "runtimeTilePresent=${runtimeTilePresent(ComponentName(activity, ChurLockTileService::class.java))}, " +
                "callbacks=${callbacks.get()}, rootPackage=${root?.packageName}"
        }
        assertTrue("The real system request must complete; expected=$expected$diagnostic; $inputDiagnostic", completed)
        instrumentation.waitForIdleSync()
        assertEquals(inputDiagnostic, activity.getString(messageId), result.get())
        assertTrue("The callback must run on the activity main executor", callbackOnMain.get())
        assertEquals("One system request must deliver one result", 1, callbacks.get())
    }

    /** SDKs use different titles and decline labels for the same real SystemUI request. */
    private fun clickSystemPromptButton(label: String, resultReady: CountDownLatch? = null, result: AtomicReference<String>? = null): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000
        var diagnostic = "No active accessibility window"
        var idle = false
        var stableBounds: Rect? = null
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val nodes = if (root == null) emptyList() else accessibilityTree(root)
            val systemNodes = nodes.filter { it.packageName?.toString() == "com.android.systemui" }
            diagnostic = "rootPackage=${root?.packageName}; SystemUI=" + systemNodes.mapNotNull { node ->
                (if (node.isPassword || node.isEditable) "<REDACTED>" else node.text?.toString())?.takeIf { it.isNotBlank() }?.let { text ->
                    "${text.take(120)} [clickable=${node.isClickable}, enabled=${node.isEnabled}]"
                }
            }.distinct().take(24).joinToString("; ")
            if (isOwnTilePrompt(systemNodes)) {
                if (!idle) {
                    // Accessible controls can arrive before the dialog's entrance animation ends.
                    automation.waitForIdle(500, 5_000)
                    idle = true
                    continue // Use new nodes and screen coordinates after the transition.
                }
                val labels = if (label == "Don't add tile") DECLINE_LABELS else setOf(label)
                val text = systemNodes.firstOrNull { it.text?.toString() in labels }
                var node = text
                var depth = 0
                while (node != null && depth++ < 10) {
                    val current = node
                    if (current.isEnabled && current.isClickable && current.isVisibleToUser) {
                        val bounds = Rect().also(current::getBoundsInScreen)
                        val window = root?.window
                        val windowBounds = Rect().also { window?.getBoundsInScreen(it) }
                        val ime = automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        val ready = window?.isFocused == true && window.isActive &&
                            !ime && !bounds.isEmpty && windowBounds.contains(bounds)
                        diagnostic += "; focused=${window?.isFocused}, active=${window?.isActive}, " +
                            "window=$windowBounds, button=$bounds, ime=$ime"
                        if (!ready || bounds != stableBounds) {
                            stableBounds = if (ready) bounds else null
                            stableSince = SystemClock.uptimeMillis()
                        } else if (SystemClock.uptimeMillis() - stableSince >= 200) {
                            touchSystemButton(current)
                            return diagnostic
                        }
                        break
                    }
                    node = current.parent
                }
            }
            if (resultReady != null) {
                assertTrue("A system result arrived before the expected prompt: ${result?.get()}", resultReady.count != 0L)
            }
            SystemClock.sleep(50)
        }
        error("The real SystemUI prompt did not expose an enabled '$label' button; $diagnostic")
    }

    /** Framework SystemUI buttons can accept ACTION_CLICK without invoking their listener. */
    private fun touchSystemButton(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("The SystemUI button must have visible bounds", !bounds.isEmpty)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("The real touch must reach SystemUI", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
    }

    /** A failed run can leave this system-owned dialog alive after its activity finishes. */
    private fun dismissPendingTilePrompt() {
        fun showing(): Boolean {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
            return isOwnTilePrompt(accessibilityTree(root).filter { it.packageName?.toString() == "com.android.systemui" })
        }
        if (!showing()) return
        clickSystemPromptButton("Don't add tile")
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline && showing()) SystemClock.sleep(50)
        assertTrue("The pending Chur tile request must close", !showing())
    }

    private fun isOwnTilePrompt(nodes: List<AccessibilityNodeInfo>): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tileLabel = context.getString(R.string.quick_settings_lock_label)
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        return nodes.any {
            it.text?.toString() == tileLabel || it.contentDescription?.toString() == tileLabel ||
                it.text?.toString()?.startsWith("$appLabel wants to add") == true
        } &&
            nodes.any { it.text?.toString() == "Add tile" } &&
            nodes.any { it.text?.toString() in DECLINE_LABELS }
    }

    /** Compose virtual nodes are exposed by child traversal, not always by framework text search. */
    private fun accessibilityTree(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 32 || nodes.size >= 512) return
            nodes += node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return nodes
    }

    private fun setTilePresent(component: ComponentName, present: Boolean) {
        shell("cmd statusbar ${if (present) "add-tile" else "remove-tile"} ${component.flattenToShortString()}")
        awaitTilePresence(component, present)
    }

    private fun awaitTilePresence(component: ComponentName, expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            // The added callback can precede QSTileHost's settings observer.
            // Its runtime inventory decides whether the next request is "already added".
            if (tilePresent(component) == expected && runtimeTilePresent(component) == expected) return
            SystemClock.sleep(50)
        }
        assertEquals("The system tile list must reach the requested state", expected, tilePresent(component))
        assertEquals("The SystemUI runtime tile inventory must reach the requested state", expected, runtimeTilePresent(component))
    }

    private fun tilePresent(component: ComponentName): Boolean {
        return shell("settings get secure sysui_qs_tiles").trim().split(',').any { it in tileSpecs(component) }
    }

    private fun runtimeTilePresent(component: ComponentName): Boolean {
        val dump = shell("dumpsys activity service com.android.systemui/.SystemUIService QSTileHost")
        check("QSTileHost" in dump) { "SystemUI must expose the actual tile host inventory" }
        return tileSpecs(component).any { "[spec=$it," in dump }
    }

    private fun tileSpecs(component: ComponentName): Set<String> =
        setOf("custom(${component.flattenToString()})", "custom(${component.flattenToShortString()})")

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private companion object {
        val DECLINE_LABELS = setOf("Don't add tile", "Do not add tile")
    }
}
