package com.rotabot

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Configuração imutável transferida da Activity para o serviço. */
@Parcelize
data class BotConfig(
    val marcadorInicio: Int = 1,
    val marcadorFim: Int = 5,
    val usaLoot: Boolean = false,
    val usaAlerta: Boolean = true,
    val somenteAlarme: Boolean = false,
    val modoCircular: Boolean = false,
    val salvarPrints: Boolean = true,
    val volumeAlerta: Int = 80,
    val uriSomAlerta: String = "",
    val limiteAlarmeSegundos: Int = 30,
    val intervaloLootMs: Int = 100,
    val maxAutolootSegundos: Int = 40,
    val intervaloPinoMs: Int = 700,
    val pixelsPorCasa: Float = Cal.PIXELS_POR_CASA_PADRAO,
    val tamanhoCasaPx: Float = Cal.TAMANHO_CASA_PX_PADRAO,
    val limiarMinimapaParado: Float = Cal.LIMIAR_MINIMAPA_PARADO_PADRAO,
    val refBarraTituloX: Float = Cal.REF_BARRA_TITULO_X_PADRAO,
    val refBarraTituloY: Float = Cal.REF_BARRA_TITULO_Y_PADRAO
) : Parcelable {
    fun validado(): BotConfig = copy(
        marcadorInicio = marcadorInicio.coerceIn(1, Cal.TOTAL_ICONES),
        marcadorFim = marcadorFim.coerceIn(marcadorInicio.coerceIn(1, Cal.TOTAL_ICONES), Cal.TOTAL_ICONES),
        volumeAlerta = volumeAlerta.coerceIn(0, 100),
        limiteAlarmeSegundos = limiteAlarmeSegundos.coerceIn(5, 300),
        intervaloLootMs = intervaloLootMs.coerceIn(30, 500),
        maxAutolootSegundos = maxAutolootSegundos.coerceIn(5, 120),
        intervaloPinoMs = intervaloPinoMs.coerceIn(300, 2000),
        pixelsPorCasa = pixelsPorCasa.coerceIn(4f, 200f),
        tamanhoCasaPx = tamanhoCasaPx.coerceIn(10f, 300f),
        limiarMinimapaParado = limiarMinimapaParado.coerceIn(0.1f, 100f),
        refBarraTituloX = refBarraTituloX.coerceIn(0f, 1f),
        refBarraTituloY = refBarraTituloY.coerceIn(0f, 1f)
    )
}
