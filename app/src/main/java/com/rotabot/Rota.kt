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
    const val LIMIAR = 0.65f
    const val INTERVALO_CLIQUE = 1000L
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
    val valida = BooleanArray(w * h)
    val pixelsValidos: Int
    val norma: Double

    init {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var soma = 0.0
        var quantidade = 0
        for (i in px.indices) {
            val alpha = (px[i] ushr 24) and 0xFF
            // Pixels transparentes do PNG não fazem parte do desenho do pino.
            valida[i] = alpha >= 32
            if (valida[i]) {
                z[i] = cinza(px[i])
                soma += z[i]
                quantidade++
            }
        }
        pixelsValidos = quantidade
        val media = if (quantidade > 0) soma / quantidade else 0.0
        var q = 0.0
        for (i in z.indices) {
            if (valida[i]) {
                z[i] -= media
                q += z[i] * z[i]
            }
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
    private var confirmacoesFallback = 0
    private var ultimaDist = -1f
    private var semProgresso = 0
    private var naoVisto = 0
    private var ultimaDistVista = -1f
    private var estavaEmLuta = false
    private var tentativaDesvio = 0
    private var tempoInicioLuta = 0L
    // A cruz fica fixa no centro; o rastreamento acompanha o deslocamento do ícone.
    private var ultimoPinoVerdeRel: Pair<Int, Int>? = null
    private var quadrosDesdeDeteccao = 0
    private var velocidadePinoX = 0f
    private var velocidadePinoY = 0f
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
        confirmacoesFallback = 0
        ultimaDist = -1f
        semProgresso = 0
        naoVisto = 0
        ultimaDistVista = -1f
        estavaEmLuta = false
        tentativaDesvio = 0
        tempoInicioLuta = 0L
        ultimoPinoVerdeRel = null
        quadrosDesdeDeteccao = 0
        velocidadePinoX = 0f
        velocidadePinoY = 0f
        usandoFallbackVerde = false
        cliquesNoPinoVerde = 0
        BotService.pararAlarme()
    }

    private fun proximoPino() {
        val tamanho = modelos.size
        if (tamanho <= 0) return

        if (tamanho == 1) {
            alvo = 0
        } else if (direcaoIda) {
            if (alvo < tamanho - 1) alvo++
            else {
                direcaoIda = false
                alvo = (tamanho - 2).coerceAtLeast(0)
            }
        } else {
            if (alvo > 0) alvo--
            else {
                direcaoIda = true
                alvo = 1
            }
        }

        ultimoClique = 0L
        confirmacoes = 0
        confirmacoesFallback = 0
        ultimaDist = -1f
        semProgresso = 0
        naoVisto = 0
        ultimaDistVista = -1f
        tentativaDesvio = 0
        ultimoPinoVerdeRel = null
        quadrosDesdeDeteccao = 0
        velocidadePinoX = 0f
        velocidadePinoY = 0f
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
            // Ao sair da batalha, não espera o intervalo normal: o próximo ciclo
            // procura novamente o mesmo destino e pode clicar imediatamente.
            ultimoClique = 0L
            ultimaDist = -1f
            ultimaDistVista = -1f
            semProgresso = 0
            naoVisto = 0
            tentativaDesvio = 0
            confirmacoes = 0
            confirmacoesFallback = 0
            ultimoPinoVerdeRel = null
            quadrosDesdeDeteccao = 0
            velocidadePinoX = 0f
            velocidadePinoY = 0f
            usandoFallbackVerde = false
            cliquesNoPinoVerde = 0
            return if (usaLoot) "AUTOLOOT concluído - procurando o mesmo pino" else "MONSTRO MORREU - procurando o mesmo pino"
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
            if (naoVisto >= 30) return "PINO $indice NÃO LOCALIZADO - aguardando $acerto"
            return "PROCURANDO pino $indice (${alvo + 1}/$tamanho) $acerto"
        }
        naoVisto = 0

        val dist = Math.hypot((achado.first - cx).toDouble(), (achado.second - cy).toDouble()).toFloat()
        ultimaDistVista = dist

        val toleranciaChegada = tela.width * Cal.CHEGOU_FRAC
        if (dist <= toleranciaChegada) {
            if (usandoFallbackVerde) {
                val ateCruzX = cx - achado.first
                val ateCruzY = cy - achado.second
                val produto = velocidadePinoX * ateCruzX + velocidadePinoY * ateCruzY
                val velocidade = Math.hypot(velocidadePinoX.toDouble(), velocidadePinoY.toDouble())
                confirmacoesFallback++
                if (confirmacoesFallback >= 2 && quadrosDesdeDeteccao in 1..4 && produto > 0f && velocidade >= 0.5) {
                    proximoPino()
                    return "CHEGOU ao pino $indice (trajetória confirmada)"
                }
                return "PINO $indice SOB A CRUZ - confirmando trajetória"
            }
            confirmacoesFallback = 0
            confirmacoes++
            if (confirmacoes >= 4) {
                proximoPino()
                return "CHEGOU no pino $indice (leitura visual)"
            }
            return "CHEGANDO no pino $indice $acerto"
        }
        confirmacoes = 0
        confirmacoesFallback = 0

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

            // Primeira tentativa: centro do ícone. Se a distância não diminuir
            // após 1 segundo, percorre pequenas correções ao redor do mesmo alvo.
            if (semProgresso >= 1 && !usandoFallbackVerde) {
                val escala = (tela.width / 2460f).coerceAtLeast(0.5f)
                val d = 7f * escala
                when (tentativaDesvio % 8) {
                    0 -> alvoX += d
                    1 -> alvoX -= d
                    2 -> alvoY -= d
                    3 -> alvoY += d
                    4 -> { alvoX += d; alvoY -= d }
                    5 -> { alvoX -= d; alvoY -= d }
                    6 -> { alvoX += d; alvoY += d }
                    7 -> { alvoX -= d; alvoY += d }
                }
                tentativaDesvio++
                semProgresso = 1
            }

            tocar(alvoX, alvoY)
            ultimoClique = agora
            if (usandoFallbackVerde) {
                cliquesNoPinoVerde++
            }
        }

        if (semProgresso >= 6 || cliquesNoPinoVerde >= 12) {
            // Nunca pula automaticamente: um destino errado pode fazer a rota inteira se perder.
            semProgresso = 0
            cliquesNoPinoVerde = 0
            return "SEM PROGRESSO no pino $indice - aguardando nova leitura"
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
        if (rw < m.w || rh < m.h || m.norma < 1e-3 || m.pixelsValidos < 8) {
            melhorScore = 0.0
            return null
        }

        val cruzRelX = cruzAbsX - x0
        val cruzRelY = cruzAbsY - y0

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        val g = DoubleArray(rw * rh)
        val ocultoPelaCruz = BooleanArray(rw * rh)
        for (i in px.indices) {
            g[i] = cinza(px[i])
            val r = (px[i] shr 16) and 0xFF
            val verde = (px[i] shr 8) and 0xFF
            val blue = px[i] and 0xFF
            ocultoPelaCruz[i] = r >= 185 && verde >= 185 && blue >= 185
        }

        val centroCruzX = cruzRelX.toInt()
        val centroCruzY = cruzRelY.toInt()
        val meiaLarguraCruz = (m.w * 0.12f).toInt().coerceAtLeast(2)
        val alcanceCruz = (m.h * 0.43f).toInt().coerceAtLeast(7)
        for (dy in -alcanceCruz..alcanceCruz) {
            for (dx in -alcanceCruz..alcanceCruz) {
                val dentroDaCruz = kotlin.math.abs(dx) <= meiaLarguraCruz || kotlin.math.abs(dy) <= meiaLarguraCruz
                if (!dentroDaCruz) continue
                val xx = centroCruzX + dx
                val yy = centroCruzY + dy
                if (xx in 0 until rw && yy in 0 until rh) ocultoPelaCruz[yy * rw + xx] = true
            }
        }

        quadrosDesdeDeteccao++
        val ref = ultimoPinoVerdeRel
        val rastreamentoValido = ref != null && quadrosDesdeDeteccao in 1..12
        val quadrosPrevistos = if (rastreamentoValido) quadrosDesdeDeteccao else 0
        val previstoX = if (rastreamentoValido) ref!!.first + (velocidadePinoX * quadrosPrevistos).toInt() else -1
        val previstoY = if (rastreamentoValido) ref!!.second + (velocidadePinoY * quadrosPrevistos).toInt() else -1
        val raio = (maxOf(m.w, m.h) * 5).coerceAtLeast(45)

        var melhor = -2.0
        var bx = 0
        var by = 0
        var y = 0
        while (y <= rh - m.h) {
            var x = 0
            while (x <= rw - m.w) {
                if (!rastreamentoValido || Math.hypot((x + m.w / 2 - previstoX).toDouble(), (y + m.h / 2 - previstoY).toDouble()) <= raio) {
                    val score = pontuar(g, ocultoPelaCruz, rw, x, y, m)
                    if (score > melhor) {
                        melhor = score
                        bx = x
                        by = y
                    }
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
                val s = pontuar(g, ocultoPelaCruz, rw, x, yy, m)
                if (s > melhor) {
                    melhor = s
                    bx = x
                    by = yy
                }
            }
        }

        melhorScore = melhor
        if (melhor >= Cal.LIMIAR) {
            // Antes de aceitar o candidato, compara o mesmo recorte com os outros
            // números carregados. Se outro modelo explicar melhor a imagem, não
            // assume que encontrou o destino atual.
            var melhorOutroModelo = -2.0
            for (outro in modelos) {
                if (outro.indice == m.indice || outro.w != m.w || outro.h != m.h) continue
                val scoreOutro = pontuar(g, ocultoPelaCruz, rw, bx, by, outro)
                if (scoreOutro > melhorOutroModelo) melhorOutroModelo = scoreOutro
            }
            val margemIdentidade = 0.035
            if (melhorOutroModelo > melhor + margemIdentidade) {
                melhorScore = melhor
                usandoFallbackVerde = false
                return null
            }

            usandoFallbackVerde = false
            val relPos = Pair(bx + m.w / 2, by + m.h / 2)
            val anterior = ultimoPinoVerdeRel
            if (anterior != null && rastreamentoValido) {
                val intervalo = quadrosDesdeDeteccao.coerceAtLeast(1)
                val dx = relPos.first - anterior.first
                val dy = relPos.second - anterior.second
                val limiteSalto = (maxOf(m.w, m.h) * 5).coerceAtLeast(45)
                if (Math.hypot(dx.toDouble(), dy.toDouble()) <= limiteSalto) {
                    velocidadePinoX = (dx.toFloat() / intervalo).coerceIn(-30f, 30f)
                    velocidadePinoY = (dy.toFloat() / intervalo).coerceIn(-30f, 30f)
                } else {
                    velocidadePinoX = 0f
                    velocidadePinoY = 0f
                }
            } else {
                velocidadePinoX = 0f
                velocidadePinoY = 0f
            }
            ultimoPinoVerdeRel = relPos
            quadrosDesdeDeteccao = 0
            return Pair(x0 + relPos.first, y0 + relPos.second)
        }

        // Se o marcador mudou de cor/estado após o clique, acompanha somente
        // a posição previamente reconhecida, compensando o deslocamento do minimapa.
        // Não procura "qualquer verde" no mapa: isso confundia o destino com outros pinos.
        val referencia = ultimoPinoVerdeRel
        if (referencia != null && quadrosDesdeDeteccao in 1..12) {
            val previstoX = referencia.first + (velocidadePinoX * quadrosDesdeDeteccao).toInt()
            val previstoY = referencia.second + (velocidadePinoY * quadrosDesdeDeteccao).toInt()
            if (previstoX in 0 until rw && previstoY in 0 until rh) {
                usandoFallbackVerde = true
                melhorScore = 0.0
                return Pair(x0 + previstoX, y0 + previstoY)
            }
        }

        if (ref != null && !rastreamentoValido) {
            ultimoPinoVerdeRel = null
            quadrosDesdeDeteccao = 0
            velocidadePinoX = 0f
            velocidadePinoY = 0f
            cliquesNoPinoVerde = 0
        }
        usandoFallbackVerde = false
        melhorScore = melhor.coerceAtLeast(0.0)
        return null
    }

    private fun pontuar(
        g: DoubleArray,
        ocultoPelaCruz: BooleanArray,
        rw: Int,
        x: Int,
        y: Int,
        m: Modelo
    ): Double {
        // Correlação normalizada com máscara: ignora os pixels brancos da cruz
        // que fica por cima do ícone quando o personagem chega ao destino.
        var somaImagem = 0.0
        var somaModelo = 0.0
        var quantidade = 0
        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val baseModelo = yy * m.w
            for (xx in 0 until m.w) {
                val pos = base + xx
                val indiceModelo = baseModelo + xx
                if (ocultoPelaCruz[pos] || !m.valida[indiceModelo]) continue
                somaImagem += g[pos]
                somaModelo += m.z[indiceModelo]
                quantidade++
            }
        }

        val total = m.pixelsValidos
        // Compara apenas pixels realmente desenhados no PNG e ainda visíveis na tela.
        if (quantidade < total * 0.55) return -1.0

        val mediaImagem = somaImagem / quantidade
        val mediaModelo = somaModelo / quantidade
        var covariancia = 0.0
        var varianciaImagem = 0.0
        var varianciaModelo = 0.0

        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val baseModelo = yy * m.w
            for (xx in 0 until m.w) {
                val pos = base + xx
                val indiceModelo = baseModelo + xx
                if (ocultoPelaCruz[pos] || !m.valida[indiceModelo]) continue
                val a = g[pos] - mediaImagem
                val b = m.z[indiceModelo] - mediaModelo
                covariancia += a * b
                varianciaImagem += a * a
                varianciaModelo += b * b
            }
        }

        if (varianciaImagem < 50.0 || varianciaModelo < 50.0) return -1.0
        return covariancia / (Math.sqrt(varianciaImagem) * Math.sqrt(varianciaModelo))
    }
}
