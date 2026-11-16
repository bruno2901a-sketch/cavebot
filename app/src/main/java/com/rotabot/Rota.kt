package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock

// Referência: tela 2460 px de largura. Posições são frações da tela,
// então funcionam em qualquer resolução.
object Cal {
    const val MINIMAPA_X0 = 0.843f
    const val MINIMAPA_Y0 = 0.100f
    const val MINIMAPA_X1 = 0.995f
    const val MINIMAPA_Y1 = 0.625f

    const val BATALHA_X0 = 0.050f   // só dentro do painel "Batalha" (lista de monstros)
    const val BATALHA_Y0 = 0.210f
    const val BATALHA_X1 = 0.180f
    const val BATALHA_Y1 = 0.680f

    const val CHEGOU_FRAC = 0.012f   // distância até o centro do minimapa para considerar chegada
    const val LIMIAR = 0.75f         // correlação mínima (1.0 = igual). Abaixo disso não é o pino.
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
    private var melhorScore = 0f // para mostrar na notificação o quão parecido foi o melhor encaixe

    fun carregar(ctx: Context, larguraTela: Int) {
        val escala = larguraTela / 2460f
        val lado = (26 * escala).toInt().coerceAtLeast(8)
        modelos = (1..Cal.TOTAL_ICONES).map { n ->
            val raw = ctx.assets.open("pins/p%02d.png".format(n)).use { BitmapFactory.decodeStream(it) }
            Modelo(n, Bitmap.createScaledBitmap(raw, lado, lado, true))
        }
    }

    fun configurar(novoInicio: Int, novoFim: Int) {
        inicio = novoInicio.coerceIn(1, Cal.TOTAL_ICONES)
        fim = novoFim.coerceIn(inicio, Cal.TOTAL_ICONES)
        alvo = 0
        ultimoClique = 0L
    }

    fun resetar() { alvo = 0; ultimoClique = 0L }

    /** Uma leitura. Retorna o estado para mostrar na notificação. */
    fun passo(tela: Bitmap, tocar: (Float, Float) -> Unit): String {
        if (temMonstro(tela)) return "LUTA (pausado)"

        val tamanho = fim - inicio + 1
        if (alvo >= tamanho) {
            alvo = 0                          // acabou a hunt: recomeça do primeiro
            ultimoClique = 0L
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
            ?: return "PROCURANDO pino $indice (${alvo + 1}/$tamanho) melhor=%.2f".format(melhorScore)

        val dist = Math.hypot((pos.first - cx).toDouble(), (pos.second - cy).toDouble()).toFloat()
        if (dist <= tela.width * Cal.CHEGOU_FRAC) {
            alvo++
            ultimoClique = 0L
            return "CHEGOU no pino $indice"
        }

        val agora = SystemClock.uptimeMillis()
        if (agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
            tocar(pos.first.toFloat(), pos.second.toFloat())
            ultimoClique = agora
        }
        return "INDO ao pino $indice (${alvo + 1}/$tamanho) acerto=%.2f".format(melhorScore)
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
                if (g > 110 && r < 60 && b < 60) verdes++   // barra de vida do monstro (verde escuro)
                x += 3
            }
            y += 3
        }
        return verdes > 25
    }

    /**
     * Busca o ícone na região do minimapa usando correlação (1.0 = igual, 0 = nada parecido).
     * Retorna o centro do melhor encaixe, só se passar do LIMIAR.
     */
    private fun achar(tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, m: Bitmap): Pair<Int, Int>? {
        val w = m.width
        val h = m.height
        val n = w * h
        val mg = gray(m)
        var somaT = 0.0
        for (v in mg) somaT += v
        val mediaT = somaT / n
        var varT = 0.0
        for (v in mg) varT += (v - mediaT) * (v - mediaT)
        if (varT < 1.0) { melhorScore = 0f; return null }
        val raizT = Math.sqrt(varT)

        val janela = IntArray(n)
        var melhor = -1.0
        var mx = 0
        var my = 0
        var y = y0
        while (y + h < y1) {
            var x = x0
            while (x + w < x1) {
                var soma = 0.0
                var j = 0
                for (yy in 0 until h) for (xx in 0 until w) {
                    val p = tela.getPixel(x + xx, y + yy)
                    val v = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    janela[j] = v
                    soma += v
                    j++
                }
                val mediaJ = soma / n
                var cov = 0.0
                var varJ = 0.0
                for (k in 0 until n) {
                    val a = janela[k] - mediaJ
                    cov += a * (mg[k] - mediaT)
                    varJ += a * a
                }
                val score = if (varJ < 1.0) 0.0 else cov / (Math.sqrt(varJ) * raizT)
                if (score > melhor) { melhor = score; mx = x + w / 2; my = y + h / 2 }
                x += 2
            }
            y += 2
        }
        melhorScore = melhor.toFloat()
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
