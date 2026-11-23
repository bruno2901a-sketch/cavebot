package com.rotabot

/**
 * Regras de decisão e confirmação do autoloot.
 * A execução dos gestos permanece coordenada pela Rota.
 */
object LootEngine {
    private const val DURACAO_MINIMA_LUTA_MS = 2000L

    fun ignorarLutaCurta(duracaoLutaMs: Long): Boolean {
        return duracaoLutaMs < DURACAO_MINIMA_LUTA_MS
    }

    fun confirmarColeta(totalItensPegos: Int): Boolean {
        return totalItensPegos > 0
    }
}
