package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock

object Cal {
    const val MINIMAPA_X0 = 0.848f
    const val MINIMAPA_Y0 = 0.135f
    const val MINIMAPA_X1 = 0.996f
    const val MINIMAPA_Y1 = 0.615f

    const val BATALHA_X0 = 0.045f
    const val BATALHA_Y0 = 0.203f
    const val BATALHA_X1 = 0.181f
    const val BATALHA_Y1 = 0.392f

    const val CHEGOU_FRAC = 0.015f
    const val LIMIAR = 0.78f
    const val INTERVALO_CLIQUE = 1500L
    const val TOTAL_ICONES = 20
    const val LADO_ICONE = 26

    val PONTOS_CORPO = listOf(
        Pair(0.4890f, 0.5011f), Pair(0.4880f, 0.6082f),
        Pair(0.5270f, 0.6082f), Pair(0.5660f, 0.5968f),
        Pair(0.5650f, 0.4989f), Pair(0.4890f, 0.4077f),
        Pair(0.5260f, 0.3964f), Pair(0.5680f, 0.3986f)
    )

    val PONTOS_LOOT = listOf(
        Pair(0.5403f, 0.3351f), Pair(0.7027f, 0.1635f),
        Pair(0.6994f, 0.3454f), Pair(0.5357f, 0.1677f)
    )
}

private fun cinza(p: Int): Double =
    ((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114

// Verde puro do corpo do pino ativo (R e B baixos, G forte), evitando tons amarelados/limao do terreno
private fun ehVerdePinoAtivo(p: Int): Boolean {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return g >= 150 && r < 55 && b < 55
}

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
    private var alvo = 0
    private var direcaoIda = true
    private var ultimoClique = 0L
    private var confirmacoes = 0
    private var ultimaDist = -1f
    private var semProgresso = 0
    private var naoVisto = 0
    private var ultimaDistVista = -1f
    private var estavaEmLuta = false
    private var tentativaDesvio = 0
    private var tempoInicioLuta = 0L
    private var ultimoPinoVerdeRel: Pair<Int, Int>? = null
    private var ultimaCruzRel: Pair<Float, Float>? = null
    private var usandoFallbackVerde = false
    private var cliquesNoPinoVerde = 0
    @Volatile private var melhorScore = 0.0

    fun carregar(ctx: Context, inicio: Int, fim: Int, larguraTela: Int) {
        val lado = (Cal.LADO_ICONE * larguraTela / 2460f).toInt().coerceAtLeast(8)
        val lista = ArrayList<Modelo>()
        for (n in inicio..fim) {
            val raw = ctx.assets.open("pins/p%02d.png".format(n)).use { BitmapFactory.decodeStream(it) } ?: continue
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
        direcaoIda = true
        ultimoClique = 0L
        confirmacoes = 0
        ultimaDist = -1f
        semProgresso = 0
        naoVisto = 0
        ultimaDistVista = -1f
        estavaEmLuta = false
        tentativaDesvio = 0
        tempoInicioLuta = 0L
        ultimoPinoVerdeRel = null
        ultimaCruzRel = null
        usandoFallbackVerde = false
        cliquesNoPinoVerde = 0
        BotService.pararAlarme()
    }

    private fun proximoPino() {
        val tamanho = modelos.size
        if (tamanho <= 0) return

        if (direcaoIda) {
            if (alvo < tamanho - 1) alvo++
            else {
                direcaoIda = false
                alvo = tamanho - 2.coerceAtLeast(0)
            }
        } else {
            if (alvo > 0) alvo--
            else {
                direcaoIda = true
                alvo = 1.coerceAtMost(tamanho - 1)
            }
        }

        ultimoClique = 0L
        confirmacoes = 0
        ultimaDist = -1f
        semProgresso = 0
        naoVisto = 0
        ultimaDistVista = -1f
        tentativaDesvio = 0
        ultimoPinoVerdeRel = null
        ultimaCruzRel = null
        usandoFallbackVerde = false
        cliquesNoPinoVerde = 0
    }

    private fun executarAutoloot(larguraTela: Int, alturaTela: Int) {
        SystemClock.sleep(1000L)
        for (ponto in Cal.PONTOS_CORPO) {
            val px = ponto.first * larguraTela
            val py = ponto.second * alturaTela
            ToqueService.clicar(px, py, 30L)
            SystemClock.sleep(60L)
            ToqueService.clicar(px, py, 30L)
            SystemClock.sleep(100L)
        }
        SystemClock.sleep(250L)
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

    fun passo(
        tela: Bitmap,
        context: Context,
        usaLoot: Boolean,
        usaAlerta: Boolean,
        uriSom: String,
        volume: Int,
        tocar: (Float, Float) -> Unit
    ): String {
        if (tela.height > tela.width) return "CELULAR EM RETRATO (pausado)"
        if (modelos.isEmpty()) return "SEM ÍCONES CARREGADOS"

        val x0 = (tela.width * Cal.MINIMAPA_X0).toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.MINIMAPA_Y0).toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.MINIMAPA_X1).toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.MINIMAPA_Y1).toInt().coerceIn(y0 + 1, tela.height)

        val cruz = acharCruz(tela, x0, y0, x1, y1) ?: return "SEM CRUZ NO MINIMAPA (pausado)"

        if (temMonstro(tela)) {
            val agora = SystemClock.uptimeMillis()
            if (!estavaEmLuta) {
                estavaEmLuta = true
                tempoInicioLuta = agora
            } else if (usaAlerta && (agora - tempoInicioLuta >= 15000L)) {
                BotService.tocarAlarme(context, uriSom, volume)
                return "ALERTA: Batalha > 15s (Player?)"
            }
            return "LUTA (pausado)"
        }

        BotService.pararAlarme()

        if (estavaEmLuta) {
            estavaEmLuta = false
            tempoInicioLuta = 0L
            if (usaLoot) {
                executarAutoloot(tela.width, tela.height)
            }
            ultimoClique = SystemClock.uptimeMillis()
            return if (usaLoot) "AUTOLOOT concluído" else "MONSTRO MORREU (Retornando)"
        }

        val tamanho = modelos.size
        if (alvo < 0 || alvo >= tamanho) resetar()
        val modelo = modelos[alvo]
        val indice = modelo.indice

        val cx = cruz.first.toFloat()
        val cy = cruz.second.toFloat()

        val achado = achar(tela, x0, y0, x1, y1, modelo, cx, cy)
        val acerto = "acerto=" + "%.2f".format(melhorScore)

        if (achado == null) {
            naoVisto++
            if (ultimaDistVista >= 0f && ultimaDistVista <= tela.width * Cal.CHEGOU_FRAC * 1.5f && naoVisto >= 3) {
                proximoPino()
                return "CHEGOU no pino $indice (pino sumiu)"
            }
            if (naoVisto >= 30) return "PINO $indice FORA DO MINIMAPA? $acerto"
            return "PROCURANDO pino $indice (${alvo + 1}/$tamanho) $acerto"
        }
        naoVisto = 0

        val dist = Math.hypot((achado.first - cx).toDouble(), (achado.second - cy).toDouble()).toFloat()
        ultimaDistVista = dist

        val toleranciaChegada = if (usandoFallbackVerde) tela.width * Cal.CHEGOU_FRAC * 1.35f else tela.width * Cal.CHEGOU_FRAC
        if (dist <= toleranciaChegada) {
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
            if (ultimaDist >= 0f && dist >= ultimaDist - tela.width * 0.003f) {
                semProgresso++
            } else {
                semProgresso = 0
                tentativaDesvio = 0
            }
            ultimaDist = dist

            var alvoX = achado.first.toFloat()
            var alvoY = achado.second.toFloat()

            if (semProgresso >= 4 && !usandoFallbackVerde) {
                val desvioAjustado = 6f
                when (tentativaDesvio % 4) {
                    0 -> alvoX += desvioAjustado
                    1 -> alvoY -= desvioAjustado
                    2 -> alvoX -= desvioAjustado
                    3 -> alvoY += desvioAjustado
                }
                tentativaDesvio++
                semProgresso = 2
            }

            tocar(alvoX, alvoY)
            ultimoClique = agora
            if (usandoFallbackVerde) {
                cliquesNoPinoVerde++
            }
        }

        val limiteSemProgresso = if (usandoFallbackVerde) 9 else 6
        if (semProgresso >= limiteSemProgresso || cliquesNoPinoVerde >= 12) {
            proximoPino()
            return "PULANDO pino $indice (travado)"
        }

        val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
        return if (tentativaDesvio > 0) "DESVIANDO pino $indice [$sentidoStr] $acerto"
               else "INDO ao pino $indice (${alvo + 1}/$tamanho) [$sentidoStr] $acerto"
    }

    fun temMonstro(tela: Bitmap): Boolean {
        val x0 = (tela.width * Cal.BATALHA_X0).toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.BATALHA_Y0).toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.BATALHA_X1).toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.BATALHA_Y1).toInt().coerceIn(y0 + 1, tela.height)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return false
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        var pixelsDiferentes = 0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val ehFundoEscuro = (r <= 25 && g <= 30 && b >= 20 && b <= 50)
            if (!ehFundoEscuro) {
                pixelsDiferentes++
                if (pixelsDiferentes >= 120) return true
            }
        }
        return false
    }

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
        return if (melhor >= 62) Pair(x0 + bx + 1, y0 + by + 1) else null
    }

    private fun acharBlocoPinoVerde(
        px: IntArray,
        rw: Int,
        rh: Int,
        mw: Int,
        mh: Int,
        cruzRelX: Float,
        cruzRelY: Float
    ): Pair<Int, Int>? {
        val esperadoX: Double
        val esperadoY: Double
        val temRef = ultimoPinoVerdeRel != null && ultimaCruzRel != null
        if (temRef) {
            val dxCruz = cruzRelX - ultimaCruzRel!!.first
            val dyCruz = cruzRelY - ultimaCruzRel!!.second
            esperadoX = ultimoPinoVerdeRel!!.first - dxCruz
            esperadoY = ultimoPinoVerdeRel!!.second - dyCruz
        } else {
            esperadoX = -1.0
            esperadoY = -1.0
        }

        val raioBusca = Math.max(mw, mh) * 3.0
        var melhorScoreVerde = -1.0
        var melhorContagem = 0
        var bx = -1
        var by = -1

        var y = 0
        while (y <= rh - mh) {
            var x = 0
            while (x <= rw - mw) {
                var verde = 0
                for (yy in 0 until mh) {
                    val base = (y + yy) * rw + x
                    for (xx in 0 until mw) {
                        if (ehVerdePinoAtivo(px[base + xx])) verde++
                    }
                }
                if (verde >= 25) {
                    val cx = x + mw / 2
                    val cy = y + mh / 2
                    var score = verde.toDouble()
                    if (temRef) {
                        val distEsperado = Math.hypot(cx - esperadoX, cy - esperadoY)
                        if (distEsperado > raioBusca) {
                            score *= 0.25
                        } else {
                            score += (1.0 - distEsperado / raioBusca) * 40.0
                        }
                    }
                    if (score > melhorScoreVerde) {
                        melhorScoreVerde = score
                        melhorContagem = verde
                        bx = cx
                        by = cy
                    }
                }
                x += 2
            }
            y += 2
        }

        if (bx >= 0 && by >= 0) {
            val area = (mw * mh).coerceAtLeast(1)
            val frac = (melhorContagem.toDouble() / area.toDouble()).coerceIn(0.0, 1.0)
            melhorScore = (0.78 + frac * 0.17).coerceIn(0.78, 0.95)
            return Pair(bx, by)
        }
        return null
    }

    private fun achar(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        m: Modelo,
        cruzAbsX: Float,
        cruzAbsY: Float
    ): Pair<Int, Int>? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < m.w || rh < m.h || m.norma < 1e-3) {
            melhorScore = 0.0
            return null
        }

        val cruzRelX = cruzAbsX - x0
        val cruzRelY = cruzAbsY - y0

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
        if (melhor >= Cal.LIMIAR) {
            usandoFallbackVerde = false
            val relPos = Pair(bx + m.w / 2, by + m.h / 2)
            ultimoPinoVerdeRel = relPos
            ultimaCruzRel = Pair(cruzRelX, cruzRelY)
            return Pair(x0 + relPos.first, y0 + relPos.second)
        }

        val verdePos = acharBlocoPinoVerde(px, rw, rh, m.w, m.h, cruzRelX, cruzRelY)
        if (verdePos != null) {
            usandoFallbackVerde = true
            ultimoPinoVerdeRel = verdePos
            ultimaCruzRel = Pair(cruzRelX, cruzRelY)
            return Pair(x0 + verdePos.first, y0 + verdePos.second)
        }

        usandoFallbackVerde = false
        return null
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
        if (variancia < 50.0) return -1.0
        return sc / (Math.sqrt(variancia) * m.norma)
    }
}
