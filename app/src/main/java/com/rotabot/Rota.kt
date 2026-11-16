package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock

// Referência: tela 2460 px de largura. Posições são frações da tela,
// então funcionam em qualquer resolução.
object Cal {
    const val MINIMAPA_X0 = 0.850f
    const val MINIMAPA_Y0 = 0.100f
    const val MINIMAPA_X1 = 0.995f
    const val MINIMAPA_Y1 = 0.625f

    const val BATALHA_X0 = 0.000f
    const val BATALHA_Y0 = 0.105f
    const val BATALHA_X1 = 0.160f
    const val BATALHA_Y1 = 0.790f

    const val CHEGOU_FRAC = 0.012f   // distância até o centro do minimapa para considerar chegada
    const val LIMIAR = 0.80f         // qualidade mínima do reconhecimento do pino
    const val INTERVALO_CLIQUE = 1500L
    const val TOTAL_ICONES = 20      // ícones da paleta "Criar marca", em ordem de leitura
}

class Modelo(val indice: Int, val bmp: Bitmap)

object Rota {
    // Hunt escolhida pelo usuário: intervalo da paleta, ex.: 1 a 5.
    var inicio = 1
    var fim = 5

    var alvo = 0                 // memória: posição dentro da hunt que o bot está indo
    private var ultimoClique = 0L
    private var modelos: List<Modelo> = emptyList()

    fun carregar(ctx: Context, larguraTela: Int) {
        val escala = larguraTela / 2460f