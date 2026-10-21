package dev.po4yka.chur.android

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.vault.PrivateAlertDialog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A private dialog is its own window, and that window is excluded too, `ANDROID.md` §20.3. */
@RunWith(AndroidJUnit4::class)
class PrivateDialogCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aPrivateDialogWindowIsExcludedFromAutofillAndContentCapture() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val compose = ComposeView(activity).apply {
                    setContent {
                        PrivateAlertDialog(
                            onDismissRequest = {},
                            confirmButton = { TextButton(onClick = {}) { Text("Close") } },
                            title = { Text("Private dialog fixture") },
                        )
                    }
                }
                activity.setContentView(FrameLayout(activity).apply { addView(compose) })
            }
            var dialogRoot: View? = null
            val until = SystemClock.uptimeMillis() + 10_000
            while (dialogRoot == null && SystemClock.uptimeMillis() < until) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    dialogRoot = WindowInspector.getGlobalWindowViews()
                        .firstOrNull {
                            // A dialog window of this activity: TYPE_APPLICATION, not the base window.
                            it !== activity.window.decorView && it.isAttachedToWindow &&
                                (it.layoutParams as? WindowManager.LayoutParams)?.type ==
                                WindowManager.LayoutParams.TYPE_APPLICATION
                        }
                }
            }
            val root = requireNotNull(dialogRoot) { "the dialog window did not open" }
            instrumentation.runOnMainSync {
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, root.importantForAutofill)
                if (Build.VERSION.SDK_INT >= 30) {
                    assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, root.importantForContentCapture)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
