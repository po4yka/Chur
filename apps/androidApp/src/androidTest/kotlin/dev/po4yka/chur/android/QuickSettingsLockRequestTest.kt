package dev.po4yka.chur.android

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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

/** Real SystemUI acceptance with semantic button actions and no injected callback results. */
@RunWith(AndroidJUnit4::class)
class QuickSettingsLockRequestTest {
    @Test
    fun foreground_request_reports_the_actual_system_add_tile_result() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
        if (button != null) clickSystemPromptButton(button, resultReady, result)
        assertTrue("The real system request must complete; expected=$expected", resultReady.await(15, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(activity.getString(messageId), result.get())
        assertTrue("The callback must run on the activity main executor", callbackOnMain.get())
        assertEquals("One system request must deliver one result", 1, callbacks.get())
    }

    /** These exact labels and clickable ancestors were observed in the Android 37 SystemUI prompt. */
    private fun clickSystemPromptButton(label: String, resultReady: CountDownLatch, result: AtomicReference<String>) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000
        var diagnostic = "No active accessibility window"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val nodes = if (root == null) emptyList() else accessibilityTree(root)
            val systemNodes = nodes.filter { it.packageName?.toString() == "com.android.systemui" }
            diagnostic = "rootPackage=${root?.packageName}; SystemUI=" + systemNodes.mapNotNull { node ->
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
                    "${text.take(120)} [clickable=${node.isClickable}, enabled=${node.isEnabled}]"
                }
            }.distinct().take(24).joinToString("; ")
            if (systemNodes.any { it.text?.toString() == "Add tile to Quick Settings" }) {
                val text = systemNodes.firstOrNull { it.text?.toString() == label }
                var node = text
                repeat(10) {
                    val current = node ?: return@repeat
                    if (current.isEnabled && current.isClickable) {
                        assertTrue("SystemUI must accept the semantic button action: $label", current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        return
                    }
                    node = current.parent
                }
            }
            assertTrue("A system result arrived before the expected prompt: ${result.get()}", resultReady.count != 0L)
            SystemClock.sleep(50)
        }
        error("The real SystemUI prompt did not expose an enabled '$label' button; $diagnostic")
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
            if (tilePresent(component) == expected) return
            SystemClock.sleep(50)
        }
        assertEquals("The system tile list must reach the requested state", expected, tilePresent(component))
    }

    private fun tilePresent(component: ComponentName): Boolean {
        val names = setOf("custom(${component.flattenToString()})", "custom(${component.flattenToShortString()})")
        return shell("settings get secure sysui_qs_tiles").trim().split(',').any { it in names }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
