package com.rotabot

import android.graphics.Bitmap
import kotlin.math.sqrt

object Cal {
    const val LADO_ICONE = 26
    const val LIMIAR = 0.90f
    const val LIMIAR_VERDE = 0.78f
    const val MARGEM_VERDE = 0.03f
    const val CHEGOU_FRAC = 0.018f
    const val TOLERANCIA_CHEGADA_CASAS = 1.5f
    const val PERMITIR_PULO = false
    const val INTERVALO_CLIQUE = 700L
    const val TOTAL_ICONES = 20
    const val MINIMAPA_X0 = 0.848f
    const val MINIMAPA_Y0 = 0.135f
    const val MINIMAPA_X1 = 0.996f
    const val MINIMAPA_Y1 = 0.615f
    const val BATALHA_X0 = 0.045f
    const val BATALHA_Y0 = 0.203f
    const val BATALHA_X1 = 0.181f
    const val BATALHA_Y1 = 0.392f
    const val MAX_FALHAS_PINO = 3
    const val IGNORAR_LUTA_MS = 4000L
    const val NAO_VISTO_LIMITE = 24
    const val PIXELS_POR_CASA_PADRAO = 16.0f
    const val TAMANHO_CASA_PX_PADRAO = 88.0f
    const val LIMIAR_MINIMAPA_PARADO_PADRAO = 3.2f
    const val REF_BARRA_TITULO_X_PADRAO = 0.620f
    const val REF_BARRA_TITULO_Y_PADRAO = 0.145f
    const val TEMPO_MINIMO_PARADO_CHEGADA_MS = 500L
    const val TEMPO_PARADO_RECLIQUE_MS = 1500L
    const val TEMPO_POR_CASA_MS = 700L
    const val FATOR_TEMPO_MAX_PINO = 3L
    const val FOLGA_TEMPO_MAX_PINO_MS = 4000L
    const val PISO_ANIMACAO_MAX_PX = 28.0
    const val JANELA_ABERTURA_LOOT_MS = 1500L
    const val TEMPO_MAX_POR_CORPO_MS = 6000L
    const val MAX_TENTATIVAS_ITEM_LOOT = 2
    val PONTOS_CORPO: List<Pair<Float, Float>> = listOf(
        Pair(0.4890f, 0.5011f),
        Pair(0.4880f, 0.6082f),
        Pair(0.5270f, 0.6082f),
        Pair(0.5660f, 0.5968f),
        Pair(0.5650f, 0.4989f),
        Pair(0.4890f, 0.4077f),
        Pair(0.5260f, 0.3964f),
        Pair(0.5680f, 0.3986f)
    )
    val PONTOS_LOOT: List<Pair<Float, Float>> = listOf(
        Pair(0.5403f, 0.3351f),
        Pair(0.7027f, 0.1635f),
        Pair(0.6994f, 0.3454f),
        Pair(0.5357f, 0.1677f)
    )
}

internal fun cinza(p: Int): Double {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b
}

class Modelo(
    val indice: Int,
    bmp: Bitmap
) {
    val w: Int = bmp.width
    val h: Int = bmp.height
    val z: DoubleArray
    val valida: BooleanArray
    val pixelsValidos: Int
    val norma: Double

    init {
        val total = w * h
        val px = IntArray(total)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val cinzas = DoubleArray(total)
        val mask = BooleanArray(total)
        var validos = 0
        var soma = 0.0

        for (i in 0 until total) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            if (a >= 32) {
                val c = cinza(p)
                cinzas[i] = c
                mask[i] = true
                validos++
                soma += c
            }
        }

        val media = if (validos > 0) soma / validos else 0.0
        val zArr = DoubleArray(total)
        var somaSq = 0.0

        for (i in 0 until total) {
            if (mask[i]) {
                val v = cinzas[i] - media
                zArr[i] = v
                somaSq += v * v
            }
        }

        z = zArr
        valida = mask
        pixelsValidos = validos
        norma = sqrt(somaSq)
    }
}
