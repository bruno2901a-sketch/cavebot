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
        fun temGestoEmAndamento(): Boolean = gestoEmAndamento.get()

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
            return try {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (service.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
                if (metrics.widthPixels <= 1 || metrics.heightPixels <= 0 ||
                    x < 0f || y < 0f || x >= metrics.widthPixels || y >= metrics.heightPixels) {
                    concluir(false, "Coordenada/tela inválida: ${x.toInt()},${y.toInt()} / ${metrics.widthPixels}x${metrics.heightPixels}")
                    return false
                }
                // Um trajeto de 1 px evita gestos de comprimento zero.
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
                aceito
            } catch (e: Exception) {
                concluir(false, "Falha ao enviar gesto: ${e.javaClass.simpleName}")
                false
            }
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
        gestoEmAndamento.set(false)
        super.onDestroy()
    }
}
