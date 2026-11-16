package com.rotabot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

// Serviço de acessibilidade: envia toques na tela do jogo.
// Precisa ser ativado em Configurações > Acessibilidade.
class ToqueService : AccessibilityService() {

    override fun onServiceConnected() {
        instancia = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instancia === this) instancia = null
        super.onDestroy()
    }

    // Toque de 120 ms (mais longo que o anterior, 50 ms, que alguns jogos ignoram).
    // O resultado (entregue, cancelado ou recusado) aparece na notificação.
    fun tocar(x: Float, y: Float) {
        val caminho = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0, 120))
            .build()
        val aceitou = dispatchGesture(gesto, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                resultado = "toque entregue em ${x.toInt()},${y.toInt()}"
            }
            override fun onCancelled(g: GestureDescription?) {
                resultado = "toque cancelado em ${x.toInt()},${y.toInt()}"
            }
        }, null)
        if (!aceitou) resultado = "sistema recusou o toque"
    }

    companion object {
        @Volatile var instancia: ToqueService? = null
        @Volatile var resultado = "nenhum toque ainda"
    }
}
