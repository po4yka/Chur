package dev.po4yka.chur.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.contentcapture.ContentCaptureManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * The Android cover: `FLAG_SECURE`.
 *
 * The flag makes the compositor refuse to include the window in a screenshot,
 * in a screen recording, and in the recents thumbnail, which is the whole of
 * what `PLAINTEXT_LIFECYCLE.md` §1 asks for on this platform. It is set on the
 * window rather than per view because the recents capture is of the window.
 *
 * It also blocks the user's own screenshot. That is a deliberate cost: §1 puts
 * the switcher snapshot in the forbidden column without an exception, and a
 * setting to relax it would be a setting that turns the protection off.
 *
 * The activity is read through a function rather than held. The window is
 * activity state and the session it covers is process state:
 * `docs/interop/FFI_CONTRACT.md` §8.1 says "there is no per-scene vault
 * state", so the platform recreates the window under one session. A cover that
 * held the first activity would set the flag on a destroyed window and keep
 * every destroyed activity alive. No activity means no window to cover, and
 * the next one sets the flag from the session state as it starts.
 *
 * `FLAG_SECURE` does not stop an autofill or content-capture service from reading
 * the view tree, and `ANDROID.md` §20.3 forbids both on private surfaces. The same
 * switch therefore excludes the whole window from autofill and content capture,
 * and cancels an autofill session that is already open.
 */
class AndroidPrivacyCover(private val activity: () -> Activity?) : PrivacyCover {
    override fun setEnabled(enabled: Boolean) {
        val current = activity() ?: return
        current.runOnUiThread {
            // Async native lock completion may request a public window after
            // onPause covered it. Keep recents protected until this window is
            // resumed; MainActivity's RESUMED collector reapplies public state.
            val resumed = (current as? LifecycleOwner)?.lifecycle?.currentState == Lifecycle.State.RESUMED
            if (enabled || !resumed) {
                current.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
                exclude(current, true)
            } else {
                current.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                exclude(current, false)
            }
        }
    }

    private fun exclude(activity: Activity, private: Boolean) {
        val decor = activity.window.decorView
        decor.importantForAutofill = if (private) {
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        if (private) activity.getSystemService(AutofillManager::class.java)?.cancel()
        if (Build.VERSION.SDK_INT >= 30) {
            decor.importantForContentCapture = if (private) {
                View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_CONTENT_CAPTURE_AUTO
            }
        } else {
            // API 29 has no per-view switch; turn capture off for the activity instead.
            activity.getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(!private)
        }
    }
}
