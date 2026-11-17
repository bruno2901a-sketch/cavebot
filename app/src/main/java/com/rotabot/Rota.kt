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

    const val CHEGOU_FRAC = 0.025f   // distância até o centro do minimapa para considerar chegada
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
    private var confirmacoes = 0      // leituras seguidas perto do pino
    private var ultimaDist = -1f      // distância no último toque
    private var semProgresso = 0      // toques seguidos sem chegar mais perto
    private var modelos: List<Modelo> = emptyList()

    fun carregar(ctx: Context, larguraTela: Int) {
        val escala = larguraTela / 2460f
        val lado = (32 * escala).toInt().coerceAtLeast(8)
        modelos = (1..Cal.TOTAL_ICONES).map { n ->
            val raw = ctx.assets.open("pins/p%02d.png".format(n)).use { BitmapFactory.decodeStream(it) }
            Modelo(n, Bitmap.createScaledBitmap(raw, lado, lado, true))
        }
    }

    fun configurar(novoInicio: Int, novoFim: Int) {
        inicio = novoInicio.coerceIn(1, Cal.TOTAL_ICONES)
        fim = novoFim.coerceIn(inicio, Cal.TOTAL_ICONES)
        resetar()
    }

    fun resetar() {
        alvo = 0; ultimoClique = 0L
        confirmacoes = 0; ultimaDist = -1f; semProgresso = 0
    }

    private fun proximoPino() {
        alvo++; ultimoClique = 0L
        confirmacoes = 0; ultimaDist = -1f; semProgresso = 0
    }

    /** Uma leitura. Retorna o estado para mostrar na notificação. */
    fun passo(tela: Bitmap, tocar: (Float, Float) -> Unit): String {
        if (temMonstro(tela)) return "LUTA (pausado)"

        val tamanho = fim - inicio + 1
        if (alvo >= tamanho) {
            resetar()                         // acabou a hunt: recomeça do primeiro
        }

        val x0 = (tela.width * Cal.MINIMAPA_X0).toInt()
        val y0 = (tela.height * Cal.MINIMAPA_Y0).toInt()
        val x1 = (tela.width * Cal.MINIMAPA_X1).toInt()
        val y1 = (tela.height * Cal.MINIMAPA_Y1).toInt()
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f

        val indice = inicio + alvo
        val modelo = modelos.firstOrNull { it.indice == indice }
            ?: return "SEM ÍCONE $indice"
        val pos = achar(tela, x0, y0, x1, y1, modelo.bmp)
            ?: return "PROCURANDO pino $indice (${alvo + 1}/$tamanho)"

        val dist = Math.hypot((pos.first - cx).toDouble(), (pos.second - cy).toDouble()).toFloat()
        if (dist <= tela.width * Cal.CHEGOU_FRAC) {
            // só dá por chegado depois de 2 leituras seguidas perto do pino
            confirmacoes++
            if (confirmacoes >= 2) {
                proximoPino()
                return "CHEGOU no pino $indice"
            }
            return "CHEGANDO no pino $indice"
        }
        confirmacoes = 0

        val agora = SystemClock.uptimeMillis()
        if (agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
            // se o toque anterior não deixou o pino mais perto, conta como sem avanço
            if (ultimaDist >= 0f && dist >= ultimaDist - tela.width * 0.003f) semProgresso++ else semProgresso = 0
            ultimaDist = dist
            tocar(pos.first.toFloat(), pos.second.toFloat())
            ultimoClique = agora
        }
        if (semProgresso >= 4) return "TRAVADO indo ao pino $indice (sem avanço $semProgresso)"
        return "INDO ao pino $indice (${alvo + 1}/$tamanho)"
    }

    /** Monstro na lista de batalha = barras de vida verdes dentro da área da lista. */
    fun temMonstro(tela: Bitmap): Boolean {
        val x0 = (tela.width * Cal.BATALHA_X0).toInt()
        val y0 = (tela.height * Cal.BATALHA_Y0).toInt()
        val x1 = (tela.width * Cal.BATALHA_X1).toInt()
        val y1 = (tela.height * Cal.BATALHA_Y1).toInt()
        var verdes = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = tela.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (g > 190 && r < 120 && b < 120) verdes++
                x += 3
            }
            y += 3
        }
        return verdes > 25
    }

    /** Busca o ícone na região do minimapa. Retorna o centro do melhor encaixe. */
    private fun achar(tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, m: Bitmap): Pair<Int, Int>? {
        val w = m.width
        val h = m.height
        val mg = gray(m)
        val ref = w * h * 255f * 255f
        var melhor = -1f
        var mx = 0
        var my = 0
        var y = y0
        while (y + h < y1) {
            var x = x0
            while (x + w < x1) {
                var soma = 0f
                var j = 0
                for (yy in 0 until h) for (xx in 0 until w) {
                    val p = tela.getPixel(x + xx, y + yy)
                    val g = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    val d = (g - mg[j]).toFloat()
                    soma += d * d
                    j++
                }
                val score = 1f - soma / ref
                if (score > melhor) { melhor = score; mx = x + w / 2; my = y + h / 2 }
                x += 2
            }
            y += 2
        }
        return if (melhor >= Cal.LIMIAR) Pair(mx, my) else null
    }

    private fun gray(b: Bitmap): IntArray {
        val out = IntArray(b.width * b.height)
        var i = 0
        for (yy in 0 until b.height) for (xx in 0 until b.width) {
            val p = b.getPixel(xx, yy)
            out[i++] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return out
    }
}
