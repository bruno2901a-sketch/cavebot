package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

private const val RECLIQUE_SEM_MOVER_MS = 500L
private const val AVISO_BUSCA_PINO_MS = 10000L
private const val AVISO_ALARME_SEM_CHEGADA_MS = 180000L

private enum class FaseNavegacao {
    ESCOLHER,
    CAMINHANDO
}

private data class DeteccaoVerde(
    val posicao: Pair<Int, Int>,
    val quantidade: Int,
    val rgbMedio: Triple<Int, Int, Int>
)

private data class ResultadoVerdeTemplate(
    val valido: Boolean,
    val scoreAlvo: Double,
    val rivalIndice: Int?,
    val rivalScore: Double,
    val margem: Double
)

private data class AvaliacaoPino(
    val alvoCor: Double,
    val rivalIndice: Int?,
    val rivalCor: Double,
    val margem: Double,
    val ambiguo: Boolean,
    val corBaixa: Boolean = false
) {
    /** Verdadeiro quando NÃO se deve clicar: cor do alvo fraca demais ou rival colado no alvo. */
    val recusado: Boolean get() = ambiguo || corBaixa

    fun motivo(): String = when {
        corBaixa && ambiguo -> "COR_BAIXA+AMBIGUO"
        corBaixa -> "COR_BAIXA"
        ambiguo -> "AMBIGUO"
        else -> "OK"
    }

    fun texto(alvo: Int): String =
        "alvo=#$alvo corAlvo=${"%.2f".format(alvoCor)}(min ${"%.2f".format(Cal.LIMIAR_COR_ALVO)}) rival=" +
            (if (rivalIndice != null) "#$rivalIndice(${"%.2f".format(rivalCor)})" else "nenhum") +
            " margemCor=${"%+.2f".format(margem)}(min ${"%.2f".format(Cal.MARGEM_PINO)})"
}

private data class EstadoComparacaoMinimapa(
    val emMovimento: Boolean,
    val diffMedia: Double
)

private data class DeslocamentoClique(
    val tentativa: Int,
    val padrao: String,
    val dx: Float,
    val dy: Float
)

private class EstadoPino {
    var quadrosChegada = 0
    var quadrosSumidoVerde = 0
    var quadrosDistEstavel = 0
    var ultimaDistObservada = -1f
    var ultimaDist = -1f
    var ultimaDistVerde = -1f
    var menorDistVerde = -1f
    var semProgresso = 0
    var cliquesVerde = 0
    var falhasPino = 0
    var ultimoVerdeRel: Pair<Int, Int>? = null
    var ultimoQtdVerde = 0
    var ultimoRgbVerde: Triple<Int, Int, Int>? = null
    var vistoVerde = false
    var ultimoPosPinoConhecidaRel: Pair<Int, Int>? = null

    fun reiniciar(preservarFalhas: Boolean = false) {
        val falhasAnteriores = falhasPino
        quadrosChegada = 0
        quadrosSumidoVerde = 0
        quadrosDistEstavel = 0
        ultimaDistObservada = -1f
        ultimaDist = -1f
        ultimaDistVerde = -1f
        menorDistVerde = -1f
        semProgresso = 0
        cliquesVerde = 0
        falhasPino = if (preservarFalhas) falhasAnteriores else 0
        ultimoVerdeRel = null
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
        ultimoPosPinoConhecidaRel = null
    }
}

private fun ehVerdePinoAtivo(p: Int): Boolean {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return g >= 170 && r < 90 && b < 90
}

private fun ehPixelVerdeCaixa(p: Int): Boolean {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return g > 190 && r < 90 && b < 90
}

object Rota {
    @Volatile
    var limiteAlarmeMs: Long = 30000L

    @Volatile
    var cancelarAutoloot = false

    @Volatile
    var intervaloCliqueMs: Long = 700L

    @Volatile
    var lutas: Int = 0

    @Volatile
    var autoloots: Int = 0

    @Volatile
    var chegadasConfirmadas: Int = 0

    @Volatile
    var pulosPino: Int = 0

    @Volatile
    var pixelsPorCasa: Float = Cal.PIXELS_POR_CASA_PADRAO

    @Volatile
    var tamanhoCasaPx: Float = Cal.TAMANHO_CASA_PX_PADRAO

    @Volatile
    var limiarMinimapaParado: Float = Cal.LIMIAR_MINIMAPA_PARADO_PADRAO

    @Volatile
    var refBarraTituloX: Float = Cal.REF_BARRA_TITULO_X_PADRAO

    @Volatile
    var refBarraTituloY: Float = Cal.REF_BARRA_TITULO_Y_PADRAO

    @Volatile
    var capturadorQuadroLoot: (() -> Bitmap?)? = null

    @Volatile
    var intervaloLootMs: Long = 100L

    @Volatile
    var maxAutolootMs: Long = 40000L

    // ---- Threads ----
    // Todo o estado mutável abaixo pertence à thread de análise (passo / passoSoAlarme).
    // Chamadas externas entram na fila pendentes e são aplicadas no início do próximo passo.
    private val pendentes = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    private fun aplicarPendentes() {
        while (true) {
            val acao = pendentes.poll() ?: return
            acao()
        }
    }

    private var modelos: List<Modelo> = emptyList()
    private var fase = FaseNavegacao.ESCOLHER
    private var alvo = 0
    private var direcaoIda = true
    private var ultimoClique = 0L
    private var houveMovimentoDesdeClique = false
    private var tentativaClique = 0
    private var iniciouCaminhadaEm = 0L
    private var inicioBuscaPinoAtualEm = 0L
    private var ultimoAvisoBuscaPinoEm = 0L
    private var alarmeSemChegadaTocando = false
    private var tempoMaxPinoMs = 15000L
    private var pausadoAte = 0L
    private var ultimoUriSom = ""
    private var ultimoVolume = 80
    private val estadoPino = EstadoPino()

    private var quadrosChegada: Int
        get() = estadoPino.quadrosChegada
        set(v) { estadoPino.quadrosChegada = v }
    private var quadrosSumidoVerde: Int
        get() = estadoPino.quadrosSumidoVerde
        set(v) { estadoPino.quadrosSumidoVerde = v }
    private var quadrosDistEstavel: Int
        get() = estadoPino.quadrosDistEstavel
        set(v) { estadoPino.quadrosDistEstavel = v }
    private var ultimaDistObservada: Float
        get() = estadoPino.ultimaDistObservada
        set(v) { estadoPino.ultimaDistObservada = v }
    private var ultimaDist: Float
        get() = estadoPino.ultimaDist
        set(v) { estadoPino.ultimaDist = v }
    private var ultimaDistVerde: Float
        get() = estadoPino.ultimaDistVerde
        set(v) { estadoPino.ultimaDistVerde = v }
    private var menorDistVerde: Float
        get() = estadoPino.menorDistVerde
        set(v) { estadoPino.menorDistVerde = v }
    private var semProgresso: Int
        get() = estadoPino.semProgresso
        set(v) { estadoPino.semProgresso = v }
    private var cliquesVerde: Int
        get() = estadoPino.cliquesVerde
        set(v) { estadoPino.cliquesVerde = v }
    private var falhasPino: Int
        get() = estadoPino.falhasPino
        set(v) { estadoPino.falhasPino = v }
    private var ultimoVerdeRel: Pair<Int, Int>?
        get() = estadoPino.ultimoVerdeRel
        set(v) { estadoPino.ultimoVerdeRel = v }
    private var ultimoQtdVerde: Int
        get() = estadoPino.ultimoQtdVerde
        set(v) { estadoPino.ultimoQtdVerde = v }
    private var ultimoRgbVerde: Triple<Int, Int, Int>?
        get() = estadoPino.ultimoRgbVerde
        set(v) { estadoPino.ultimoRgbVerde = v }
    private var vistoVerde: Boolean
        get() = estadoPino.vistoVerde
        set(v) { estadoPino.vistoVerde = v }
    private var ultimoPosPinoConhecidaRel: Pair<Int, Int>?
        get() = estadoPino.ultimoPosPinoConhecidaRel
        set(v) { estadoPino.ultimoPosPinoConhecidaRel = v }

    private var versaoRegistradaNoLog = false
    private var ultimoRecorteMinimapa: DoubleArray? = null
    private var ultimoRecorteMinimapaW = 0
    private var ultimoRecorteMinimapaH = 0
    private var minimapaParadoDesde = 0L
    private var ultimaDiffMinimapa = 0.0
    private var ultimaToleranciaPx = 0f
    private var ultimaAvaliacaoTemplate: AvaliacaoPino? = null

    @Volatile
    private var melhorScore = 0.0

    fun carregar(ctx: Context, inicio: Int, fim: Int, larguraTela: Int) {
        val catalogo = CatalogoPinos.validar(ctx, inicio, fim)
        check(catalogo.valido) { catalogo.erro ?: "Catálogo de pinos vazio." }

        val lado = (Cal.LADO_ICONE * larguraTela / 2460f)
            .toInt().coerceAtLeast(8)
        val lista = ArrayList<Modelo>(catalogo.ids.size)

        for (n in catalogo.ids) {
            val raw = ctx.assets.open("pins/p%02d.png".format(n)).use {
                BitmapFactory.decodeStream(it)
            } ?: error("Não foi possível decodificar o pino $n.")

            val bmp = if (raw.width == lado && raw.height == lado) raw
            else Bitmap.createScaledBitmap(raw, lado, lado, true).also {
                if (it !== raw) raw.recycle()
            }
            lista.add(Modelo(n, bmp))
        }

        check(lista.size == fim - inicio + 1) {
            "Catálogo incompleto: esperado ${fim - inicio + 1}, carregado ${lista.size}."
        }
        val novos = lista.toList()
        pendentes.add {
            modelos = novos
            resetarInterno()
        }
        BotService.pararAlarme()
    }

    fun resetar() {
        BotService.pararAlarme()
        lutas = 0
        autoloots = 0
        chegadasConfirmadas = 0
        pulosPino = 0
        pendentes.add { resetarInterno() }
    }

    private fun resetarInterno() {
        fase = FaseNavegacao.ESCOLHER
        alvo = 0
        direcaoIda = true
        ultimoClique = 0L
        houveMovimentoDesdeClique = false
        tentativaClique = 0
        iniciouCaminhadaEm = 0L
        inicioBuscaPinoAtualEm = 0L
        ultimoAvisoBuscaPinoEm = 0L
        alarmeSemChegadaTocando = false
        tempoMaxPinoMs = 15000L
        pausadoAte = 0L
        estadoPino.reiniciar(preservarFalhas = false)
        versaoRegistradaNoLog = false
        cancelarAutoloot = false
        lutas = 0
        autoloots = 0
        chegadasConfirmadas = 0
        pulosPino = 0
        ultimoRecorteMinimapa = null
        ultimoRecorteMinimapaW = 0
        ultimoRecorteMinimapaH = 0
        minimapaParadoDesde = 0L
        ultimaDiffMinimapa = 0.0
        ultimaToleranciaPx = 0f
        ultimaAvaliacaoTemplate = null
        melhorScore = 0.0
        BatalhaEngine.resetar()
        BotService.pararAlarme()
    }

    fun retomarPausaPinoPerdido() {
        pendentes.add { retomarPausaPinoPerdidoInterno() }
    }

    private fun retomarPausaPinoPerdidoInterno() {
        pausadoAte = 0L
        estadoPino.reiniciar(preservarFalhas = false)
        fase = FaseNavegacao.ESCOLHER
        iniciouCaminhadaEm = 0L
        ultimoRecorteMinimapa = null
        ultimoRecorteMinimapaW = 0
        ultimoRecorteMinimapaH = 0
        minimapaParadoDesde = 0L
        alarmeSemChegadaTocando = false
        BotService.pararAlarme()
    }

    private fun calcularToleranciaChegada(larguraTela: Int): Float {
        return max(
            Cal.TOLERANCIA_CHEGADA_CASAS * pixelsPorCasa,
            34f * (larguraTela / 2460f)
        )
    }

    private fun atualizarEstabilidadeDistancia(distAtual: Float) {
        if (distAtual < 0f) return
        if (ultimaDistObservada >= 0f && abs(distAtual - ultimaDistObservada) <= 1.5f) {
            quadrosDistEstavel++
        } else {
            quadrosDistEstavel = 0
        }
        ultimaDistObservada = distAtual
    }

    private fun estaParado(estadoMini: EstadoComparacaoMinimapa, tempoParadoMs: Long): Boolean {
        val paradoPorMinimapa = !estadoMini.emMovimento && tempoParadoMs >= Cal.TEMPO_MINIMO_PARADO_CHEGADA_MS
        val paradoPorDistancia = quadrosDistEstavel >= 2
        return paradoPorMinimapa || paradoPorDistancia
    }

    /**
     * Calcula o deslocamento para a tentativa atual:
     * - Tentativa 0: (0,0)
     * - Ciclos de 16 tentativas: A (cruz 4) -> B (diagonais 4) -> C (cruz 2x 4) -> D (diagonais 2x 4)
     * - A cada ciclo completo (16 tentativas), o raio base aumenta em +r até o limite de 4r.
     */
    private fun calcularDeslocamentoClique(larguraTela: Int, tentativa: Int): DeslocamentoClique {
        val rBase = 6f * (larguraTela / 2460f)
        if (tentativa <= 0) {
            return DeslocamentoClique(tentativa = 0, padrao = "0", dx = 0f, dy = 0f)
        }

        val idx = tentativa - 1
        val ciclo = idx / 16
        val posNoCiclo = idx % 16
        val multiplicadorRaio = (1 + ciclo).coerceAtMost(4)
        val r = rBase * multiplicadorRaio

        return when (posNoCiclo) {
            // Padrão A (cruz): (+r,0) (-r,0) (0,+r) (0,-r)
            0 -> DeslocamentoClique(tentativa, "A", +r, 0f)
            1 -> DeslocamentoClique(tentativa, "A", -r, 0f)
            2 -> DeslocamentoClique(tentativa, "A", 0f, +r)
            3 -> DeslocamentoClique(tentativa, "A", 0f, -r)

            // Padrão B (diagonais): (+r,+r) (-r,-r) (+r,-r) (-r,+r)
            4 -> DeslocamentoClique(tentativa, "B", +r, +r)
            5 -> DeslocamentoClique(tentativa, "B", -r, -r)
            6 -> DeslocamentoClique(tentativa, "B", +r, -r)
            7 -> DeslocamentoClique(tentativa, "B", -r, +r)

            // Padrão C (anel maior, raio 2r): (+2r,0) (-2r,0) (0,+2r) (0,-2r)
            8 -> DeslocamentoClique(tentativa, "C", +2f * r, 0f)
            9 -> DeslocamentoClique(tentativa, "C", -2f * r, 0f)
            10 -> DeslocamentoClique(tentativa, "C", 0f, +2f * r)
            11 -> DeslocamentoClique(tentativa, "C", 0f, -2f * r)

            // Padrão D (diagonais maiores, raio 2r): (+2r,+2r) (-2r,-2r) (+2r,-2r) (-2r,+2r)
            12 -> DeslocamentoClique(tentativa, "D", +2f * r, +2f * r)
            13 -> DeslocamentoClique(tentativa, "D", -2f * r, -2f * r)
            14 -> DeslocamentoClique(tentativa, "D", +2f * r, -2f * r)
            else -> DeslocamentoClique(tentativa, "D", -2f * r, +2f * r)
        }
    }

    private fun clicarComDeslocamento(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        alvoAbsX: Float,
        alvoAbsY: Float,
        pinoId: Int,
        agora: Long,
        context: Context,
        tocar: (Float, Float) -> Unit
    ): Pair<Float, Float> {
        val desl = calcularDeslocamentoClique(tela.width, tentativaClique)

        val minMiniX = (x0 + 4f).coerceAtMost((x1 - 4f).coerceAtLeast(x0.toFloat()))
        val maxMiniX = (x1 - 4f).coerceAtLeast(minMiniX)
        val minMiniY = (y0 + 4f).coerceAtMost((y1 - 4f).coerceAtLeast(y0.toFloat()))
        val maxMiniY = (y1 - 4f).coerceAtLeast(minMiniY)

        val pxMini = (alvoAbsX + desl.dx).coerceIn(minMiniX, maxMiniX)
        val pyMini = (alvoAbsY + desl.dy).coerceIn(minMiniY, maxMiniY)

        val toque = dispararToqueSeguro(tela.width, tela.height, pxMini, pyMini, tocar)

        ultimoClique = agora
        houveMovimentoDesdeClique = false
        tentativaClique++

        LogRota.registrarPasso(
            context,
            "CLIQUE_DESLOCAMENTO pino=#$pinoId tentativa=${desl.tentativa} padrao=${desl.padrao} " +
                "dx=${"%.1f".format(desl.dx)} dy=${"%.1f".format(desl.dy)} " +
                "posFinal=(${toque.first.toInt()},${toque.second.toInt()})",
            tela
        )

        return toque
    }

    private fun dispararToqueSeguro(
        larguraTela: Int,
        alturaTela: Int,
        x: Float,
        y: Float,
        tocar: (Float, Float) -> Unit
    ): Pair<Float, Float> {
        val minX = minToqueX(larguraTela)
        val maxX = maxToqueX(larguraTela)
        val minY = minToqueY(alturaTela)
        val maxY = maxToqueY(alturaTela)
        val sx = x.coerceIn(minX, maxX)
        val sy = y.coerceIn(minY, maxY)
        tocar(sx, sy)
        return Pair(sx, sy)
    }

    private fun minToqueX(larguraTela: Int): Float = 28f.coerceAtMost((larguraTela - 1).toFloat())
    private fun maxToqueX(larguraTela: Int): Float = (larguraTela * 0.985f - 20f).coerceAtLeast(minToqueX(larguraTela))
    private fun minToqueY(alturaTela: Int): Float = 28f.coerceAtMost((alturaTela - 1).toFloat())
    private fun maxToqueY(alturaTela: Int): Float = (alturaTela - 28f).coerceAtLeast(minToqueY(alturaTela))

    private fun calcularTempoMaxPinoMs(distPx: Float): Long {
        val pxCasa = pixelsPorCasa.coerceAtLeast(1f)
        val casasReais = if (distPx > 0f) (distPx / pxCasa) else 1f
        val baseMs = (casasReais * Cal.TEMPO_POR_CASA_MS.toFloat()).toLong()
        return (baseMs * Cal.FATOR_TEMPO_MAX_PINO) + Cal.FOLGA_TEMPO_MAX_PINO_MS
    }

    private fun iniciarCaminhada(
        relPos: Pair<Int, Int>,
        distAtual: Float,
        agora: Long
    ) {
        val estabilidadePreservada = quadrosDistEstavel
        val distObservadaPreservada = ultimaDistObservada
        estadoPino.reiniciar(preservarFalhas = true)
        quadrosDistEstavel = estabilidadePreservada
        ultimaDistObservada = distObservadaPreservada
        fase = FaseNavegacao.CAMINHANDO
        ultimoVerdeRel = relPos
        ultimoPosPinoConhecidaRel = relPos
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
        ultimaDistVerde = distAtual
        menorDistVerde = distAtual
        ultimaDist = distAtual
        iniciouCaminhadaEm = agora
        tempoMaxPinoMs = calcularTempoMaxPinoMs(distAtual)
    }

    private fun chegouNoPino(
        dist: Float,
        toleranciaChegada: Float,
        estadoMini: EstadoComparacaoMinimapa,
        tempoParadoMs: Long,
        vistoVerdeNesteTrecho: Boolean
    ): Boolean {
        val parado = estaParado(estadoMini, tempoParadoMs)
        val dentroTolerancia = dist >= 0f && dist <= toleranciaChegada
        if (dentroTolerancia && parado && vistoVerdeNesteTrecho) {
            quadrosChegada++
            return quadrosChegada >= 2
        }
        quadrosChegada = 0
        return false
    }

    /**
     * Único ponto (junto com resetarInterno) que altera `alvo`.
     * Percorre 1..N em ida e volta sem pular nenhum pino.
     */
    private fun proximoPino(
        agora: Long,
        motivoAvanco: String,
        context: Context,
        tela: Bitmap
    ) {
        val tamanho = modelos.size
        if (tamanho <= 0) return

        val pinoAntesId = if (alvo in modelos.indices) modelos[alvo].indice else alvo

        if (motivoAvanco == "CHEGADA_CONFIRMADA") {
            chegadasConfirmadas++
        }

        if (alarmeSemChegadaTocando) {
            alarmeSemChegadaTocando = false
            BotService.pararAlarme()
        }

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
                alvo = 1.coerceAtMost(tamanho - 1)
            }
        }

        val pinoDepoisId = if (alvo in modelos.indices) modelos[alvo].indice else alvo
        val dirStr = if (direcaoIda) "IDA" else "VOLTA"

        LogRota.registrarPasso(
            context,
            "MUDANCA_ALVO motivo=$motivoAvanco de=#$pinoAntesId para=#$pinoDepoisId direcao=$dirStr chegadas=$chegadasConfirmadas",
            tela
        )

        fase = FaseNavegacao.ESCOLHER
        ultimoClique = 0L
        houveMovimentoDesdeClique = false
        tentativaClique = 0
        iniciouCaminhadaEm = 0L
        inicioBuscaPinoAtualEm = agora
        ultimoAvisoBuscaPinoEm = 0L
        tempoMaxPinoMs = 15000L
        pausadoAte = agora + 200L
        estadoPino.reiniciar(preservarFalhas = false)
    }

    /**
     * Nunca pausa nem desiste do pino atual: apenas registra a falha no log, volta para ESCOLHER e continua.
     */
    private fun registrarFalhaPino(
        pinoId: Int,
        score: Double,
        rivalTexto: String,
        distPx: Float,
        motivo: String,
        context: Context,
        tela: Bitmap
    ): String {
        falhasPino++
        val falhasAtuais = falhasPino
        LogRota.registrarPasso(
            context,
            "FALHA_PINO alvo=#$pinoId motivo=$motivo falhas=$falhasAtuais score=${"%.2f".format(score)} $rivalTexto (continuando no mesmo pino)",
            tela
        )

        iniciouCaminhadaEm = 0L
        fase = FaseNavegacao.ESCOLHER
        estadoPino.reiniciar(preservarFalhas = true)
        return formatarLog(
            fase.name, pinoId, score, rivalTexto, ultimoQtdVerde, ultimoRgbVerde, distPx,
            "$motivo (falhas=$falhasAtuais) -> ESCOLHER"
        )
    }

    private fun formatarRivalAvaliacao(av: AvaliacaoPino?): String {
        if (av == null) return "rival=nenhum"
        val rStr = if (av.rivalIndice != null) "#${av.rivalIndice}(${"%.2f".format(av.rivalCor)})" else "nenhum"
        return "corAlvo=${"%.2f".format(av.alvoCor)} rival=$rStr margem=${"%+.2f".format(av.margem)}"
    }

    private fun formatarRivalVerde(rv: ResultadoVerdeTemplate?): String {
        if (rv == null) return "rival=nenhum"
        val rStr = if (rv.rivalIndice != null) "#${rv.rivalIndice}(${"%.2f".format(rv.rivalScore)})" else "nenhum"
        return "scoreVerde=${"%.2f".format(rv.scoreAlvo)} rival=$rStr margem=${"%+.2f".format(rv.margem)}"
    }

    private fun formatarLog(
        faseNome: String,
        pinoId: Int,
        score: Double,
        rivalTexto: String,
        qtdVerde: Int,
        rgbVerde: Triple<Int, Int, Int>?,
        distPx: Float,
        extra: String? = null
    ): String {
        val distStr = if (distPx >= 0f) "%.1fpx".format(distPx) else "N/A"
        val scoreStr = "%.2f".format(score)
        val rgbStr = if (rgbVerde != null) {
            "rgb(${rgbVerde.first},${rgbVerde.second},${rgbVerde.third})"
        } else {
            "rgb(0,0,0)"
        }
        val diffStr = "%.2f".format(ultimaDiffMinimapa)
        val dirStr = if (direcaoIda) "IDA" else "VOLTA"
        val tolStr = "${ultimaToleranciaPx.toInt()}px"
        val base = "fase=$faseNome | pino=$pinoId | direcao=$dirStr | score=$scoreStr | $rivalTexto | verde=$qtdVerde | $rgbStr | dist=$distStr | tol=$tolStr | estavel=$quadrosDistEstavel | tentClique=$tentativaClique | falhas=$falhasPino | diffMini=$diffStr"
        return if (!extra.isNullOrEmpty()) "$base | $extra" else base
    }

    fun passoSoAlarme(tela: Bitmap, context: Context, uriSom: String, volume: Int): String {
        aplicarPendentes()
        ultimoUriSom = uriSom
        ultimoVolume = volume
        if (tela.height > tela.width) {
            return "Aguardando o jogo em paisagem"
        }
        val agora = SystemClock.uptimeMillis()
        val limiteSeg = (limiteAlarmeMs / 1000L).coerceAtLeast(1L)
        val st = BatalhaEngine.atualizar(tela, agora, context, limiteAlarmeMs)
        if (st.alarmeDevido) {
            BotService.tocarAlarme(context, uriSom, volume)
            return "ALARME: batalha há ${(agora - st.inicioLutaMs) / 1000L}s"
        }
        if (!st.lutaConfirmada) {
            BotService.pararAlarme()
        }
        return when {
            st.lutaConfirmada -> "Batalha: ${(agora - st.inicioLutaMs) / 1000L}s de ${limiteSeg}s"
            st.fase == BatalhaEngine.Fase.CONFIRMANDO_INICIO -> "Verificando..."
            else -> "Sem monstros na Batalha"
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
        aplicarPendentes()
        ultimoUriSom = uriSom
        ultimoVolume = volume
        if (!versaoRegistradaNoLog) {
            versaoRegistradaNoLog = true
            val linhaVersao = "VERSAO Rota ${BuildConfig.VERSION_NAME} | MODO=SO_NAVEGACAO | MARGEM_PINO=${"%.2f".format(Cal.MARGEM_PINO)} | LIMIAR_COR_ALVO=${"%.2f".format(Cal.LIMIAR_COR_ALVO)} | LIMIAR=${"%.2f".format(Cal.LIMIAR)} | LIMIAR_VERDE=${"%.2f".format(Cal.LIMIAR_VERDE)} | MARGEM_VERDE=${"%.2f".format(Cal.MARGEM_VERDE)} | RECLIQUE_SEM_MOVER=${RECLIQUE_SEM_MOVER_MS}ms | tela=${tela.width}x${tela.height}"
            LogRota.registrarPasso(context, linhaVersao, tela)
        }
        val texto = passoInterno(tela, context, tocar)
        LogRota.registrarPasso(context, texto, tela)
        return texto
    }

    private fun compararMinimapaComAnterior(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        cruzAbsX: Float,
        cruzAbsY: Float
    ): EstadoComparacaoMinimapa {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) {
            return EstadoComparacaoMinimapa(false, 0.0)
        }
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        val cruzRelX = (cruzAbsX - x0).toInt()
        val cruzRelY = (cruzAbsY - y0).toInt()
        val raioIgnorarCruz = max(8, (rw * 0.06f).toInt())

        val atual = DoubleArray(rw * rh)
        for (yy in 0 until rh) {
            val row = yy * rw
            for (xx in 0 until rw) {
                val ignorar = abs(xx - cruzRelX) <= raioIgnorarCruz && abs(yy - cruzRelY) <= raioIgnorarCruz
                atual[row + xx] = if (ignorar) 0.0 else cinza(px[row + xx])
            }
        }

        val anterior = ultimoRecorteMinimapa
        if (anterior == null || anterior.size != atual.size || ultimoRecorteMinimapaW != rw || ultimoRecorteMinimapaH != rh) {
            ultimoRecorteMinimapa = atual
            ultimoRecorteMinimapaW = rw
            ultimoRecorteMinimapaH = rh
            return EstadoComparacaoMinimapa(false, 0.0)
        }

        var somaDiff = 0.0
        var cont = 0
        for (yy in 0 until rh) {
            val row = yy * rw
            for (xx in 0 until rw) {
                val ignorar = abs(xx - cruzRelX) <= raioIgnorarCruz && abs(yy - cruzRelY) <= raioIgnorarCruz
                if (ignorar) continue
                somaDiff += abs(atual[row + xx] - anterior[row + xx])
                cont++
            }
        }

        ultimoRecorteMinimapa = atual
        ultimoRecorteMinimapaW = rw
        ultimoRecorteMinimapaH = rh

        val media = if (cont > 0) somaDiff / cont.toDouble() else 0.0
        val emMovimento = media > limiarMinimapaParado.toDouble()
        return EstadoComparacaoMinimapa(emMovimento, media)
    }

    private fun passoInterno(
        tela: Bitmap,
        context: Context,
        tocar: (Float, Float) -> Unit
    ): String {
        if (tela.height > tela.width) {
            return "CELULAR EM RETRATO (pausado)"
        }

        if (modelos.isEmpty()) {
            return "SEM ÍCONES CARREGADOS"
        }

        val x0 = (tela.width * Cal.MINIMAPA_X0).toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.MINIMAPA_Y0).toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.MINIMAPA_X1).toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.MINIMAPA_Y1).toInt().coerceIn(y0 + 1, tela.height)

        val cruz = acharCruz(tela, x0, y0, x1, y1)
            ?: return "SEM CRUZ"

        val cx = cruz.first.toFloat()
        val cy = cruz.second.toFloat()
        val agora = SystemClock.uptimeMillis()

        val estadoMini = compararMinimapaComAnterior(tela, x0, y0, x1, y1, cx, cy)
        ultimaDiffMinimapa = estadoMini.diffMedia
        if (estadoMini.emMovimento) {
            minimapaParadoDesde = 0L
            houveMovimentoDesdeClique = true
        } else if (minimapaParadoDesde == 0L) {
            minimapaParadoDesde = agora
        }
        val tempoParadoMs = if (minimapaParadoDesde > 0L) (agora - minimapaParadoDesde) else 0L

        if (alvo !in modelos.indices) {
            resetarInterno()
        }

        if (inicioBuscaPinoAtualEm == 0L) {
            inicioBuscaPinoAtualEm = agora
        }

        val modelo = modelos[alvo]
        val indice = modelo.indice
        val toleranciaChegada = calcularToleranciaChegada(tela.width)
        ultimaToleranciaPx = toleranciaChegada

        // 5) AVISO SEM PARAR (somente som após 3 minutos sem chegada no mesmo pino, sem pausar)
        if (!alarmeSemChegadaTocando && (agora - inicioBuscaPinoAtualEm) >= AVISO_ALARME_SEM_CHEGADA_MS) {
            alarmeSemChegadaTocando = true
            BotService.tocarAlarme(context, ultimoUriSom, ultimoVolume)
            LogRota.registrarPasso(
                context,
                "AVISO_ALARME_SEM_CHEGADA pino=#$indice (>3min sem chegada - continuando sem pausar)",
                tela
            )
        }

        if (agora < pausadoAte) {
            return formatarLog(
                fase.name, indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                ultimoQtdVerde, ultimoRgbVerde, ultimaDistVerde,
                "estabilizando"
            )
        }

        // =========================================================================
        // 2) FASE CAMINHANDO
        // =========================================================================
        if (fase == FaseNavegacao.CAMINHANDO) {
            // Se o tempo máximo do pino (tempoMaxPinoMs) estourar: não conta falha para pausar.
            // Apenas clica com o próximo deslocamento, reinicia o cronômetro da caminhada e continua.
            if (iniciouCaminhadaEm > 0L && (agora - iniciouCaminhadaEm) >= tempoMaxPinoMs) {
                val refRel = ultimoVerdeRel ?: ultimoPosPinoConhecidaRel
                if (refRel != null) {
                    val pxAlvo = (x0 + refRel.first).toFloat()
                    val pyAlvo = (y0 + refRel.second).toFloat()
                    val distAtual = hypot((pxAlvo - cx).toDouble(), (pyAlvo - cy).toDouble()).toFloat()
                    val toque = clicarComDeslocamento(
                        tela, x0, y0, x1, y1, pxAlvo, pyAlvo, indice, agora, context, tocar
                    )
                    iniciouCaminhadaEm = agora
                    tempoMaxPinoMs = calcularTempoMaxPinoMs(distAtual)
                    return formatarLog(
                        fase.name, indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                        ultimoQtdVerde, ultimoRgbVerde, distAtual,
                        "tempoMaxPinoMs estourado -> reclique=(${toque.first.toInt()},${toque.second.toInt()}) cronometro reiniciado"
                    )
                } else {
                    return registrarFalhaPino(
                        indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                        ultimaDistVerde, "tempoMaxPinoMs estourado sem referencia", context, tela
                    )
                }
            }

            val detVerde = acharPinoVerdeLocal(tela, x0, y0, x1, y1, modelo, ultimoVerdeRel)

            if (detVerde != null) {
                val verdeRel = detVerde.posicao
                val qtdVerde = detVerde.quantidade
                val rgbVerde = detVerde.rgbMedio
                vistoVerde = true
                ultimoQtdVerde = qtdVerde
                ultimoRgbVerde = rgbVerde
                quadrosSumidoVerde = 0
                ultimoVerdeRel = verdeRel
                ultimoPosPinoConhecidaRel = verdeRel

                val absX = (x0 + verdeRel.first).toFloat()
                val absY = (y0 + verdeRel.second).toFloat()
                val dist = hypot((absX - cx).toDouble(), (absY - cy).toDouble()).toFloat()
                atualizarEstabilidadeDistancia(dist)
                ultimaDistVerde = dist
                ultimaDist = dist
                if (menorDistVerde < 0f || dist < menorDistVerde) {
                    menorDistVerde = dist
                }

                // Chegou à zona do pino: avança imediatamente, sem exigir que o minimapa pare.
                // Isso também evita ficar preso quando o ícone fica sob a cruz ou animação de batalha.
                if (dist <= toleranciaChegada) {
                    proximoPino(agora, "CHEGADA_POR_PROXIMIDADE", context, tela)
                    return formatarLog(
                        "CHEGOU", indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                        qtdVerde, rgbVerde, dist,
                        "CHEGADA_POR_PROXIMIDADE: dentro da zona do pino"
                    )
                }

                // Regra de reclique (500 ms sem mover ou minimapa parado continuamente por 500 ms)
                var logClique = "clique=aguardando"
                val paradoParaReclique = (!estadoMini.emMovimento && tempoParadoMs >= RECLIQUE_SEM_MOVER_MS) ||
                    (quadrosDistEstavel >= 2)
                if (estadoMini.emMovimento && quadrosDistEstavel < 2) {
                    semProgresso = 0
                    logClique = "em_movimento"
                } else if (dist > toleranciaChegada && paradoParaReclique) {
                    val semMover500ms = !houveMovimentoDesdeClique && (agora - ultimoClique >= RECLIQUE_SEM_MOVER_MS)
                    val parouDeNovo500ms = houveMovimentoDesdeClique && (tempoParadoMs >= RECLIQUE_SEM_MOVER_MS || quadrosDistEstavel >= 2)
                    if (semMover500ms || parouDeNovo500ms) {
                        semProgresso++
                        cliquesVerde++
                        val toque = clicarComDeslocamento(
                            tela, x0, y0, x1, y1, absX, absY, indice, agora, context, tocar
                        )
                        logClique = "clique=OK(${toque.first.toInt()},${toque.second.toInt()}) reclique#$cliquesVerde"
                    }
                }

                return formatarLog(
                    fase.name, indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                    qtdVerde, rgbVerde, dist,
                    "seguindo verde | $logClique"
                )
            } else {
                ultimoQtdVerde = 0
                quadrosSumidoVerde++

                val distRef = ultimaDistVerde
                // Se o verde sumiu depois de o jogador entrar na zona do pino, conserva
                // a última distância válida. O ícone pode estar coberto pela cruz ou pela
                // sobreposição da batalha; não é necessário esperar o minimapa parar.
                val pinoOcultoJaNaZona = vistoVerde &&
                    distRef >= 0f && distRef <= toleranciaChegada

                if (pinoOcultoJaNaZona) {
                    val rgbConfirmado = ultimoRgbVerde
                    proximoPino(agora, "CHEGADA_PINO_OCULTO", context, tela)
                    return formatarLog(
                        "CHEGOU", indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                        0, rgbConfirmado, distRef,
                        "CHEGADA_PINO_OCULTO: ultima distancia valida dentro da zona; verde oculto"
                    )
                }

                val longeDaCruz = !pinoOcultoJaNaZona
                val posConhecida = ultimoPosPinoConhecidaRel
                val paradoParaReclique = (!estadoMini.emMovimento && tempoParadoMs >= RECLIQUE_SEM_MOVER_MS) ||
                    (quadrosDistEstavel >= 2)
                if (paradoParaReclique && longeDaCruz && posConhecida != null) {
                    val semMover500ms = !houveMovimentoDesdeClique && (agora - ultimoClique >= RECLIQUE_SEM_MOVER_MS)
                    val parouDeNovo500ms = houveMovimentoDesdeClique && (tempoParadoMs >= RECLIQUE_SEM_MOVER_MS || quadrosDistEstavel >= 2)
                    if (semMover500ms || parouDeNovo500ms) {
                        val pxAlvo = (x0 + posConhecida.first).toFloat()
                        val pyAlvo = (y0 + posConhecida.second).toFloat()
                        val toque = clicarComDeslocamento(
                            tela, x0, y0, x1, y1, pxAlvo, pyAlvo, indice, agora, context, tocar
                        )
                        return formatarLog(
                            fase.name, indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                            0, ultimoRgbVerde, distRef,
                            "verde ausente -> reclique=(" + toque.first.toInt() + "," + toque.second.toInt() + ")"
                        )
                    }
                }

                if (longeDaCruz && quadrosSumidoVerde >= 6) {
                    return registrarFalhaPino(
                        indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                        distRef, "pino sumiu longe da cruz ($quadrosSumidoVerde quadros)", context, tela
                    )
                }

                // Não volta para ESCOLHER quando o pino some junto à cruz. Mantém CAMINHANDO
                // e aguarda a confirmação; assim não procura eternamente o mesmo pino já alcançado.
                return formatarLog(
                    fase.name, indice, melhorScore, formatarRivalAvaliacao(ultimaAvaliacaoTemplate),
                    0, ultimoRgbVerde, distRef,
                    if (dentroZonaOcultacao) "pino sob a cruz: aguardando parada/confirmacao ($quadrosChegada/2)"
                    else "verde ausente ($quadrosSumidoVerde/6)"
                )
            }
        }

        // =========================================================================
        // 1) FASE ESCOLHER
        // =========================================================================
        val paradoParaClique = (!estadoMini.emMovimento && tempoParadoMs >= RECLIQUE_SEM_MOVER_MS) ||
            (quadrosDistEstavel >= 2)
        val podeClicarAgora = ultimoClique == 0L ||
            (paradoParaClique &&
                ((!houveMovimentoDesdeClique && agora - ultimoClique >= RECLIQUE_SEM_MOVER_MS) ||
                    (houveMovimentoDesdeClique && (tempoParadoMs >= RECLIQUE_SEM_MOVER_MS || quadrosDistEstavel >= 2))))

        // Verifica se o pino alvo já está verde no minimapa e validado pelo template
        val verdes = buscarComponentesVerdes(tela, x0, y0, x1, y1)
        if (verdes.isNotEmpty()) {
            val avaliadosVerdes = verdes.map { Pair(it, avaliarPinoVerdeComModelos(tela, x0, y0, x1, y1, it.posicao, modelo)) }
            val validosVerdes = avaliadosVerdes.filter { it.second.valido }
            if (validosVerdes.size == 1) {
                val verde = validosVerdes[0].first
                val avVerde = validosVerdes[0].second
                val rivalVerdeTxt = formatarRivalVerde(avVerde)
                val absVx = (x0 + verde.posicao.first).toFloat()
                val absVy = (y0 + verde.posicao.second).toFloat()
                val distVerde = hypot((absVx - cx).toDouble(), (absVy - cy).toDouble()).toFloat()
                atualizarEstabilidadeDistancia(distVerde)

                if (distVerde > toleranciaChegada && podeClicarAgora) {
                    val toque = clicarComDeslocamento(
                        tela, x0, y0, x1, y1, absVx, absVy, indice, agora, context, tocar
                    )
                    iniciarCaminhada(verde.posicao, distVerde, agora)
                    vistoVerde = true
                    ultimoQtdVerde = verde.quantidade
                    ultimoRgbVerde = verde.rgbMedio
                    LogRota.salvarQuadroDiagnostico(context, "pino_aceito_verde_$indice", tela)
                    return formatarLog(
                        fase.name, indice, avVerde.scoreAlvo, rivalVerdeTxt,
                        verde.quantidade, verde.rgbMedio, distVerde,
                        "ACEITO(verde) | clique=OK(${toque.first.toInt()},${toque.second.toInt()})"
                    )
                }
            }
        }

        // Busca o pino alvo por template (cinza) e valida em cor (avaliarAmbiguidadePino)
        val achado = acharPorTemplate(tela, x0, y0, x1, y1, modelo, cx, cy)
        val scoreAtual = melhorScore
        val avAtual = ultimaAvaliacaoTemplate
        val rivalTexto = formatarRivalAvaliacao(avAtual)

        if (achado == null) {
            if (avAtual != null && avAtual.recusado) {
                LogRota.salvarQuadroDiagnostico(context, "pino_recusado_${avAtual.motivo()}_$indice", tela)
                return formatarLog(
                    fase.name, indice, scoreAtual, rivalTexto,
                    0, null, -1f,
                    "RECUSADO(${avAtual.motivo()}) | clique=NAO"
                )
            }

            // 4) PINO NÃO ENCONTRADO NA FASE ESCOLHER: continua procurando indefinidamente; avisa a cada 10s
            if (agora - ultimoAvisoBuscaPinoEm >= AVISO_BUSCA_PINO_MS) {
                ultimoAvisoBuscaPinoEm = agora
                LogRota.registrarPasso(context, "procurando pino $indice", tela)
            }
            return formatarLog(
                fase.name, indice, scoreAtual, rivalTexto,
                0, null, -1f,
                "procurando pino $indice | clique=NAO"
            )
        }

        val dist = hypot((achado.first - cx).toDouble(), (achado.second - cy).toDouble()).toFloat()
        atualizarEstabilidadeDistancia(dist)
        ultimoPosPinoConhecidaRel = Pair(achado.first - x0, achado.second - y0)

        // Se a rotina iniciar já em cima/perto do pino atual, reconhece a posição
        // e passa ao próximo sem exigir uma parada adicional.
        if (dist <= toleranciaChegada) {
            proximoPino(agora, "JA_ESTAVA_NA_ZONA_DO_PINO", context, tela)
            return formatarLog(
                "CHEGOU", indice, scoreAtual, rivalTexto,
                0, null, dist,
                "JA_ESTAVA_NA_ZONA_DO_PINO: avancando sem clique"
            )
        }

        // Pino aceito! Clica nele com deslocamento da tentativa atual e vai para CAMINHANDO
        if (podeClicarAgora) {
            val toque = clicarComDeslocamento(
                tela, x0, y0, x1, y1, achado.first.toFloat(), achado.second.toFloat(),
                indice, agora, context, tocar
            )
            val relPos = Pair(achado.first - x0, achado.second - y0)
            iniciarCaminhada(relPos, dist, agora)
            LogRota.salvarQuadroDiagnostico(context, "pino_aceito_$indice", tela)
            return formatarLog(
                fase.name, indice, scoreAtual, rivalTexto,
                0, null, dist,
                "ACEITO | clique=OK(${toque.first.toInt()},${toque.second.toInt()})"
            )
        }

        return formatarLog(
            fase.name, indice, scoreAtual, rivalTexto,
            0, null, dist,
            "ACEITO | clique=aguardando_500ms"
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
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return emptyList()

        val px = IntArray(w * h)
        tela.getPixels(px, 0, w, x0, y0, w, h)

        val escala = tela.width / 2460f
        val escalaArea = (escala * escala).toDouble()
        val minQtd = (Cal.VERDE_AREA_MIN * escalaArea).toInt().coerceAtLeast(1)
        val maxQtd = (Cal.VERDE_AREA_MAX * escalaArea).toInt().coerceAtLeast(minQtd)
        val areaRef = Cal.VERDE_AREA_REF * escalaArea
        val larMin = (Cal.VERDE_LARG_MIN * escala).toInt().coerceAtLeast(1)
        val larMax = (Cal.VERDE_LARG_MAX * escala).toInt().coerceAtLeast(larMin)
        val altMin = (Cal.VERDE_ALT_MIN * escala).toInt().coerceAtLeast(1)
        val altMax = (Cal.VERDE_ALT_MAX * escala).toInt().coerceAtLeast(altMin)

        val total = w * h
        val mask = BooleanArray(total)
        for (i in 0 until total) mask[i] = ehPixelVerdeCaixa(px[i])

        val visitado = BooleanArray(total)
        val fila = IntArray(total)
        val achados = ArrayList<Pair<Double, DeteccaoVerde>>()

        for (start in 0 until total) {
            if (!mask[start] || visitado[start]) continue
            var cabeca = 0
            var cauda = 0
            fila[cauda++] = start
            visitado[start] = true
            var sx = 0L
            var sy = 0L
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var n = 0
            var mnX = Int.MAX_VALUE
            var mxX = -1
            var mnY = Int.MAX_VALUE
            var mxY = -1
            while (cabeca < cauda) {
                val cur = fila[cabeca++]
                val cy = cur / w
                val cx = cur - cy * w
                val p = px[cur]
                sx += cx
                sy += cy
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
                n++
                if (cx < mnX) mnX = cx
                if (cx > mxX) mxX = cx
                if (cy < mnY) mnY = cy
                if (cy > mxY) mxY = cy
                if (cx > 0 && mask[cur - 1] && !visitado[cur - 1]) { visitado[cur - 1] = true; fila[cauda++] = cur - 1 }
                if (cx < w - 1 && mask[cur + 1] && !visitado[cur + 1]) { visitado[cur + 1] = true; fila[cauda++] = cur + 1 }
                if (cy > 0 && mask[cur - w] && !visitado[cur - w]) { visitado[cur - w] = true; fila[cauda++] = cur - w }
                if (cy < h - 1 && mask[cur + w] && !visitado[cur + w]) { visitado[cur + w] = true; fila[cauda++] = cur + w }
            }
            if (n < minQtd || n > maxQtd) continue
            val bw = mxX - mnX + 1
            val bh = mxY - mnY + 1
            if (bw !in larMin..larMax || bh !in altMin..altMax) continue
            if (n.toFloat() / (bw * bh) < Cal.VERDE_PREENCHIMENTO_MIN) continue
            achados.add(
                Pair(
                    abs(n - areaRef),
                    DeteccaoVerde(
                        posicao = Pair((sx / n).toInt(), (sy / n).toInt()),
                        quantidade = n,
                        rgbMedio = Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
                    )
                )
            )
        }
        return achados.sortedBy { it.first }.map { it.second }
    }

    /** NCC em COR (máscara circular) do modelo m com o recorte de px cujo canto sup. esq. é (x,y). */
    private fun pontuarCor(px: IntArray, rw: Int, rh: Int, x: Int, y: Int, m: Modelo): Double {
        if (x < 0 || y < 0 || x + m.w > rw || y + m.h > rh) return -2.0
        if (m.normaRgb < 1e-3) return -2.0
        var soma = 0.0
        var n = 0
        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val bm = yy * m.w
            for (xx in 0 until m.w) {
                if (!m.mascaraPino[bm + xx]) continue
                val p = px[base + xx]
                soma += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
                n += 3
            }
        }
        if (n == 0) return -2.0
        val media = soma / n
        var cov = 0.0
        var vi = 0.0
        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val bm = yy * m.w
            for (xx in 0 until m.w) {
                val idx = bm + xx
                if (!m.mascaraPino[idx]) continue
                val p = px[base + xx]
                val a0 = ((p shr 16) and 0xFF) - media
                val a1 = ((p shr 8) and 0xFF) - media
                val a2 = (p and 0xFF) - media
                cov += a0 * m.rgbZ[idx * 3] + a1 * m.rgbZ[idx * 3 + 1] + a2 * m.rgbZ[idx * 3 + 2]
                vi += a0 * a0 + a1 * a1 + a2 * a2
            }
        }
        if (vi < 50.0) return -2.0
        return cov / (sqrt(vi) * m.normaRgb)
    }

    private fun melhorCorNoPonto(px: IntArray, rw: Int, rh: Int, bx: Int, by: Int, m: Modelo): Double {
        val j = Cal.PINO_RIVAL_JANELA_PX
        var melhor = -2.0
        for (dy in -j..j) for (dx in -j..j) {
            val s = pontuarCor(px, rw, rh, bx + dx, by + dy, m)
            if (s > melhor) melhor = s
        }
        return melhor
    }

    /**
     * No ponto (bx,by) (canto sup. esq. do melhor encaixe do alvo), compara o alvo com TODOS os outros
     * modelos. Ambíguo = algum outro pino fica a menos de Cal.MARGEM_PINO do alvo (NCC em cor).
     */
    private fun avaliarAmbiguidadePino(px: IntArray, rw: Int, rh: Int, bx: Int, by: Int, alvo: Modelo): AvaliacaoPino {
        val alvoCor = melhorCorNoPonto(px, rw, rh, bx, by, alvo)
        var rivalCor = -2.0
        var rivalIdx: Int? = null
        for (m in modelos) {
            if (m.indice == alvo.indice) continue
            val s = melhorCorNoPonto(px, rw, rh, bx, by, m)
            if (s > rivalCor) {
                rivalCor = s
                rivalIdx = m.indice
            }
        }
        val corBaixa = alvoCor < Cal.LIMIAR_COR_ALVO.toDouble()
        if (rivalIdx == null) return AvaliacaoPino(alvoCor, null, -1.0, alvoCor, false, corBaixa)
        val margem = alvoCor - rivalCor
        return AvaliacaoPino(alvoCor, rivalIdx, rivalCor, margem, margem < Cal.MARGEM_PINO.toDouble(), corBaixa)
    }

    private fun pontuarCinzaSimples(
        g: DoubleArray,
        rw: Int,
        x: Int,
        y: Int,
        m: Modelo
    ): Double {
        if (m.norma < 1e-3 || m.pixelsValidos < 8) return -1.0

        var somaImagem = 0.0
        var somaModelo = 0.0
        var quantidade = 0

        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val baseModelo = yy * m.w
            for (xx in 0 until m.w) {
                val indiceModelo = baseModelo + xx
                if (!m.valida[indiceModelo]) continue
                somaImagem += g[base + xx]
                somaModelo += m.z[indiceModelo]
                quantidade++
            }
        }

        if (quantidade < m.pixelsValidos * Cal.COBERTURA_MIN_PINO) return -1.0

        val mediaImagem = somaImagem / quantidade
        val mediaModelo = somaModelo / quantidade

        var covariancia = 0.0
        var varianciaImagem = 0.0
        var varianciaModelo = 0.0

        for (yy in 0 until m.h) {
            val base = (y + yy) * rw + x
            val baseModelo = yy * m.w
            for (xx in 0 until m.w) {
                val indiceModelo = baseModelo + xx
                if (!m.valida[indiceModelo]) continue
                val a = g[base + xx] - mediaImagem
                val b = m.z[indiceModelo] - mediaModelo
                covariancia += a * b
                varianciaImagem += a * a
                varianciaModelo += b * b
            }
        }

        if (varianciaImagem < 50.0 || varianciaModelo < 50.0) return -1.0

        return covariancia / (sqrt(varianciaImagem) * sqrt(varianciaModelo))
    }

    private fun avaliarPinoVerdeComModelos(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        verdeRel: Pair<Int, Int>,
        modeloAlvo: Modelo
    ): ResultadoVerdeTemplate {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0 || modelos.isEmpty()) {
            return ResultadoVerdeTemplate(false, -1.0, null, -1.0, -2.0)
        }

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        val g = DoubleArray(rw * rh) { cinza(px[it]) }

        val escala = tela.width / 2460f
        val winX0 = (verdeRel.first + (-24f * escala).toInt()).coerceIn(0, rw - 1)
        val winX1 = (verdeRel.first + (24f * escala).toInt()).coerceIn(winX0, rw - 1)
        val winY0 = (verdeRel.second + (-26f * escala).toInt()).coerceIn(0, rh - 1)
        val winY1 = (verdeRel.second + (14f * escala).toInt()).coerceIn(winY0, rh - 1)

        val winW = winX1 - winX0 + 1
        val winH = winY1 - winY0 + 1

        var scoreAlvo = -2.0
        var melhorRivalScore = Double.NEGATIVE_INFINITY
        var melhorRivalIndice: Int? = null

        for (m in modelos) {
            if (winW < m.w || winH < m.h) continue

            var melhorModelo = -2.0
            var bx = winX0
            var by = winY0

            val maxRefX = winX1 - m.w + 1
            val maxRefY = winY1 - m.h + 1

            var yy = winY0
            while (yy <= maxRefY) {
                var xx = winX0
                while (xx <= maxRefX) {
                    val s = pontuarCinzaSimples(g, rw, xx, yy, m)
                    if (s > melhorModelo) {
                        melhorModelo = s
                        bx = xx
                        by = yy
                    }
                    xx += 2
                }
                yy += 2
            }

            for (dy in -1..1) {
                for (dx in -1..1) {
                    val rx = (bx + dx).coerceIn(winX0, maxRefX)
                    val ry = (by + dy).coerceIn(winY0, maxRefY)
                    val s = pontuarCinzaSimples(g, rw, rx, ry, m)
                    if (s > melhorModelo) {
                        melhorModelo = s
                    }
                }
            }

            if (m.indice == modeloAlvo.indice) {
                scoreAlvo = melhorModelo
            } else if (melhorModelo > melhorRivalScore) {
                melhorRivalScore = melhorModelo
                melhorRivalIndice = m.indice
            }
        }

        val temRival = melhorRivalIndice != null
        val rivalFinal = if (temRival) melhorRivalScore else -1.0
        val margem = if (temRival) scoreAlvo - melhorRivalScore else scoreAlvo
        val valido = scoreAlvo >= Cal.LIMIAR_VERDE.toDouble() &&
            (!temRival || margem >= Cal.MARGEM_VERDE.toDouble())

        return ResultadoVerdeTemplate(
            valido = valido,
            scoreAlvo = scoreAlvo,
            rivalIndice = melhorRivalIndice,
            rivalScore = rivalFinal,
            margem = margem
        )
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
        var componenteOk = false
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
            var bMinX = Int.MAX_VALUE
            var bMaxX = -1
            var bMinY = Int.MAX_VALUE
            var bMaxY = -1

            while (cabeca < cauda) {
                val curr = fila[cabeca++]
                val cyLocal = curr / subW
                val cxLocal = curr - cyLocal * subW
                if (cxLocal < bMinX) bMinX = cxLocal
                if (cxLocal > bMaxX) bMaxX = cxLocal
                if (cyLocal < bMinY) bMinY = cyLocal
                if (cyLocal > bMaxY) bMaxY = cyLocal
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

            run {
                val escTrack = tela.width / 2460f
                val areaMin = (Cal.VERDE_AREA_RASTREIO_MIN * escTrack * escTrack).toInt().coerceAtLeast(4)
                val areaMax = (Cal.VERDE_AREA_MAX * escTrack * escTrack).toInt()
                val bw = bMaxX - bMinX + 1
                val bh = bMaxY - bMinY + 1
                val larMax = (Cal.VERDE_LARG_MAX * escTrack * 1.3f).toInt()
                val altMax = (Cal.VERDE_ALT_MAX * escTrack * 1.3f).toInt()
                if (count < areaMin || count > areaMax) return@run
                if (bw > larMax || bh > altMax) return@run
                if (count.toFloat() / (bw * bh) < Cal.VERDE_PREENCHIMENTO_RASTREIO_MIN) return@run
                componenteOk = true
            }
            if (!componenteOk) continue
            componenteOk = false

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

    @Deprecated("Use BatalhaEngine.atualizar; esta é só a leitura instantânea, sem confirmação por quadros")
    fun temMonstro(tela: Bitmap): Boolean = BatalhaEngine.medir(tela).presente

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
        ultimaAvaliacaoTemplate = null
        val rw = x1 - x0
        val rh = y1 - y0

        if (
            rw < m.w ||
            rh < m.h ||
            m.norma < 1e-3 ||
            m.pixelsValidos < 8
        ) {
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
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val verde = (p shr 8) and 0xFF
            val blue = p and 0xFF
            val ehLaranjaRota = r >= 175 && verde in 85..165 && blue <= 80
            val baseCinza = cinza(p)
            g[i] = if (ehLaranjaRota) baseCinza * Cal.FATOR_LARANJA_ROTA.toDouble() else baseCinza
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
        val candidatos = ArrayList<Triple<Double, Int, Int>>()

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
                if (score >= Cal.LIMIAR) candidatos.add(Triple(score, x, y))
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

        if (melhor >= Cal.LIMIAR) {
            fun refinar(sx: Int, sy: Int, sScore: Double): Triple<Double, Int, Int> {
                var bs = sScore
                var rx = sx
                var ry = sy
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = sx + dx
                        val yy = sy + dy
                        if (xx < 0 || yy < 0 || xx > rw - m.w || yy > rh - m.h) continue
                        val s = if (regiaoQuasePreta(px, rw, xx, yy, m.w, m.h)) -1.0 else pontuar(g, ocultoPelaCruz, rw, xx, yy, m)
                        if (s > bs) {
                            bs = s
                            rx = xx
                            ry = yy
                        }
                    }
                }
                return Triple(bs, rx, ry)
            }

            val tentados = ArrayList<Triple<Double, Int, Int>>()
            tentados.add(Triple(melhor, bx, by))
            val distMin = (m.w / 2).coerceAtLeast(2)
            for (c in candidatos.sortedByDescending { it.first }) {
                if (tentados.size >= Cal.MAX_CANDIDATOS_PINO) break
                if (tentados.any { abs(it.second - c.second) < distMin && abs(it.third - c.third) < distMin }) continue
                val r = refinar(c.second, c.third, c.first)
                if (r.first < Cal.LIMIAR) continue
                tentados.add(r)
            }

            for ((sc, tx, ty) in tentados) {
                val centro = Pair(x0 + tx + m.w / 2, y0 + ty + m.h / 2)
                val amb = avaliarAmbiguidadePino(px, rw, rh, tx, ty, m)
                ultimaAvaliacaoTemplate = amb
                if (!amb.recusado) {
                    melhorScore = sc
                    return centro
                }
            }
            return null
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

        if (quantidade < total * Cal.COBERTURA_MIN_PINO) {
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