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
        val caminho = Path().apply { moveTo(x, y) }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0, 50))
            .build()
        dispatchGesture(gesto, null, null)
    }

    companion object {
        @Volatile var instancia: ToqueService? = null
    }
}
