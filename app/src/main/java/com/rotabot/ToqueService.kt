package com.rotabot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ToqueService : AccessibilityService() {
    companion object {
        @Volatile var instance: ToqueService? = null
            private set

        // AccessibilityService cancels an active gesture when another is dispatched.
        // Keep one gesture in flight at a time; a timeout does not release this guard.
        private val gestoEmAndamento = AtomicBoolean(false)

        fun clicarEAguardar(x: Float, y: Float, duracaoMs: Long = 50L, timeoutMs: Long = 1800L): Pair<Boolean, String> {
            val latch = CountDownLatch(1)
            var resultado = false
            var mensagem = "Tempo limite aguardando gesto"
            val aceito = clicar(x, y, duracaoMs) { ok, msg ->
                resultado = ok
                mensagem = msg
                latch.countDown()
            }
            if (!aceito) return Pair(false, mensagem)
            if (!latch.await(timeoutMs.coerceAtLeast(100L), TimeUnit.MILLISECONDS)) {
                return Pair(false, "Tempo limite aguardando conclusão do gesto")
            }
            return Pair(resultado, mensagem)
        }

        fun clicar(x: Float, y: Float, duracaoMs: Long = 50L, callback: ((Boolean, String) -> Unit)? = null): Boolean {
            val service = instance ?: run { callback?.invoke(false, "Acessibilidade desconectada"); return false }
            if (!gestoEmAndamento.compareAndSet(false, true)) {
                callback?.invoke(false, "Toque anterior ainda não foi concluído; novo toque bloqueado")
                return false
            }
            val callbackConcluido = AtomicBoolean(false)
            fun concluir(ok: Boolean, mensagem: String) {
                if (callbackConcluido.compareAndSet(false, true)) {
                    try {
                        callback?.invoke(ok, mensagem)
                    } finally {
                        gestoEmAndamento.set(false)
                    }
                }
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            if (x < 0f || y < 0f || x >= metrics.widthPixels || y >= metrics.heightPixels) {
                concluir(false, "Coordenada fora da tela: ${x.toInt()},${y.toInt()} / ${metrics.widthPixels}x${metrics.heightPixels}")
                return false
            }
            // Um trajeto só com moveTo tem comprimento zero e vários aparelhos cancelam.
            // Com um traço de 1 px o toque é aceito como um toque normal.
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + if (x < metrics.widthPixels - 1f) 1f else -1f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duracaoMs.coerceAtLeast(1L)))
                .build()
            val aceito = service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    concluir(true, "Gesto concluído em ${x.toInt()},${y.toInt()} (jogo não confirmado)")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    concluir(false, "Android cancelou o toque em ${x.toInt()},${y.toInt()}")
                }
            }, null)
            if (!aceito) concluir(false, "Android recusou o gesto em ${x.toInt()},${y.toInt()}")
            return aceito
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
