package nz.co.mixport.customsvision.scanner

import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicReference

object PdaHardwareKeyDispatcher {
    private val handlerRef = AtomicReference<((Int) -> Boolean)?>(null)

    fun setHandler(handler: ((Int) -> Boolean)?) {
        handlerRef.set(handler)
    }

    fun dispatchKeyDown(keyCode: Int): Boolean {
        if (!isHardwareScanKey(keyCode)) {
            return false
        }
        return handlerRef.get()?.invoke(keyCode) == true
    }

    fun shouldConsumeKeyUp(keyCode: Int): Boolean {
        return isHardwareScanKey(keyCode) && handlerRef.get() != null
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
