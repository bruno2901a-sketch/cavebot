package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock

// Posições são frações da tela (referência: 2460 x 1080, celular deitado).
object Cal {
    // Conteúdo do minimapa (sem o título e sem o jogo ao redor)
    const val MINIMAPA_X0 = 0.848f
    const val MINIMAPA_Y0 = 0.135f
    const val MINIMAPA_X1 = 0.996f
    const val MINIMAPA_Y1 = 0.615f

    // Lista de batalha (só a lista, abaixo da fileira de ícones do painel)
    const val BATALHA_X0 = 0.045f
    const val BATALHA_Y0 = 0.215f
    const val BATALHA_X1 = 0.195f
    const val BATALHA_Y1 = 0.650f
    const val BARRA_MIN = 20          // largura mínima (px) de uma barra de vida verde

    const val CHEGOU_FRAC = 0.015f    // distância (fração da largura) até a cruz = chegou
    const val LIMIAR = 0.90f          // semelhança mínima (0 a 1) para aceitar um pino
    const val INTERVALO_CLIQUE = 1500L
    const val TOTAL_ICONES = 20
    const val LADO_ICONE = 26         // tamanho real dos ícones numa tela de 2460 px

    // Coordenadas em fração da tela dos pontos 1 a 8 ao redor do personagem
    val PONTOS_CORPO = listOf(
        Pair(0.4890f, 0.5011f), // 1
        Pair(0.4880f, 0.6082f), // 2
        Pair(0.5270f, 0.6082f), // 3
        Pair(0.5660f, 0.5968f), // 4
        Pair(0.5650f, 0.4989f), // 5
        Pair(0.4890f, 0.4077f), // 6
        Pair(0.5260f, 0.3964f), // 7
        Pair(0.5680f, 0.3986f)  // 8
    )

    // Coordenadas em fração da tela das novas janelas de loot na sequência exata: 1, 2, 3, 4
    val PONTOS_LOOT = listOf(
        Pair(0.5403f, 0.3351f), // 1
        Pair(0.7027f, 0.1635f), // 2
        Pair(0.6994f, 0.3454f), // 3
        Pair(0.5357f, 0.1677f)  // 4
    )
}

private fun cinza(p: Int): Double =
    ((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114

/** Ícone do pino em tons de cinza, sem a média, pronto para comparar por correlação. */
class Modelo(val indice: Int, bmp: Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val z = DoubleArray(w * h)
    val norma: Double

    init {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var soma = 0.0
        for (i in px.indices) {
            z[i] = cinza(px[i])
            soma += z[i]
        }
        val media = soma / z.size
        var q = 0.0
        for (i in z.indices) {
            z[i] -= media
            q += z[i] * z[i]
        }
        norma = Math.sqrt(q)
    }
}

object Rota {
    private var modelos: List<Modelo> = emptyList()

    private var alvo = 0                  // posição atual dentro da lista de pinos
    private var ultimoClique = 0L
    private var confirmacoes = 0          // leituras seguidas perto do pino
    private var ultimaDist = -1f          // distância no último toque
    private var semProgresso = 0          // toques seguidos sem chegar mais perto
    private var naoVisto = 0              // leituras seguidas sem achar o pino
    private var ultimaDistVista = -1f     // distância da última vez que o pino foi visto
    private var estavaEmLuta = false      // controla se havia monstro na leitura anterior
    @Volatile private var melhorScore = 0.0

    /** Carrega os ícones de [inicio] a [fim] já no tamanho certo para a largura da tela. */
    fun carregar(ctx: Context, inicio: Int, fim: Int, larguraTela: Int) {
        val lado = (Cal.LADO_ICONE * larguraTela / 2460f).toInt().coerceAtLeast(8)
        val lista = ArrayList<Modelo>()
        for (n in inicio..fim) {
            val raw = ctx.assets.open("pins/p%02d.png".format(n)).use { BitmapFactory.decodeStream(it) }
                ?: continue
            val bmp = if (raw.width == lado && raw.height == lado) raw
            else Bitmap.createScaledBitmap(raw, lado, lado, true)
            lista.add(Modelo(n, bmp))
        }
        modelos = lista
        resetar()
    }

    fun quantidade(): Int = modelos.size

    fun limpar() {
        modelos = emptyList()
        resetar()
    }

    fun resetar() {
        alvo = 0
        ultimoClique = 0L
        confirmacoes = 0
        ultimaDist = -1f
        semProgresso = 0
        naoVisto = 0
        ultimaDistVista = -1f
        estavaEmLuta = false
    }

    private fun proximoPino() {
        alvo++
        ultimoClique = 0L
        confirmacoes = 0
        ultimaDist = -1f
        semProgresso = 0
        naoVisto = 0
        ultimaDistVista = -1f
    }

    /** Executa a rotina completa de autoloot após o monstro morrer. */
    private fun executarAutoloot(larguraTela: Int, alturaTela: Int) {
        // 1. Espera 1 segundo para o monstro cair e gerar o corpo no chão
        SystemClock.sleep(1000L)

        // 2. Dá 2 cliques no centro de cada ponto de 1 a 8 ao redor do personagem
        for (ponto in Cal.PONTOS_CORPO) {
            val px = ponto.first * larguraTela
            val py = ponto.second * alturaTela
            ToqueService.clicar(px, py, 30L)
            SystemClock.sleep(60L)
            ToqueService.clicar(px, py, 30L)
            SystemClock.sleep(100L)
        }

        // Pequena pausa para as janelas de loot abrirem
        SystemClock.sleep(250L)

        // 3. Clica 4 vezes em cada janela de loot na ordem exata: 1, 2, 3, 4 (com intervalo de 100 ms entre os cliques)
        for (ponto in Cal.PONTOS_LOOT) {
            val lx = ponto.first * larguraTela
            val ly = ponto.second * alturaTela
            repeat(4) {
                ToqueService.clicar(lx, ly, 30L)
                SystemClock.sleep(100L)
            }
            SystemClock.sleep(100L)
        }
    }

    /** Uma leitura da tela. Retorna o estado para mostrar no painel. */
    fun passo(tela: Bitmap, tocar: (Float, Float) -> Unit): String {
        if (tela.height > tela.width) return "CELULAR EM RETRATO (pausado)"
        if (modelos.isEmpty()) return "SEM ÍCONES CARREGADOS"

        val x0 = (tela.width * Cal.MINIMAPA_X0).toInt()
        val y0 = (tela.height * Cal.MINIMAPA_Y0).toInt()
        val x1 = (tela.width * Cal.MINIMAPA_X1).toInt()
        val y1 = (tela.height * Cal.MINIMAPA_Y1).toInt()

        // A cruz branca é o personagem. Sem ela não há minimapa na tela
        // (barra de notificações, menu aberto etc.): pausa sem mudar nada.
        val cruz = acharCruz(tela, x0, y0, x1, y1) ?: return "SEM CRUZ NO MINIMAPA (pausado)"

        if (temMonstro(tela)) {
            estavaEmLuta = true
            return "LUTA (pausado)"
        }

        // Se estava em luta e a janela de batalha ficou vazia, coleta o loot antes de andar
        if (estavaEmLuta) {
            estavaEmLuta = false
            executarAutoloot(tela.width, tela.height)
            ultimoClique = SystemClock.uptimeMillis()
            return "AUTOLOOT concluído"
        }

        val tamanho = modelos.size
        if (alvo >= tamanho) resetar()    // acabou a hunt: recomeça do primeiro
        val modelo = modelos[alvo]
        val indice = modelo.indice

        val cx = cruz.first.toFloat()
        val cy = cruz.second.toFloat()

        val achado = achar(tela, x0, y0, x1, y1, modelo)
        val acerto = "acerto=" + "%.2f".format(melhorScore)

        if (achado == null) {
            naoVisto++
            // ao chegar, o pino costuma sumir ou ficar escondido embaixo da cruz
            if (ultimaDistVista >= 0f && ultimaDistVista <= tela.width * Cal.CHEGOU_FRAC * 1.5f && naoVisto >= 3) {
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

    /** Monstro na lista de batalha = barra de vida verde (faixa horizontal larga) dentro da lista. */
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
        var y = 0
        while (y < rh) {
            var corrida = 0
            for (x in 0 until rw) {
                val p = px[y * rw + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (g > 140 && r < 110 && b < 110 && g - r > 50) {
                    corrida++
                    if (corrida >= Cal.BARRA_MIN) return true
                } else {
                    corrida = 0
                }
            }
            y += 2
        }
        return false
    }

    /**
     * Procura a cruz branca do personagem no minimapa (2 px de espessura, uns 18 px de
     * comprimento) e retorna o centro dela. Perto da borda do mapa ela sai do meio do
     * minimapa, por isso é procurada em toda a área. Retorna null se não achar.
     */
    fun acharCruz(tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Pair<Int, Int>? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 30 || rh < 30) return null
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        val branco = BooleanArray(rw * rh)
        for (i in px.indices) {
            val p = px[i]
            branco[i] = ((p shr 16) and 0xFF) > 200 && ((p shr 8) and 0xFF) > 200 && (p and 0xFF) > 200
        }
        var melhor = 0
        var bx = -1
        var by = -1
        for (y in 8 until rh - 10) {
            for (x in 8 until rw - 10) {
                // canto do cruzamento: bloco 2x2 branco
                if (!branco[y * rw + x] || !branco[y * rw + x + 1] ||
                    !branco[(y + 1) * rw + x] || !branco[(y + 1) * rw + x + 1]) continue
                var s = 0
                for (d in -8..9) {
                    if (branco[(y + d) * rw + x]) s++
                    if (branco[(y + d) * rw + x + 1]) s++
                    if (branco[y * rw + x + d]) s++
                    if (branco[(y + 1) * rw + x + d]) s++
                }
                if (s > melhor) {
                    melhor = s
                    bx = x
                    by = y
                }
            }
        }
        return if (melhor >= 62) Pair(x0 + bx + 1, y0 + by + 1) else null   // 62 de 72 pontos
    }

    /**
     * Procura o ícone na região do minimapa por correlação normalizada
     * (1,0 = igual; perto de 0 = sem relação). Guarda o melhor valor em melhorScore.
     * Retorna o centro do melhor encaixe, ou null se ficou abaixo do limiar.
     */
    private fun achar(tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, m: Modelo): Pair<Int, Int>? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < m.w || rh < m.h || m.norma < 1e-3) {
            melhorScore = 0.0
            return null
        }

        // lê a região uma vez só (getPixel pixel a pixel é lento demais)
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        val g = DoubleArray(rw * rh)
        for (i in px.indices) g[i] = cinza(px[i])

        var melhor = -2.0
        var bx = 0
        var by = 0
        var y = 0
        while (y <= rh - m.h) {
            var x = 0
            while (x <= rw - m.w) {
                val s = pontuar(g, rw, x, y, m)
                if (s > melhor) {
                    melhor = s
                    bx = x
                    by = y
                }
                x += 2
            }
            y += 2
        }
        // refina em volta do melhor ponto (a busca andou de 2 em 2 pixels)
        val cx = bx
        val cy = by
        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = cx + dx
                val yy = cy + dy
                if (x < 0 || yy < 0 || x > rw - m.w || yy > rh - m.h) continue
                val s = pontuar(g, rw, x, yy, m)
                if (s > melhor) {
                    melhor = s
                    bx = x
                    by = yy
                }
            }
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
