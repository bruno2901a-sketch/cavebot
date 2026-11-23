package com.rotabot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LootEngineTest {
    @Test fun janelaLootExigeEscurecimentoEBarra() {
        assertTrue(LootEngine.confirmarJanelaLoot(20, 100, true))
        assertFalse(LootEngine.confirmarJanelaLoot(14, 100, true))
        assertFalse(LootEngine.confirmarJanelaLoot(80, 100, false))
        assertFalse(LootEngine.confirmarJanelaLoot(0, 0, true))
    }

    @Test fun itemRespeitaEscalaDaTela() {
        assertTrue(LootEngine.confirmarItem(10, 1f))
        assertFalse(LootEngine.confirmarItem(9, 1f))
        assertTrue(LootEngine.confirmarItem(40, 2f))
        assertFalse(LootEngine.confirmarItem(39, 2f))
    }

    @Test fun lutaCurtaUsaUmUnicoLimiar() {
        assertTrue(LootEngine.ignorarLutaCurta(Cal.DURACAO_MINIMA_LUTA_MS - 1))
        assertFalse(LootEngine.ignorarLutaCurta(Cal.DURACAO_MINIMA_LUTA_MS))
    }
}
