package dev.po4yka.chur.app

import android.app.Activity
import android.view.WindowManager

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
 */
class AndroidPrivacyCover(private val activity: Activity) : PrivacyCover {
    override fun setEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            if (enabled) {
                activity.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
