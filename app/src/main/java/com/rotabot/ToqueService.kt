package com.rotabot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class ToqueService : AccessibilityService() {
    companion object {
        @Volatile var instance: ToqueService? = null
            private set

        fun clicar(x: Float, y: Float, duracaoMs: Long = 90L, callback: ((Boolean, String) -> Unit)? = null): Boolean {
            val service = instance ?: run { callback?.invoke(false, "Acessibilidade desconectada"); return false }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            if (x < 0f || y < 0f || x >= metrics.widthPixels || y >= metrics.heightPixels) {
                callback?.invoke(false, "Coordenada fora da tela: \${x.toInt()},\${y.toInt()} / \${metrics.widthPixels}x\${metrics.heightPixels}")
                return false
            }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duracaoMs.coerceAtLeast(1L)))
                .build()
            return service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true, "Toque concluído em \${x.toInt()},\${y.toInt()}")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false, "Android cancelou o toque em \${x.toInt()},\${y.toInt()}")
                }
            }, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
