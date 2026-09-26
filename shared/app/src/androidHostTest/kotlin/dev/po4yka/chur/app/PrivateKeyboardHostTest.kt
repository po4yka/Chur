package dev.po4yka.chur.app

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PrivateKeyboardHostTest {
    @Test
    fun the_no_learning_flag_survives_the_field_that_fills_the_editor_info() {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, _, _ -> null } as InputConnection
        // Compose's field assigns imeOptions outright, as EditorInfo.update does.
        val field = PlatformTextInputMethodRequest { info ->
            info.imeOptions = EditorInfo.IME_ACTION_GO
            connection
        }
        val info = EditorInfo()

        val result = withoutPersonalizedLearning(field).createInputConnection(info)

        assertSame(connection, result)
        assertEquals(EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions)
    }
}
