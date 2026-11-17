package com.rotabot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ToqueService : AccessibilityService() {

    companion object {
        var instance: ToqueService? = null
            private set

        fun clicar(x: Float, y: Float, duracaoMs: Long = 65L, callback: (() -> Unit)? = null) {
            val service = instance
            if (service == null || x < 0f || y < 0f) return

            val path = Path().apply { moveTo(x, y) }
            val gestureBuilder = GestureDescription.Builder()
            val stroke = GestureDescription.StrokeDescription(path, 0L, duracaoMs)
            gestureBuilder.addStroke(stroke)

            service.dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        callback?.invoke()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                    }
                },
                null
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
