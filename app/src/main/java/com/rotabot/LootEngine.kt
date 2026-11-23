package com.rotabot

/**
 * Regras puras de confirmação do autoloot.
 * Não acessa Android, tela, serviço ou relógio: pode ser validado por replay/testes.
 */
object LootEngine {
    fun ignorarLutaCurta(duracaoLutaMs: Long): Boolean {
        return duracaoLutaMs < Cal.DURACAO_MINIMA_LUTA_MS
    }

    fun confirmarJanelaLoot(
        pixelsEscuros: Int,
        totalPixels: Int,
        barraTituloDetectada: Boolean,
        razaoMinima: Float = 0.15f
    ): Boolean {
        if (totalPixels <= 0 || pixelsEscuros < 0) return false
        val razao = pixelsEscuros.toFloat() / totalPixels.toFloat()
        return razao >= razaoMinima.coerceIn(0f, 1f) && barraTituloDetectada
    }

    fun confirmarItem(pixelsNaoFundo: Int, escala: Float): Boolean {
        if (pixelsNaoFundo < 0) return false
        val escalaSegura = escala.coerceIn(0.1f, 3f)
        val minimoPixels = (10f * escalaSegura * escalaSegura).toInt().coerceAtLeast(2)
        return pixelsNaoFundo >= minimoPixels
    }

    fun confirmarColeta(totalItensPegos: Int): Boolean {
        return totalItensPegos > 0
    }
}
