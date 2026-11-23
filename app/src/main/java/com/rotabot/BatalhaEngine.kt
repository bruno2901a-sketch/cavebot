package com.rotabot

import android.graphics.Bitmap

/**
 * Detecção visual de batalha. Mantém a regra legada e a confirmação
 * combinada em um módulo separado da máquina de navegação.
 */
object BatalhaEngine {
    fun ehCorHp(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (g >= 155 && r <= 85 && b <= 90) ||
            (r >= 165 && g in 70..225 && b <= 85) ||
            (r >= 155 && g <= 75 && b <= 75)
    }

    fun temMonstroLegado(tela: Bitmap): Boolean {
        val x0 = (tela.width * Cal.BATALHA_X0)
            .toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.BATALHA_Y0)
            .toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.BATALHA_X1)
            .toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.BATALHA_Y1)
            .toInt().coerceIn(y0 + 1, tela.height)

        val rw = x1 - x0
        val rh = y1 - y0

        if (rw <= 0 || rh <= 0) return false

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        var pixelsDestaque = 0
        var pixelsBarraVida = 0

        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val ehFundoEscuro =
                r <= 32 && g <= 42 && b >= 18 && b <= 75

            if (!ehFundoEscuro && (r > 95 || g > 105 || b > 125)) {
                pixelsDestaque++
            }
            if (ehCorHp(p)) pixelsBarraVida++
        }

        val escala = (tela.width / 2460f).coerceIn(0.4f, 1.5f)
        return pixelsBarraVida >= (8f * escala).toInt().coerceAtLeast(3) && pixelsDestaque >= (220f * escala * escala).toInt().coerceAtLeast(35)
    }

    fun confirmarPresenca(linhasOcupadas: Int, detectorLegado: Boolean): Boolean {
        return linhasOcupadas > 0 || detectorLegado
    }
}
