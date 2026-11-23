package com.rotabot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatalhaEngineTest {
    @Test
    fun reconheceBarraHpVerde() {
        val pixel = (40 shl 16) or (180 shl 8) or 40
        assertTrue(BatalhaEngine.ehCorHp(pixel))
    }

    @Test
    fun reconheceBarraHpAmarela() {
        val pixel = (190 shl 16) or (150 shl 8) or 30
        assertTrue(BatalhaEngine.ehCorHp(pixel))
    }

    @Test
    fun rejeitaPixelSemCorDeHp() {
        val pixel = (100 shl 16) or (100 shl 8) or 100
        assertFalse(BatalhaEngine.ehCorHp(pixel))
    }

    @Test
    fun confirmaPresencaQuandoDetectorNovoEncontraLinha() {
        assertTrue(BatalhaEngine.confirmarPresenca(1, false))
    }

    @Test
    fun confirmaPresencaQuandoDetectorLegadoEncontraMonstro() {
        assertTrue(BatalhaEngine.confirmarPresenca(0, true))
    }

    @Test
    fun rejeitaQuandoAmbosDetectoresNaoEncontramMonstro() {
        assertFalse(BatalhaEngine.confirmarPresenca(0, false))
    }
}
