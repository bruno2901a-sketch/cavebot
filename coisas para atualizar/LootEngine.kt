package com.rotabot

/**
 * Regras de decisão e confirmação do autoloot.
 * A execução dos gestos permanece coordenada pela Rota.
 */
object LootEngine {
    fun ignorarLutaCurta(duracaoLutaMs: Long): Boolean {
        return duracaoLutaMs < Cal.DURACAO_MINIMA_LUTA_MS
    }

    fun confirmarColeta(totalItensPegos: Int): Boolean {
        return totalItensPegos > 0
    }
}
