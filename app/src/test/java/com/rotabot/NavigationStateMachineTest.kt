package com.rotabot

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStateMachineTest {
    @Test fun transicoesGuardamEstadoCompleto() {
        val machine = NavigationStateMachine()
        assertEquals(NavigationState.Escolhendo, machine.state)
        machine.transition(NavigationState.Caminhando(4))
        assertEquals(NavigationState.Caminhando(4), machine.state)
        machine.transition(NavigationState.Lutando(1234L))
        assertEquals(NavigationState.Lutando(1234L), machine.state)
        machine.transition(NavigationState.Lootando)
        assertEquals(NavigationState.Lootando, machine.state)
        machine.transition(NavigationState.Recuperando(4, 2))
        assertEquals(NavigationState.Recuperando(4, 2), machine.state)
        machine.transition(NavigationState.Pausado("pino perdido"))
        assertEquals(NavigationState.Pausado("pino perdido"), machine.state)
    }
}
