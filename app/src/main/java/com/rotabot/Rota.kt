package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock

// Referência: tela 2460 px de largura. Posições são frações da tela,
// então funcionam em qualquer resolução.
object Cal {
    // Área onde os pinos aparecem no minimapa
    const val MINIMAPA_X0 = 0.830f
    const val MINIMAPA_Y0 = 0.100f
    const val MINIMAPA_X1 = 0.998f
    const val MINIMAPA_Y1 = 0.640f

    // Posição da cruz branca (o personagem) no minimapa
    const val CENTRO_X = 0.920f
    const val CENTRO_Y = 0.374f

    // Lista de batalha
    const val BATALHA_X0 = 0.000f
    const val BATALHA_Y0 = 0.105f
    const val BATALHA_X1 = 0.160f
    const val BATALHA_Y1 = 0.790f

    const val CHEGOU_FRAC = 0.015f   // distância até a cruz para considerar que chegou
    const val LIMIAR = 0.90f         // semelhança mínima (0 a 1) para aceitar o pino
    const val INTERVALO_CLIQUE = 1500L
    const val TOTAL_ICONES = 20
    const val LADO_ICONE = 26        // tamanho real dos ícones na tela de 2460 px
}

private fun cinza(p: Int): Double =
    ((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114

/** Ícone do pino já em tons de cinza, sem a média, para comparar por correlação. */
class Modelo(val indice: Int, bmp: Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val z = DoubleArray(w * h)
    val norma: Double

    init {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var soma = 0.0
        for (i in px.indices) { z[i] = cinza(px[i]); soma += z[i] }
        val media = soma / z.size
        var q = 0.0
        for (i in z.indices) { z[i] -= media; q += z[i] * z[i] }
        norma = Math.sqrt(q)
    }
}

object Rota {
    // Hunt escolhida pelo usuário: intervalo da paleta, ex.: 1 a 5.
    var inicio = 1
    var fim = 5

    var alvo = 0                       // posição dentro da hunt que o bot está indo
    private var ultimoClique = 0L
    private var confirmacoes = 0       // leituras seguidas perto do pino
    private var ultimaDist = -1f       // distância no último toque
    private var semProgresso = 0       // toques seguidos sem chegar mais perto
    private var naoVisto = 0           // leituras seguidas sem achar o pino
    private var ultimaDistVista = -1f  // distância da última vez que o pino foi visto
    @Volatile private var melhorScore = 0.0
    private var modelos: List<Modelo> = emptyList()

    fun carregar(ctx: Context, larguraTela: Int) {
        val lado = (Cal.LADO_ICONE * larguraTela / 2460f).toInt().coerceAtLeast(8)
        modelos = (1..Cal.TOTAL_ICONES).map { n ->
            val raw = ctx.assets.open("pins/p%02d.png".format(n)).use { BitmapFactory.decodeStream(it) }
            val bmp = if (raw.width == lado && raw.height == lado) raw
            else Bitmap.createScaledBitmap(raw, lado, lado, true)
            Modelo(n, bmp)
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
        naoVisto = 0; ultimaDistVista = -1f
    }

    private fun proximoPino() {
        alvo++; ultimoClique = 0L
        confirmacoes = 0; ultimaDist = -1f; semProgresso = 0
        naoVisto = 0; ultimaDistVista = -1f
    }

    /** Uma leitura. Retorna o estado para mostrar na notificação. */
    fun passo(tela: Bitmap, tocar: (Float, Float) -> Unit): String {
        if (temMonstro(tela)) return "LUTA (pausado)"

        val tamanho = fim - inicio + 1
        if (alvo >= tamanho) resetar()    // acabou a hunt: recomeça do primeiro

        val x0 = (tela.width * Cal.MINIMAPA_X0).toInt()
        val y0 = (tela.height * Cal.MINIMAPA_Y0).toInt()
        val x1 = (tela.width * Cal.MINIMAPA_X1).toInt()
        val y1 = (tela.height * Cal.MINIMAPA_Y1).toInt()
        val cx = tela.width * Cal.CENTRO_X
        val cy = tela.height * Cal.CENTRO_Y

        val indice = inicio + alvo
        val modelo = modelos.firstOrNull { it.indice == indice }
            ?: return "SEM ÍCONE $indice"

        val achado = achar(tela, x0, y0, x1, y1, modelo)
        val acerto = "acerto=" + "%.2f".format(melhorScore)

        if (achado == null) {
            naoVisto++
            // o pino costuma sumir ou ficar escondido embaixo da cruz quando se chega nele
            if (ultimaDistVista in 0f..(tela.width * 0.06f) && naoVisto >= 2) {
                proximoPino()
                return "CHEGOU no pino $indice (pino sumiu do mapa)"
            }
            if (naoVisto >= 30) return "PINO $indice FORA DO MINIMAPA? $acerto"
            return "PROCURANDO pino $indice (${alvo + 1}/$tamanho) $acerto"
        }
        naoVisto = 0

        val dist = Math.hypot((achado.first - cx).toDouble(), (achado.second - cy).toDouble()).toFloat()
        ultimaDistVista = dist

        if (dist <= tela.width * Cal.CHEGOU_FRAC) {
            // só dá por chegado depois de 2 leituras seguidas perto do pino
            confirmacoes++
            if (confirmacoes >= 2) {
                proximoPino()
                return "CHEGOU no pino $indice"
            }
            return "CHEGANDO no pino $indice $acerto"
        }
        confirmacoes = 0

        val agora = SystemClock.uptimeMillis()
        if (agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
            // se o toque anterior não deixou o pino mais perto, conta como sem avanço
            if (ultimaDist >= 0f && dist >= ultimaDist - tela.width * 0.003f) semProgresso++ else semProgresso = 0
            ultimaDist = dist
            tocar(achado.first.toFloat(), achado.second.toFloat())
            ultimoClique = agora
        }
        if (semProgresso >= 4) return "TRAVADO indo ao pino $indice (sem avanço $semProgresso) $acerto"
        return "INDO ao pino $indice (${alvo + 1}/$tamanho) $acerto"
    }

    /** Monstro na lista de batalha = barras de vida verdes dentro da área da lista. */
    fun temMonstro(tela: Bitmap): Boolean {
        val x0 = (tela.width * Cal.BATALHA_X0).toInt()
        val y0 = (tela.height * Cal.BATALHA_Y0).toInt()
        val x1 = (tela.width * Cal.BATALHA_X1).toInt()
        val y1 = (tela.height * Cal.BATALHA_Y1).toInt()
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return false
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        var verdes = 0
        var y = 0
        while (y < rh) {
            var x = 0
            while (x < rw) {
                val p = px[y * rw + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (g > 140 && r < 100 && b < 100) verdes++
                x += 3
            }
            y += 3
        }
        return verdes > 25
    }

    /**
     * Procura o ícone na região do minimapa por correlação normalizada
     * (1,0 = igual; perto de 0 = sem relação). Guarda o melhor valor em melhorScore.
     * Retorna o centro do melhor encaixe, ou null se ficou abaixo do limiar.
     */
    private fun achar(tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, m: Modelo): Pair<Int, Int>? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < m.w || rh < m.h || m.norma < 1e-3) { melhorScore = 0.0; return null }

        // lê a região uma vez só (getPixel pixel a pixel é muito lento)
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        val g = DoubleArray(rw * rh) { cinza(px[it]) }

        var melhor = -2.0
        var bx = 0
        var by = 0
        var y = 0
        while (y <= rh - m.h) {
            var x = 0
            while (x <= rw - m.w) {
                val s = pontuar(g, rw, x, y, m)
                if (s > melhor) { melhor = s; bx = x; by = y }
                x += 2
            }
            y += 2
        }
        // refina em volta do melhor ponto (a busca andou de 2 em 2 pixels)
        for (dy in -1..1) for (dx in -1..1) {
            val x = bx + dx
            val yy = by + dy
            if (x < 0 || yy < 0 || x > rw - m.w || yy > rh - m.h) continue
            val s = pontuar(g, rw, x, yy, m)
            if (s > melhor) { melhor = s; bx = x; by = yy }
        }

        melhorScore = melhor
        return if (melhor >= Cal.LIMIAR) Pair(x0 + bx + m.w / 2, y0 + by + m.h / 2) else null
    }

    private fun pontuar(g: DoubleArray, rw: Int, x: Int, y: Int, m: Modelo): Double {
        var sp = 0.0
        var sq = 0.0
        var sc = 0.0
        var j = 0
        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            for (xx in 0 until m.w) {
                val v = g[base + xx]
                sp += v
                sq += v * v
                sc += v * m.z[j]
                j++
            }
        }
        val n = (m.w * m.h).toDouble()
        val variancia = sq - sp * sp / n
        if (variancia < 50.0) return -1.0     // trecho liso: sem como comparar
        return sc / (Math.sqrt(variancia) * m.norma)
    }
}
