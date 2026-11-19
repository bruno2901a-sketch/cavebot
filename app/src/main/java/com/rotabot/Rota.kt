package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

object Cal {
    const val LADO_ICONE = 26
    const val LIMIAR = 0.62f
    const val CHEGOU_FRAC = 0.018f
    const val INTERVALO_CLIQUE = 1000L
    const val TOTAL_ICONES = 20
    const val MINIMAPA_X0 = 0.848f
    const val MINIMAPA_Y0 = 0.135f
    const val MINIMAPA_X1 = 0.996f
    const val MINIMAPA_Y1 = 0.615f
    const val BATALHA_X0 = 0.045f
    const val BATALHA_Y0 = 0.203f
    const val BATALHA_X1 = 0.181f
    const val BATALHA_Y1 = 0.392f
    const val SEM_ROTA_X0 = 0.464f
    const val SEM_ROTA_Y0 = 0.738f
    const val SEM_ROTA_X1 = 0.559f
    const val SEM_ROTA_Y1 = 0.827f
    const val MAX_FALHAS_PINO = 3
    const val MAX_LUTA_MS = 20000L
    const val IGNORAR_LUTA_MS = 4000L
    const val IGNORAR_SEM_ROTA_MS = 2500L
    const val NAO_VISTO_LIMITE = 12
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

private enum class FaseNavegacao {
    ESCOLHER,
    CAMINHANDO
}

private data class DeteccaoVerde(
    val posicao: Pair<Int, Int>,
    val quantidade: Int,
    val rgbMedio: Triple<Int, Int, Int>
)

private fun ehVerdePinoAtivo(p: Int): Boolean {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return g >= 170 && r < 90 && b < 90
}

object Rota {
    private var modelos: List<Modelo> = emptyList()
    private var fase = FaseNavegacao.ESCOLHER
    private var alvo = 0
    private var direcaoIda = true
    private var ultimoClique = 0L
    private var iniciouCaminhadaEm = 0L
    private var pausadoAte = 0L
    private var quadrosChegada = 0
    private var quadrosPertoDaCruz = 0
    private var quadrosSumidoVerde = 0
    private var ultimaDist = -1f
    private var ultimaDistVerde = -1f
    private var menorDistVerde = -1f
    private var semProgresso = 0
    private var cliquesVerde = 0
    private var naoVisto = 0
    private var falhasPino = 0
    private var quadrosQuaseLimiar = 0
    private var ultimoCandidatoQuaseLimiar: Pair<Int, Int>? = null
    private var candidatoConsistente: Pair<Int, Int>? = null
    private var quadrosSemRota = 0
    private var ignorarSemRotaAte = 0L
    private var quadrosMonstro = 0
    private var ignorarLutaAte = 0L
    private var estavaEmLuta = false
    private var tempoInicioLuta = 0L
    private var estavaEmLutaCaminhando = false
    private var verdeRelAntesDaLuta: Pair<Int, Int>? = null
    private var retomarVerdeAte = 0L
    private var verdeVeioDeTemplate = false
    private var ultimoVerdeRel: Pair<Int, Int>? = null
    private var ultimoQtdVerde = 0
    private var ultimoRgbVerde: Triple<Int, Int, Int>? = null
    private var vistoVerde = false
    private var indiceCliqueVariavel = 0

    @Volatile
    private var melhorScore = 0.0

    fun carregar(ctx: Context, inicio: Int, fim: Int, larguraTela: Int) {
        val lado = (Cal.LADO_ICONE * larguraTela / 2460f)
            .toInt().coerceAtLeast(8)

        val lista = ArrayList<Modelo>()

        for (n in inicio..fim) {
            val raw = ctx.assets.open("pins/p%02d.png".format(n))
                .use { BitmapFactory.decodeStream(it) } ?: continue

            val bmp = if (raw.width == lado && raw.height == lado) {
                raw
            } else {
                Bitmap.createScaledBitmap(raw, lado, lado, true)
            }

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

    private fun dispararToqueSeguro(
        larguraTela: Int,
        alturaTela: Int,
        x: Float,
        y: Float,
        tocar: (Float, Float) -> Unit
    ): Pair<Float, Float> {
        val minX = 28f.coerceAtMost((larguraTela - 1).toFloat())
        val maxX = (larguraTela - 28f).coerceAtLeast(minX)
        val minY = 28f.coerceAtMost((alturaTela - 1).toFloat())
        val maxY = (alturaTela - 28f).coerceAtLeast(minY)
        val sx = x.coerceIn(minX, maxX)
        val sy = y.coerceIn(minY, maxY)
        tocar(sx, sy)
        return Pair(sx, sy)
    }

    fun resetar() {
        fase = FaseNavegacao.ESCOLHER
        alvo = 0
        direcaoIda = true
        ultimoClique = 0L
        iniciouCaminhadaEm = 0L
        pausadoAte = 0L
        quadrosChegada = 0
        quadrosPertoDaCruz = 0
        quadrosSumidoVerde = 0
        ultimaDist = -1f
        ultimaDistVerde = -1f
        menorDistVerde = -1f
        semProgresso = 0
        cliquesVerde = 0
        naoVisto = 0
        falhasPino = 0
        quadrosQuaseLimiar = 0
        ultimoCandidatoQuaseLimiar = null
        candidatoConsistente = null
        quadrosSemRota = 0
        ignorarSemRotaAte = 0L
        quadrosMonstro = 0
        ignorarLutaAte = 0L
        estavaEmLuta = false
        tempoInicioLuta = 0L
        estavaEmLutaCaminhando = false
        verdeRelAntesDaLuta = null
        retomarVerdeAte = 0L
        verdeVeioDeTemplate = false
        ultimoVerdeRel = null
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
        melhorScore = 0.0
        BotService.pararAlarme()
    }

    private fun iniciarCaminhada(
        relPos: Pair<Int, Int>,
        distAtual: Float,
        agora: Long,
        veioDeTemplate: Boolean
    ) {
        fase = FaseNavegacao.CAMINHANDO
        ultimoVerdeRel = relPos
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
        verdeVeioDeTemplate = veioDeTemplate
        ultimaDistVerde = distAtual
        menorDistVerde = distAtual
        ultimaDist = distAtual
        iniciouCaminhadaEm = agora
        quadrosChegada = 0
        quadrosPertoDaCruz = 0
        quadrosSumidoVerde = 0
        quadrosSemRota = 0
        semProgresso = 0
        cliquesVerde = 0
        quadrosQuaseLimiar = 0
        ultimoCandidatoQuaseLimiar = null
        candidatoConsistente = null
    }

    private fun proximoPino(agora: Long) {
        val tamanho = modelos.size
        if (tamanho <= 0) return

        if (tamanho == 1) {
            alvo = 0
        } else if (direcaoIda) {
            if (alvo < tamanho - 1) {
                alvo++
            } else {
                direcaoIda = false
                alvo = (tamanho - 2).coerceAtLeast(0)
            }
        } else {
            if (alvo > 0) {
                alvo--
            } else {
                direcaoIda = true
                alvo = 1
            }
        }

        fase = FaseNavegacao.ESCOLHER
        ultimoClique = 0L
        iniciouCaminhadaEm = 0L
        pausadoAte = agora + 900L
        quadrosChegada = 0
        quadrosPertoDaCruz = 0
        quadrosSumidoVerde = 0
        ultimaDist = -1f
        ultimaDistVerde = -1f
        menorDistVerde = -1f
        semProgresso = 0
        cliquesVerde = 0
        naoVisto = 0
        falhasPino = 0
        quadrosQuaseLimiar = 0
        ultimoCandidatoQuaseLimiar = null
        candidatoConsistente = null
        quadrosSemRota = 0
        ignorarSemRotaAte = 0L
        estavaEmLutaCaminhando = false
        verdeRelAntesDaLuta = null
        retomarVerdeAte = 0L
        verdeVeioDeTemplate = false
        ultimoVerdeRel = null
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
    }

    private fun registrarFalhaPino(
        agora: Long,
        faseNome: String,
        pinoId: Int,
        score: Double,
        qtdVerde: Int,
        rgbVerde: Triple<Int, Int, Int>?,
        distPx: Float,
        progressoAntesReset: Int,
        motivo: String
    ): String {
        falhasPino++
        if (falhasPino >= Cal.MAX_FALHAS_PINO) {
            val logAvanco = formatarLog(
                faseNome, pinoId, score, qtdVerde, rgbVerde, distPx, progressoAntesReset,
                "$motivo (falhas=$falhasPino -> proximo pino)"
            )
            proximoPino(agora)
            return logAvanco
        }

        fase = FaseNavegacao.ESCOLHER
        retomarVerdeAte = 0L
        verdeVeioDeTemplate = false
        semProgresso = 0
        cliquesVerde = 0
        quadrosChegada = 0
        quadrosSumidoVerde = 0
        quadrosSemRota = 0
        ultimaDist = -1f
        ultimaDistVerde = -1f
        menorDistVerde = -1f
        ultimoVerdeRel = null
        vistoVerde = false
        return formatarLog(
            faseNome, pinoId, score, qtdVerde, rgbVerde, distPx, progressoAntesReset,
            "$motivo (falhas=$falhasPino/${Cal.MAX_FALHAS_PINO})"
        )
    }

    private fun temSemRota(tela: Bitmap): Boolean {
        val x0 = (tela.width * Cal.SEM_ROTA_X0)
            .toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.SEM_ROTA_Y0)
            .toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.SEM_ROTA_X1)
            .toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.SEM_ROTA_Y1)
            .toInt().coerceIn(y0 + 1, tela.height)

        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return false

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        var pixelsTextoClaro = 0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val ehTextoMensagem = r >= 195 && g >= 195 && b >= 200 && (b - r) in -5..25
            if (ehTextoMensagem) {
                pixelsTextoClaro++
            }
        }
        return pixelsTextoClaro in 40..650
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

    private fun formatarLog(
        faseNome: String,
        pinoId: Int,
        score: Double,
        qtdVerde: Int,
        rgbVerde: Triple<Int, Int, Int>?,
        distPx: Float,
        progresso: Int,
        extra: String? = null
    ): String {
        val distStr = if (distPx >= 0f) "%.1fpx".format(distPx) else "N/A"
        val scoreStr = "%.2f".format(score)
        val rgbStr = if (rgbVerde != null) {
            "rgb(${rgbVerde.first},${rgbVerde.second},${rgbVerde.third})"
        } else {
            "rgb(0,0,0)"
        }
        val base = "fase=$faseNome | pino=$pinoId | acerto=$scoreStr | verde=$qtdVerde | $rgbStr | dist=$distStr | semProgresso=$progresso"
        return if (extra != null && extra.isNotEmpty()) "$base | $extra" else base
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
        val texto = passoInterno(tela, context, usaLoot, usaAlerta, uriSom, volume, tocar)
        LogRota.registrarPasso(context, texto, tela)
        return texto
    }

    private fun passoInterno(
        tela: Bitmap,
        context: Context,
        usaLoot: Boolean,
        usaAlerta: Boolean,
        uriSom: String,
        volume: Int,
        tocar: (Float, Float) -> Unit
    ): String {
        if (tela.height > tela.width) {
            return "CELULAR EM RETRATO (pausado)"
        }

        if (modelos.isEmpty()) {
            return "SEM ÍCONES CARREGADOS"
        }

        val x0 = (tela.width * Cal.MINIMAPA_X0)
            .toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.MINIMAPA_Y0)
            .toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.MINIMAPA_X1)
            .toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.MINIMAPA_Y1)
            .toInt().coerceIn(y0 + 1, tela.height)

        val cruz = acharCruz(tela, x0, y0, x1, y1)
            ?: return "SEM CRUZ NO MINIMAPA (pausado)"

        val agoraLuta = SystemClock.uptimeMillis()
        val monstroDetectado = if (agoraLuta >= ignorarLutaAte && temMonstro(tela)) {
            quadrosMonstro++
            quadrosMonstro >= 2
        } else {
            quadrosMonstro = 0
            false
        }

        if (monstroDetectado) {
            val agora = agoraLuta

            if (!estavaEmLuta) {
                estavaEmLuta = true
                tempoInicioLuta = agora
                estavaEmLutaCaminhando = (fase == FaseNavegacao.CAMINHANDO)
                verdeRelAntesDaLuta = ultimoVerdeRel
            } else {
                val duracaoLuta = agora - tempoInicioLuta
                if (duracaoLuta >= Cal.MAX_LUTA_MS) {
                    if (usaAlerta) {
                        BotService.tocarAlarme(context, uriSom, volume)
                    }
                    estavaEmLuta = false
                    tempoInicioLuta = 0L
                    estavaEmLutaCaminhando = false
                    verdeRelAntesDaLuta = null
                    quadrosMonstro = 0
                    ignorarLutaAte = agora + Cal.IGNORAR_LUTA_MS
                    val pinoAtual = if (alvo in modelos.indices) modelos[alvo].indice else 0
                    return registrarFalhaPino(
                        agora, fase.name, pinoAtual, melhorScore,
                        ultimoQtdVerde, ultimoRgbVerde, ultimaDistVerde, semProgresso,
                        "luta excedeu limite"
                    )
                }
                if (usaAlerta && duracaoLuta >= 15000L) {
                    BotService.tocarAlarme(context, uriSom, volume)
                    return "ALERTA: Batalha > 15s (Player?)"
                }
            }

            return "LUTA (pausado)"
        }

        if (agoraLuta >= ignorarLutaAte) {
            BotService.pararAlarme()
        }

        if (estavaEmLuta) {
            val agora = SystemClock.uptimeMillis()
            val veioDeCaminhando = estavaEmLutaCaminhando
            val refVerde = verdeRelAntesDaLuta

            estavaEmLuta = false
            tempoInicioLuta = 0L
            estavaEmLutaCaminhando = false
            verdeRelAntesDaLuta = null
            quadrosMonstro = 0

            if (usaLoot) {
                executarAutoloot(tela.width, tela.height)
            }

            ultimoClique = 0L
            pausadoAte = 0L
            quadrosChegada = 0
            quadrosSumidoVerde = 0
            ultimaDist = -1f
            ultimaDistVerde = -1f
            menorDistVerde = -1f
            semProgresso = 0
            cliquesVerde = 0
            naoVisto = 0

            if (veioDeCaminhando && refVerde != null) {
                if (buscarComponentesVerdes(tela, x0, y0, x1, y1).isEmpty()) {
                    fase = FaseNavegacao.ESCOLHER
                    retomarVerdeAte = 0L
                    verdeVeioDeTemplate = false
                    ultimoVerdeRel = null
                    vistoVerde = false
                    falhasPino = 0
                    return if (usaLoot) "AUTOLOOT concluído - sem verde apos luta - ESCOLHER" else "MONSTRO MORREU - sem verde apos luta - ESCOLHER"
                }
                fase = FaseNavegacao.CAMINHANDO
                ultimoVerdeRel = refVerde
                retomarVerdeAte = agora + 2000L
                iniciouCaminhadaEm = agora
                return if (usaLoot) {
                    "AUTOLOOT concluído - retomando verde"
                } else {
                    "MONSTRO MORREU - retomando verde"
                }
            }

            fase = FaseNavegacao.ESCOLHER
            retomarVerdeAte = 0L
            verdeVeioDeTemplate = false
            ultimoVerdeRel = null
            ultimoQtdVerde = 0
            ultimoRgbVerde = null
            vistoVerde = false

            return if (usaLoot) {
                "AUTOLOOT concluído - procurando pino"
            } else {
                "MONSTRO MORREU - procurando pino"
            }
        }

        val tamanho = modelos.size
        if (alvo < 0 || alvo >= tamanho) {
            resetar()
        }

        val modelo = modelos[alvo]
        val indice = modelo.indice
        val cx = cruz.first.toFloat()
        val cy = cruz.second.toFloat()
        val toleranciaChegada = tela.width * Cal.CHEGOU_FRAC

        val agora = SystemClock.uptimeMillis()
        if (agora < pausadoAte) {
            return formatarLog(
                fase.name, indice, melhorScore, ultimoQtdVerde, ultimoRgbVerde, ultimaDistVerde, semProgresso, "estabilizando"
            )
        }

        if (fase == FaseNavegacao.CAMINHANDO) {
            if (agora - iniciouCaminhadaEm < 500L) {
                return formatarLog(
                    fase.name, indice, melhorScore, ultimoQtdVerde, ultimoRgbVerde, ultimaDistVerde, semProgresso, "aguardando pino ficar verde"
                )
            }

            if (agora >= ignorarSemRotaAte && temSemRota(tela)) {
                quadrosSemRota++
                if (quadrosSemRota >= 2) {
                    quadrosSemRota = 0
                    ignorarSemRotaAte = agora + Cal.IGNORAR_SEM_ROTA_MS
                    return registrarFalhaPino(
                        agora, fase.name, indice, melhorScore, ultimoQtdVerde, ultimoRgbVerde,
                        ultimaDistVerde, semProgresso, "nao ha rota"
                    )
                }
            } else {
                quadrosSemRota = 0
            }

            val janelaTripla = retomarVerdeAte > 0L && agora <= retomarVerdeAte
            val detVerde = if (janelaTripla) {
                acharPinoVerdeJanelaTripla(tela, x0, y0, x1, y1, modelo, ultimoVerdeRel)
            } else {
                acharPinoVerdeLocal(tela, x0, y0, x1, y1, modelo, ultimoVerdeRel)
            }

            if (detVerde != null) {
                val verdeRel = detVerde.posicao
                val qtdVerde = detVerde.quantidade
                val rgbVerde = detVerde.rgbMedio
                vistoVerde = true
                retomarVerdeAte = 0L
                ultimoQtdVerde = qtdVerde
                ultimoRgbVerde = rgbVerde
                quadrosSumidoVerde = 0
                ultimoVerdeRel = verdeRel

                val absX = (x0 + verdeRel.first).toFloat()
                val absY = (y0 + verdeRel.second).toFloat()
                val dist = hypot((absX - cx).toDouble(), (absY - cy).toDouble()).toFloat()
                ultimaDistVerde = dist
                if (menorDistVerde < 0f || dist < menorDistVerde) {
                    menorDistVerde = dist
                }

                if (dist <= toleranciaChegada) {
                    quadrosChegada++
                    if (quadrosChegada >= 2) {
                        if (!verdeVeioDeTemplate) {
                            fase = FaseNavegacao.ESCOLHER
                            verdeVeioDeTemplate = false
                            quadrosChegada = 0
                            ultimoVerdeRel = null
                            vistoVerde = false
                            return formatarLog(
                                fase.name, indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso,
                                "verde sem template - reescolhendo"
                            )
                        }
                        proximoPino(agora)
                        return formatarLog(
                            "CHEGOU", indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso
                        )
                    }
                    return formatarLog(
                        fase.name, indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso, "chegando"
                    )
                }

                quadrosChegada = 0
                var logClique: String? = null

                if (agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
                    val anteriorDist = ultimaDist
                    if (anteriorDist >= 0f && dist >= anteriorDist - tela.width * 0.003f) semProgresso++ else {
                        semProgresso = 0
                        indiceCliqueVariavel = 0
                    }
                    ultimaDist = dist
                    if (semProgresso >= 3 && anteriorDist >= 0f && abs(dist - anteriorDist) < 3f && dist <= toleranciaChegada * 2f) {
                        proximoPino(agora)
                        return formatarLog("CHEGOU", indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso, "chegada por parada")
                    }
                    val offsets = arrayOf(Pair(8f,0f),Pair(-8f,0f),Pair(0f,8f),Pair(0f,-8f),Pair(10f,6f),Pair(-10f,-6f),Pair(6f,-10f),Pair(-6f,10f))
                    val off = if (semProgresso >= 2) offsets[indiceCliqueVariavel++ % offsets.size] else Pair(0f,0f)
                    val px = (absX + off.first).coerceIn(x0 + 15f, x1 - 15f)
                    val py = (absY + off.second).coerceIn(y0 + 15f, y1 - 15f)
                    val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                    ultimoClique = agora
                    cliquesVerde++
                    logClique = "CLIQUE ${toque.first.toInt()},${toque.second.toInt()}"
                }

                if (semProgresso >= 6 || cliquesVerde >= 12) {
                    val progressoAntesReset = semProgresso
                    return registrarFalhaPino(
                        agora, fase.name, indice, melhorScore, qtdVerde, rgbVerde,
                        dist, progressoAntesReset, "sem progresso - reescolhendo"
                    )
                }

                val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
                val extra = if (logClique != null) "$sentidoStr | $logClique" else sentidoStr
                return formatarLog(
                    fase.name, indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso, extra
                )
            } else {
                if (retomarVerdeAte > 0L && agora > retomarVerdeAte) {
                    retomarVerdeAte = 0L
                    fase = FaseNavegacao.ESCOLHER
                    ultimoVerdeRel = null
                    vistoVerde = false
                    semProgresso = 0
                    ultimaDist = -1f
                    falhasPino = 0
                    return formatarLog(fase.name, indice, melhorScore, 0, ultimoRgbVerde, ultimaDistVerde, 0, "sem verde apos luta - ESCOLHER sem penalidade")
                }

                ultimoQtdVerde = 0
                quadrosSumidoVerde++
                val distRefAtual = if (ultimaDistVerde >= 0f) ultimaDistVerde else menorDistVerde
                val pertoDaCruz = distRefAtual >= 0f &&
                    distRefAtual <= toleranciaChegada * 2.0f &&
                    (menorDistVerde < 0f || distRefAtual <= menorDistVerde * 1.25f)

                if (vistoVerde && pertoDaCruz && quadrosSumidoVerde >= 2 && quadrosSumidoVerde < 6) {
                    val rgbRef = ultimoRgbVerde
                    if (!verdeVeioDeTemplate) {
                        fase = FaseNavegacao.ESCOLHER
                        verdeVeioDeTemplate = false
                        quadrosSumidoVerde = 0
                        ultimoVerdeRel = null
                        vistoVerde = false
                        return formatarLog(
                            fase.name, indice, melhorScore, 0, rgbRef, distRefAtual, semProgresso,
                            "sob a cruz (verde sem template - reescolhendo)"
                        )
                    }
                    proximoPino(agora)
                    return formatarLog(
                        "CHEGOU", indice, melhorScore, 0, rgbRef, distRefAtual, semProgresso, "sob a cruz"
                    )
                }

                if (quadrosSumidoVerde >= 6) {
                    return registrarFalhaPino(
                        agora, fase.name, indice, melhorScore, 0, ultimoRgbVerde,
                        ultimaDistVerde, semProgresso, "verde sumiu - reescolhendo"
                    )
                }

                return formatarLog(
                    fase.name, indice, melhorScore, 0, ultimoRgbVerde, ultimaDistVerde, semProgresso, "aguardando verde"
                )
            }
        }

        val verdes = buscarComponentesVerdes(tela, x0, y0, x1, y1)
        if (verdes.isNotEmpty()) {
            val posicoes = verdes.joinToString("; ") { "${x0 + it.posicao.first},${y0 + it.posicao.second}" }
            val verde = verdes.maxByOrNull { it.quantidade }!!
            val distVerde = hypot(((x0 + verde.posicao.first) - cx).toDouble(), ((y0 + verde.posicao.second) - cy).toDouble()).toFloat()
            if (distVerde > toleranciaChegada && agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
                val toque = dispararToqueSeguro(tela.width, tela.height, (x0 + verde.posicao.first).toFloat(), (y0 + verde.posicao.second).toFloat(), tocar)
                ultimoClique = agora
                iniciarCaminhada(verde.posicao, distVerde, agora, veioDeTemplate = false)
                vistoVerde = true
                ultimoQtdVerde = verde.quantidade
                ultimoRgbVerde = verde.rgbMedio
                val info = if (verdes.size > 1) "verdes=${verdes.size} posicoes=[$posicoes]" else "pino verde global"
                return formatarLog(fase.name, indice, melhorScore, verde.quantidade, verde.rgbMedio, distVerde, semProgresso, "$info | CLIQUE ${toque.first.toInt()},${toque.second.toInt()}")
            }
            if (verdes.size > 1) LogRota.registrarPasso(context, "ESCOLHER: ${verdes.size} pinos verdes: $posicoes", tela)
        }
        val achado = acharPorTemplate(tela, x0, y0, x1, y1, modelo, cx, cy)
        val scoreAtual = melhorScore

        if (achado == null) {
            quadrosPertoDaCruz = 0
            val candidatoPos = candidatoConsistente
            if (scoreAtual >= 0.48 && candidatoPos != null) {
                quadrosQuaseLimiar++
            } else {
                quadrosQuaseLimiar = 0
                candidatoConsistente = null
            }

            if (quadrosQuaseLimiar >= 5 && candidatoPos != null) {
                quadrosQuaseLimiar = 0
                ultimoCandidatoQuaseLimiar = null
                candidatoConsistente = null
                val distFb = hypot((candidatoPos.first - cx).toDouble(), (candidatoPos.second - cy).toDouble()).toFloat()
                if (distFb > toleranciaChegada && agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
                    val toque = dispararToqueSeguro(
                        tela.width, tela.height, candidatoPos.first.toFloat(), candidatoPos.second.toFloat(), tocar
                    )
                    ultimoClique = agora
                    iniciarCaminhada(Pair(candidatoPos.first - x0, candidatoPos.second - y0), distFb, agora, veioDeTemplate = true)
                    val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
                    return formatarLog(
                        fase.name, indice, scoreAtual, 0, ultimoRgbVerde, distFb, semProgresso,
                        "$sentidoStr | CLIQUE ${toque.first.toInt()},${toque.second.toInt()} (fallback score)"
                    )
                }
            }

            val refCandidato = ultimoCandidatoQuaseLimiar
            if (refCandidato != null && scoreAtual >= 0.45) {
                val verdeNoMapa = acharPinoVerdeProximo(tela, x0, y0, x1, y1, refCandidato, cx, cy, toleranciaChegada)
                if (verdeNoMapa != null) {
                    val verdeRel = verdeNoMapa.posicao
                    val distVerde = hypot(
                        ((x0 + verdeRel.first) - cx).toDouble(),
                        ((y0 + verdeRel.second) - cy).toDouble()
                    ).toFloat()
                    if (distVerde > toleranciaChegada && agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
                        val toque = dispararToqueSeguro(
                            tela.width, tela.height,
                            (x0 + verdeRel.first).toFloat(), (y0 + verdeRel.second).toFloat(), tocar
                        )
                        ultimoClique = agora
                        iniciarCaminhada(verdeRel, distVerde, agora, veioDeTemplate = true)
                        vistoVerde = true
                        ultimoQtdVerde = verdeNoMapa.quantidade
                        ultimoRgbVerde = verdeNoMapa.rgbMedio
                        val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
                        return formatarLog(
                            fase.name, indice, scoreAtual, verdeNoMapa.quantidade, verdeNoMapa.rgbMedio,
                            distVerde, semProgresso, "$sentidoStr | CLIQUE ${toque.first.toInt()},${toque.second.toInt()} (verde validado candidato)"
                        )
                    }
                }
            }

            naoVisto++
            if (naoVisto >= Cal.NAO_VISTO_LIMITE) {
                naoVisto = 0
                return registrarFalhaPino(
                    agora, fase.name, indice, scoreAtual, 0, ultimoRgbVerde,
                    -1f, semProgresso, "nao localizado"
                )
            }
            return formatarLog(
                fase.name, indice, scoreAtual, 0, ultimoRgbVerde, -1f, semProgresso, "procurando"
            )
        }

        naoVisto = 0
        quadrosQuaseLimiar = 0
        ultimoCandidatoQuaseLimiar = null
        candidatoConsistente = null
        val dist = hypot((achado.first - cx).toDouble(), (achado.second - cy).toDouble()).toFloat()

        if (dist <= toleranciaChegada) {
            quadrosPertoDaCruz++
            if (quadrosPertoDaCruz >= 3) {
                proximoPino(agora)
                return formatarLog(
                    "CHEGOU", indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso, "perto da cruz"
                )
            }
            return formatarLog(
                fase.name, indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso, "ignorado perto da cruz"
            )
        }

        quadrosPertoDaCruz = 0

        var logClique: String? = null
        if (agora - ultimoClique >= Cal.INTERVALO_CLIQUE) {
            val toque = dispararToqueSeguro(
                tela.width, tela.height, achado.first.toFloat(), achado.second.toFloat(), tocar
            )
            ultimoClique = agora
            val relPos = Pair(achado.first - x0, achado.second - y0)
            iniciarCaminhada(relPos, dist, agora, veioDeTemplate = true)
            logClique = "CLIQUE ${toque.first.toInt()},${toque.second.toInt()}"
        }

        val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
        val extra = if (logClique != null) "$sentidoStr | $logClique" else sentidoStr
        return formatarLog(
            fase.name, indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso, extra
        )
    }

    private fun regiaoQuasePreta(pixels: IntArray, largura: Int, x: Int, y: Int, w: Int, h: Int): Boolean {
        var pretos = 0
        val total = w * h
        for (dy in 0 until h) for (dx in 0 until w) {
            val p = pixels[(y + dy) * largura + x + dx]
            if (((p shr 16) and 255) < 18 && ((p shr 8) and 255) < 18 && (p and 255) < 18) pretos++
        }
        return pretos >= total * 0.85
    }

    private fun buscarComponentesVerdes(tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): List<DeteccaoVerde> {
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return emptyList()
        val px = IntArray(w * h)
        tela.getPixels(px, 0, w, x0, y0, w, h)
        val mask = BooleanArray(px.size) { ehVerdePinoAtivo(px[it]) }
        val seen = BooleanArray(px.size); val queue = IntArray(px.size)
        val out = ArrayList<DeteccaoVerde>()
        val dx = intArrayOf(-1,1,0,0); val dy = intArrayOf(0,0,-1,1)
        for (start in px.indices) {
            if (!mask[start] || seen[start]) continue
            var head=0; var tail=0; queue[tail++]=start; seen[start]=true
            var sx=0L; var sy=0L; var sr=0L; var sg=0L; var sb=0L; var n=0
            while(head<tail) {
                val at=queue[head++]; val yy=at/w; val xx=at%w; val p=px[at]
                sx+=xx; sy+=yy; sr+=((p shr 16) and 255); sg+=((p shr 8) and 255); sb+=(p and 255); n++
                for(d in 0..3) { val nx=xx+dx[d]; val ny=yy+dy[d]
                    if(nx in 0 until w && ny in 0 until h) { val ni=ny*w+nx
                        if(mask[ni] && !seen[ni]) { seen[ni]=true; queue[tail++]=ni }
                    }
                }
            }
            if(n>=4) out.add(DeteccaoVerde(Pair((sx/n).toInt(),(sy/n).toInt()),n,Triple((sr/n).toInt(),(sg/n).toInt(),(sb/n).toInt())))
        }
        return out
    }

    private fun acharPinoVerdeProximo(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        candidatoAbs: Pair<Int, Int>,
        cx: Float,
        cy: Float,
        toleranciaChegada: Float
    ): DeteccaoVerde? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null

        val refRel = Pair(
            (candidatoAbs.first - x0).coerceIn(0, rw - 1),
            (candidatoAbs.second - y0).coerceIn(0, rh - 1)
        )
        val raioValidacao = max(14, (rw * 0.08f).toInt())
        val det = buscarMaiorComponenteVerdeNaJanela(tela, x0, y0, rw, rh, refRel, raioValidacao) ?: return null
        val absX = (x0 + det.posicao.first).toFloat()
        val absY = (y0 + det.posicao.second).toFloat()
        val dist = hypot((absX - cx).toDouble(), (absY - cy).toDouble()).toFloat()
        if (dist <= toleranciaChegada) return null
        return det
    }

    private fun buscarMaiorComponenteVerdeNaJanela(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        rw: Int,
        rh: Int,
        ref: Pair<Int, Int>,
        raio: Int
    ): DeteccaoVerde? {
        val minX = (ref.first - raio).coerceIn(0, rw - 1)
        val maxX = (ref.first + raio).coerceIn(minX, rw - 1)
        val minY = (ref.second - raio).coerceIn(0, rh - 1)
        val maxY = (ref.second + raio).coerceIn(minY, rh - 1)

        val subW = maxX - minX + 1
        val subH = maxY - minY + 1
        if (subW <= 0 || subH <= 0) return null

        val px = IntArray(subW * subH)
        tela.getPixels(px, 0, subW, x0 + minX, y0 + minY, subW, subH)

        val totalPixeis = subW * subH
        val verdeMask = BooleanArray(totalPixeis)
        for (i in 0 until totalPixeis) {
            verdeMask[i] = ehVerdePinoAtivo(px[i])
        }

        val visitado = BooleanArray(totalPixeis)
        val fila = IntArray(totalPixeis)

        var achouComponente = false
        var melhorCentroX = 0.0
        var melhorCentroY = 0.0
        var melhorTamanho = 0
        var melhorPontuacao = Double.NEGATIVE_INFINITY
        var melhorRgb = Triple(0, 0, 0)

        val dirsX = intArrayOf(-1, 1, 0, 0)
        val dirsY = intArrayOf(0, 0, -1, 1)

        for (startIdx in 0 until totalPixeis) {
            if (!verdeMask[startIdx] || visitado[startIdx]) continue

            var cabeca = 0
            var cauda = 0
            fila[cauda++] = startIdx
            visitado[startIdx] = true

            var somaX = 0.0
            var somaY = 0.0
            var somaR = 0L
            var somaG = 0L
            var somaB = 0L
            var count = 0

            while (cabeca < cauda) {
                val curr = fila[cabeca++]
                val cyLocal = curr / subW
                val cxLocal = curr - cyLocal * subW
                val absRelX = minX + cxLocal
                val absRelY = minY + cyLocal
                val pixel = px[curr]

                somaX += absRelX
                somaY += absRelY
                somaR += ((pixel shr 16) and 0xFF).toLong()
                somaG += ((pixel shr 8) and 0xFF).toLong()
                somaB += (pixel and 0xFF).toLong()
                count++

                for (d in 0..3) {
                    val nx = cxLocal + dirsX[d]
                    val ny = cyLocal + dirsY[d]
                    if (nx in 0 until subW && ny in 0 until subH) {
                        val nIdx = ny * subW + nx
                        if (verdeMask[nIdx] && !visitado[nIdx]) {
                            visitado[nIdx] = true
                            fila[cauda++] = nIdx
                        }
                    }
                }
            }

            if (count < 4) continue

            val centroX = somaX / count
            val centroY = somaY / count
            val distRef = hypot(centroX - ref.first, centroY - ref.second)
            val pontuacao = count.toDouble() - distRef * 0.35

            if (!achouComponente || pontuacao > melhorPontuacao) {
                achouComponente = true
                melhorPontuacao = pontuacao
                melhorCentroX = centroX
                melhorCentroY = centroY
                melhorTamanho = count
                melhorRgb = Triple(
                    (somaR / count).toInt(),
                    (somaG / count).toInt(),
                    (somaB / count).toInt()
                )
            }
        }

        if (!achouComponente) return null
        return DeteccaoVerde(
            posicao = Pair(melhorCentroX.toInt(), melhorCentroY.toInt()),
            quantidade = melhorTamanho,
            rgbMedio = melhorRgb
        )
    }

    private fun acharPinoVerdeLocal(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        m: Modelo,
        ref: Pair<Int, Int>?
    ): DeteccaoVerde? {
        if (ref == null) return null

        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null

        val raioBase = max(24, maxOf(m.w, m.h) * 2)
        val primeiraBusca = buscarMaiorComponenteVerdeNaJanela(
            tela, x0, y0, rw, rh, ref, raioBase
        )
        if (primeiraBusca != null) return primeiraBusca

        return buscarMaiorComponenteVerdeNaJanela(
            tela, x0, y0, rw, rh, ref, raioBase * 2
        )
    }

    private fun acharPinoVerdeJanelaTripla(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        m: Modelo,
        ref: Pair<Int, Int>?
    ): DeteccaoVerde? {
        if (ref == null) return null

        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null

        val raioBase = max(24, maxOf(m.w, m.h) * 2)
        return buscarMaiorComponenteVerdeNaJanela(
            tela, x0, y0, rw, rh, ref, raioBase * 3
        )
    }

    fun temMonstro(tela: Bitmap): Boolean {
        val x0 = (tela.width * Cal.BATALHA_X0)
            .toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.BATALHA_Y0)
            .toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.BATALHA_X1)
            .toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.BATALHA_Y1)
            .toInt().coerceIn(y0 + 1, tela.height)

        val rw = x1 - x0
        val rh = y1 - y0

        if (rw <= 0 || rh <= 0) return false

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        var pixelsDestaque = 0
        var pixelsBarraVida = 0

        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val ehFundoEscuro =
                r <= 32 && g <= 42 && b >= 18 && b <= 75

            if (!ehFundoEscuro && (r > 95 || g > 105 || b > 125)) {
                pixelsDestaque++
            }
            val ehVerdeHp = g >= 155 && r <= 85 && b <= 90
            val ehAmareloOuLaranjaHp = r >= 165 && g in 70..225 && b <= 85
            val ehVermelhoHp = r >= 155 && g <= 75 && b <= 75
            if (ehVerdeHp || ehAmareloOuLaranjaHp || ehVermelhoHp) {
                pixelsBarraVida++
            }
        }

        return pixelsBarraVida >= 8 && pixelsDestaque >= 220
    }

    fun acharCruz(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): Pair<Int, Int>? {
        val rw = x1 - x0
        val rh = y1 - y0

        if (rw < 20 || rh < 20) return null

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        val branco = BooleanArray(rw * rh)

        for (i in px.indices) {
            val p = px[i]
            branco[i] =
                ((p shr 16) and 0xFF) > 195 &&
                ((p shr 8) and 0xFF) > 195 &&
                (p and 0xFF) > 195
        }

        val escala = (tela.width / 2460f).coerceIn(0.35f, 1.5f)
        val raioArm = (9f * escala).toInt().coerceAtLeast(3)
        val minScore = (62f * escala).toInt().coerceAtLeast(16)
        val margemY = (raioArm + 1).coerceAtMost(rh / 2 - 1)
        val margemX = (raioArm + 1).coerceAtMost(rw / 2 - 1)

        var melhor = 0
        var bx = -1
        var by = -1

        for (y in margemY until rh - margemY) {
            for (x in margemX until rw - margemX) {
                if (
                    !branco[y * rw + x] ||
                    !branco[y * rw + x + 1] ||
                    !branco[(y + 1) * rw + x] ||
                    !branco[(y + 1) * rw + x + 1]
                ) continue

                var s = 0

                for (d in -raioArm..raioArm) {
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

        return if (melhor >= minScore) {
            Pair(x0 + bx + 1, y0 + by + 1)
        } else {
            null
        }
    }

    private fun acharPorTemplate(
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

        if (
            rw < m.w ||
            rh < m.h ||
            m.norma < 1e-3 ||
            m.pixelsValidos < 8
        ) {
            melhorScore = 0.0
            ultimoCandidatoQuaseLimiar = null
            candidatoConsistente = null
            return null
        }

        val cruzRelX = cruzAbsX - x0
        val cruzRelY = cruzAbsY - y0

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        val g = DoubleArray(rw * rh)
        val ocultoPelaCruz = BooleanArray(rw * rh)

        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val verde = (p shr 8) and 0xFF
            val blue = p and 0xFF
            val ehLaranjaRota = r >= 175 && verde in 85..165 && blue <= 80
            val baseCinza = cinza(p)
            g[i] = if (ehLaranjaRota) baseCinza * 0.45 else baseCinza
        }

        val centroCruzX = cruzRelX.toInt()
        val centroCruzY = cruzRelY.toInt()
        val meiaLarguraCruz = (m.w * 0.12f).toInt().coerceAtLeast(2)
        val alcanceCruz = (m.h * 0.43f).toInt().coerceAtLeast(7)

        for (dy in -alcanceCruz..alcanceCruz) {
            for (dx in -alcanceCruz..alcanceCruz) {
                val dentroDaCruz =
                    abs(dx) <= meiaLarguraCruz ||
                    abs(dy) <= meiaLarguraCruz

                if (!dentroDaCruz) continue

                val xx = centroCruzX + dx
                val yy = centroCruzY + dy

                if (xx in 0 until rw && yy in 0 until rh) {
                    val idx = yy * rw + xx
                    val p = px[idx]
                    val r = (p shr 16) and 0xFF
                    val verde = (p shr 8) and 0xFF
                    val blue = p and 0xFF
                    if (r >= 185 && verde >= 185 && blue >= 185) {
                        ocultoPelaCruz[idx] = true
                    }
                }
            }
        }

        var melhor = -2.0
        var bx = 0
        var by = 0

        var y = 0
        while (y <= rh - m.h) {
            var x = 0
            while (x <= rw - m.w) {
                val score = if (regiaoQuasePreta(px, rw, x, y, m.w, m.h)) -1.0 else pontuar(g, ocultoPelaCruz, rw, x, y, m)
                if (score > melhor) {
                    melhor = score
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

                if (
                    x < 0 ||
                    yy < 0 ||
                    x > rw - m.w ||
                    yy > rh - m.h
                ) continue

                val s = if (regiaoQuasePreta(px, rw, x, yy, m.w, m.h)) -1.0 else pontuar(g, ocultoPelaCruz, rw, x, yy, m)
                if (s > melhor) {
                    melhor = s
                    bx = x
                    by = yy
                }
            }
        }

        melhorScore = melhor

        val centroCandidato = Pair(
            x0 + bx + m.w / 2,
            y0 + by + m.h / 2
        )

        if (melhor >= 0.48) {
            val anterior = ultimoCandidatoQuaseLimiar
            if (anterior != null) {
                val deslocamento = hypot(
                    (centroCandidato.first - anterior.first).toDouble(),
                    (centroCandidato.second - anterior.second).toDouble()
                )
                val maxDesloc = max(6.0, m.w * 0.35)
                if (deslocamento <= maxDesloc) {
                    candidatoConsistente = Pair(
                        (anterior.first + centroCandidato.first) / 2,
                        (anterior.second + centroCandidato.second) / 2
                    )
                } else {
                    candidatoConsistente = null
                }
            } else {
                candidatoConsistente = null
            }
            ultimoCandidatoQuaseLimiar = centroCandidato
        } else {
            ultimoCandidatoQuaseLimiar = null
            candidatoConsistente = null
        }

        if (melhor >= Cal.LIMIAR) {
            return centroCandidato
        }

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
        var somaImagem = 0.0
        var somaModelo = 0.0
        var quantidade = 0

        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val baseModelo = yy * m.w

            for (xx in 0 until m.w) {
                val pos = base + xx
                val indiceModelo = baseModelo + xx

                if (
                    ocultoPelaCruz[pos] ||
                    !m.valida[indiceModelo]
                ) continue

                somaImagem += g[pos]
                somaModelo += m.z[indiceModelo]
                quantidade++
            }
        }

        val total = m.pixelsValidos

        if (quantidade < total * 0.40) {
            return -1.0
        }

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

                if (
                    ocultoPelaCruz[pos] ||
                    !m.valida[indiceModelo]
                ) continue

                val a = g[pos] - mediaImagem
                val b = m.z[indiceModelo] - mediaModelo

                covariancia += a * b
                varianciaImagem += a * a
                varianciaModelo += b * b
            }
        }

        if (
            varianciaImagem < 50.0 ||
            varianciaModelo < 50.0
        ) {
            return -1.0
        }

        return covariancia /
            (sqrt(varianciaImagem) * sqrt(varianciaModelo))
    }
}
