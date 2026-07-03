package nz.co.mixport.customsvision.scanner

import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicReference

object PdaHardwareKeyDispatcher {
    private data class KeyHandlers(
        val onKeyDown: ((Int) -> Boolean)?,
        val onKeyUp: ((Int) -> Boolean)?,
    )

    private val handlersRef = AtomicReference(KeyHandlers(onKeyDown = null, onKeyUp = null))

    fun setHandlers(
        onKeyDown: ((Int) -> Boolean)?,
        onKeyUp: ((Int) -> Boolean)? = null,
    ) {
        handlersRef.set(
            KeyHandlers(
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp,
            ),
        )
    }

    fun dispatchKeyDown(keyCode: Int): Boolean {
        if (!isHardwareScanKey(keyCode)) {
            return false
        }
        return handlersRef.get().onKeyDown?.invoke(keyCode) == true
    }

    fun dispatchKeyUp(keyCode: Int): Boolean {
        if (!isHardwareScanKey(keyCode)) {
            return false
        }
        return handlersRef.get().onKeyUp?.invoke(keyCode) == true
    }

    fun isHardwareScanKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_F11 ||
            keyCode == KeyEvent.KEYCODE_F12 ||
            keyCode == KeyEvent.KEYCODE_CAMERA
    }

    fun describeKey(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            KeyEvent.KEYCODE_CAMERA -> "CAMERA"
            else -> "KEYCODE_$keyCode"
        }
    }
}
