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

    fun tocar(x: Float, y: Float) {
        // Trajeto com moveTo + lineTo: um Path só com moveTo é cancelado em vários aparelhos.
        val caminho = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0, 40))
            .build()
        val aceito = dispatchGesture(gesto, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                resultado = "toque ok ${x.toInt()},${y.toInt()}"
            }
            override fun onCancelled(g: GestureDescription?) {
                resultado = "toque cancelado ${x.toInt()},${y.toInt()}"
            }
        }, null)
        if (!aceito) resultado = "toque recusado pelo sistema"
    }

    companion object {
        @Volatile var instancia: ToqueService? = null
        @Volatile var resultado: String = ""
    }
}
