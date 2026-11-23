package com.rotabot

/**
 * Estado operacional explícito da rota. Mantém o estado de alto nível separado
 * dos contadores de visão e da subfase de navegação.
 */
sealed interface NavigationState {
    data object Escolhendo : NavigationState
    data class Caminhando(val pino: Int) : NavigationState
    data class Lutando(val inicioUptimeMs: Long) : NavigationState
    data object Lootando : NavigationState
    data class Recuperando(val pino: Int, val tentativa: Int) : NavigationState
    data class Pausado(val motivo: String) : NavigationState
}

class NavigationStateMachine {
    var state: NavigationState = NavigationState.Escolhendo
        private set

    fun transition(next: NavigationState) {
        state = next
    }
}
