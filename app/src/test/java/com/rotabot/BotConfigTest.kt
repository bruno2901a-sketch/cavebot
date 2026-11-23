package com.rotabot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotConfigTest {
    @Test fun validaLimitesDeConfiguracao() {
        val config = BotConfig(
            marcadorInicio = -4,
            marcadorFim = 99,
            volumeAlerta = 150,
            limiteAlarmeSegundos = 1,
            intervaloLootMs = 900,
            maxAutolootSegundos = 2,
            intervaloPinoMs = 10,
            pixelsPorCasa = 500f,
            tamanhoCasaPx = 1f,
            limiarMinimapaParado = 0f,
            refBarraTituloX = -1f,
            refBarraTituloY = 2f
        ).validado()

        assertEquals(1, config.marcadorInicio)
        assertEquals(Cal.TOTAL_ICONES, config.marcadorFim)
        assertEquals(100, config.volumeAlerta)
        assertEquals(5, config.limiteAlarmeSegundos)
        assertEquals(500, config.intervaloLootMs)
        assertEquals(5, config.maxAutolootSegundos)
        assertEquals(300, config.intervaloPinoMs)
        assertEquals(200f, config.pixelsPorCasa)
        assertEquals(10f, config.tamanhoCasaPx)
        assertEquals(0.1f, config.limiarMinimapaParado)
        assertEquals(0f, config.refBarraTituloX)
        assertEquals(1f, config.refBarraTituloY)
        assertTrue(config.marcadorFim >= config.marcadorInicio)
    }
}
