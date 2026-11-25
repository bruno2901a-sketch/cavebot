package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

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

private class EstadoPino {
    var quadrosChegada = 0
    var quadrosPertoDaCruz = 0
    var quadrosSumidoVerde = 0
    var ultimaDist = -1f
    var ultimaDistVerde = -1f
    var menorDistVerde = -1f
    var semProgresso = 0
    var cliquesVerde = 0
    var naoVisto = 0
    var falhasPino = 0
    var quadrosQuaseLimiar = 0
    var ultimoCandidatoQuaseLimiar: Pair<Int, Int>? = null
    var candidatoConsistente: Pair<Int, Int>? = null
    var verdeVeioDeTemplate = false
    var ultimoVerdeRel: Pair<Int, Int>? = null
    var ultimoQtdVerde = 0
    var ultimoRgbVerde: Triple<Int, Int, Int>? = null
    var vistoVerde = false
    var retomarVerdeAte = 0L
    var resyncUsadoNestePino = false
    var nivelRecuperacaoPino = 0
    var ultimoPosPinoConhecidaRel: Pair<Int, Int>? = null
    var ultimoPosPinoEraVerde = false
    var voltouAoPinoAnteriorParaTentar = false

    fun reiniciar(preservarFalhas: Boolean = false) {
        val falhasAnteriores = falhasPino
        val voltouAnterior = voltouAoPinoAnteriorParaTentar
        quadrosChegada = 0
        quadrosPertoDaCruz = 0
        quadrosSumidoVerde = 0
        ultimaDist = -1f
        ultimaDistVerde = -1f
        menorDistVerde = -1f
        semProgresso = 0
        cliquesVerde = 0
        naoVisto = 0
        falhasPino = if (preservarFalhas) falhasAnteriores else 0
        quadrosQuaseLimiar = 0
        ultimoCandidatoQuaseLimiar = null
        candidatoConsistente = null
        verdeVeioDeTemplate = false
        ultimoVerdeRel = null
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
        retomarVerdeAte = 0L
        resyncUsadoNestePino = false
        nivelRecuperacaoPino = 0
        ultimoPosPinoConhecidaRel = null
        ultimoPosPinoEraVerde = false
        voltouAoPinoAnteriorParaTentar = if (preservarFalhas) voltouAnterior else false
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

    // ---- Threads ----
    // Todo o estado mutável abaixo (modelos, fase, alvo, estadoPino, ...) pertence à thread de análise, a mesma que
    // chama passo()/passoSoAlarme(). Chamadas vindas de outras threads (UI/serviço: resetar, retomarPausaPinoPerdido,
    // carregar) NÃO tocam esse estado: entram nesta fila e são aplicadas, na ordem, no início do próximo passo.
    // Não usamos lock em passo() porque ele pode durar dezenas de segundos (autoloot) e travaria a thread principal.
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
    private var iniciouCaminhadaEm = 0L
    private var tempoMaxPinoMs = 15000L
    private var pausadoAte = 0L
    private var pausadoPorPinoPerdido = false
    private var alertaHabilitado = true
    private var ultimoUriSom = ""
    private var ultimoVolume = 80
    private val estadoPino = EstadoPino()
    private var quadrosChegada: Int
        get() = estadoPino.quadrosChegada
        set(v) { estadoPino.quadrosChegada = v }
    private var quadrosPertoDaCruz: Int
        get() = estadoPino.quadrosPertoDaCruz
        set(v) { estadoPino.quadrosPertoDaCruz = v }
    private var quadrosSumidoVerde: Int
        get() = estadoPino.quadrosSumidoVerde
        set(v) { estadoPino.quadrosSumidoVerde = v }
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
    private var naoVisto: Int
        get() = estadoPino.naoVisto
        set(v) { estadoPino.naoVisto = v }
    private var falhasPino: Int
        get() = estadoPino.falhasPino
        set(v) { estadoPino.falhasPino = v }
    private var quadrosQuaseLimiar: Int
        get() = estadoPino.quadrosQuaseLimiar
        set(v) { estadoPino.quadrosQuaseLimiar = v }
    private var ultimoCandidatoQuaseLimiar: Pair<Int, Int>?
        get() = estadoPino.ultimoCandidatoQuaseLimiar
        set(v) { estadoPino.ultimoCandidatoQuaseLimiar = v }
    private var candidatoConsistente: Pair<Int, Int>?
        get() = estadoPino.candidatoConsistente
        set(v) { estadoPino.candidatoConsistente = v }
    private var quadrosSemRota = 0
    private var ignorarSemRotaAte = 0L
    private var estavaEmLuta = false
    private var tempoInicioLuta = 0L
    private var estavaEmLutaCaminhando = false
    private var verdeRelAntesDaLuta: Pair<Int, Int>? = null
    private var retomarVerdeAte: Long
        get() = estadoPino.retomarVerdeAte
        set(v) { estadoPino.retomarVerdeAte = v }
    private var verdeVeioDeTemplate: Boolean
        get() = estadoPino.verdeVeioDeTemplate
        set(v) { estadoPino.verdeVeioDeTemplate = v }
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
    private var indiceCliqueVariavel = 0
    private var fimLutaEm = 0L
    private var versaoRegistradaNoLog = false
    private var ultimoConfirmado: Int? = null
    private var timestampsResync: ArrayDeque<Long> = ArrayDeque()

    private var ultimoRecorteMinimapa: DoubleArray? = null
    private var ultimoRecorteMinimapaW = 0
    private var ultimoRecorteMinimapaH = 0
    private var minimapaParadoDesde = 0L
    private var ultimaDiffMinimapa = 0.0
    private var resyncUsadoNestePino: Boolean
        get() = estadoPino.resyncUsadoNestePino
        set(v) { estadoPino.resyncUsadoNestePino = v }
    private var nivelRecuperacaoPino: Int
        get() = estadoPino.nivelRecuperacaoPino
        set(v) { estadoPino.nivelRecuperacaoPino = v }
    private var ultimoPosPinoConhecidaRel: Pair<Int, Int>?
        get() = estadoPino.ultimoPosPinoConhecidaRel
        set(v) { estadoPino.ultimoPosPinoConhecidaRel = v }
    private var ultimoPosPinoEraVerde: Boolean
        get() = estadoPino.ultimoPosPinoEraVerde
        set(v) { estadoPino.ultimoPosPinoEraVerde = v }
    private var quadrosSemRotaSeguidos = 0
    private var ultimaRecusaTemplate: String? = null

    private var salvouQuadroInicioLuta = false
    private var salvouQuadroInicioAlarmeLuta = false

    private var recortesCasasLuta: Array<DoubleArray?> = arrayOfNulls(8)
    private var variacaoMaxCasasLuta: DoubleArray = DoubleArray(8)
    private var ultimoBarraTituloAbs: Pair<Int, Int>? = null
    private var ultimoLogResumoEm = 0L

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
        // A troca dos modelos e o reset acontecem juntos, na thread de análise (um passo em andamento
        // não vê modelos novos com alvo/estado antigos).
        pendentes.add {
            modelos = novos
            resetarInterno()
        }
        BotService.pararAlarme()
    }

    private fun dispararToqueSeguro(
        larguraTela: Int,
        alturaTela: Int,
        x: Float,
        y: Float,
        tocar: (Float, Float) -> Unit
    ): Pair<Float, Float> {
        val minX = 28f.coerceAtMost((larguraTela - 1).toFloat())
        val maxX = (larguraTela * 0.985f - 20f).coerceAtLeast(minX)
        val minY = 28f.coerceAtMost((alturaTela - 1).toFloat())
        val maxY = (alturaTela - 28f).coerceAtLeast(minY)
        val sx = x.coerceIn(minX, maxX)
        val sy = y.coerceIn(minY, maxY)
        tocar(sx, sy)
        return Pair(sx, sy)
    }

    private fun anexarTempoPosLuta(logClique: String, horarioClique: Long): String {
        if (fimLutaEm <= 0L) return logClique
        val deltaMs = (horarioClique - fimLutaEm).coerceAtLeast(0L)
        fimLutaEm = 0L
        return "$logClique | luta->clique=${deltaMs}ms"
    }

    /**
     * Seguro para chamar de qualquer thread: o reset de verdade roda na thread de análise, antes do próximo passo.
     * O que é thread-safe (alarme, contadores @Volatile mostrados no overlay) é zerado já, como antes.
     */
    fun resetar() {
        BotService.pararAlarme()
        lutas = 0
        autoloots = 0
        chegadasConfirmadas = 0
        pulosPino = 0
        pendentes.add { resetarInterno() }
    }

    // Só na thread de análise (aplicarPendentes / passoInterno).
    private fun resetarInterno() {
        fase = FaseNavegacao.ESCOLHER
        alvo = 0
        direcaoIda = true
        ultimoClique = 0L
        iniciouCaminhadaEm = 0L
        tempoMaxPinoMs = 15000L
        pausadoAte = 0L
        pausadoPorPinoPerdido = false
        estadoPino.reiniciar(preservarFalhas = false)
        quadrosSemRota = 0
        ignorarSemRotaAte = 0L
        estavaEmLuta = false
        tempoInicioLuta = 0L
        estavaEmLutaCaminhando = false
        verdeRelAntesDaLuta = null
        indiceCliqueVariavel = 0
        fimLutaEm = 0L
        versaoRegistradaNoLog = false
        ultimoConfirmado = null
        timestampsResync.clear()
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
        quadrosSemRotaSeguidos = 0
        BatalhaEngine.resetar()
        salvouQuadroInicioLuta = false
        salvouQuadroInicioAlarmeLuta = false
        recortesCasasLuta = arrayOfNulls(8)
        variacaoMaxCasasLuta = DoubleArray(8)
        ultimoBarraTituloAbs = null
        ultimoLogResumoEm = 0L
        melhorScore = 0.0
        BotService.pararAlarme()
    }

    private fun calcularTempoMaxPinoMs(distPx: Float): Long {
        val pxCasa = pixelsPorCasa.coerceAtLeast(1f)
        val casasReais = if (distPx > 0f) (distPx / pxCasa) else 1f
        val baseMs = (casasReais * Cal.TEMPO_POR_CASA_MS.toFloat()).toLong()
        return (baseMs * Cal.FATOR_TEMPO_MAX_PINO) + Cal.FOLGA_TEMPO_MAX_PINO_MS
    }

    private fun iniciarCaminhada(
        relPos: Pair<Int, Int>,
        distAtual: Float,
        agora: Long,
        veioDeTemplate: Boolean
    ) {
        estadoPino.reiniciar(preservarFalhas = true)
        fase = FaseNavegacao.CAMINHANDO
        ultimoVerdeRel = relPos
        ultimoPosPinoConhecidaRel = relPos
        ultimoPosPinoEraVerde = veioDeTemplate
        ultimoQtdVerde = 0
        ultimoRgbVerde = null
        vistoVerde = false
        verdeVeioDeTemplate = veioDeTemplate
        ultimaDistVerde = distAtual
        menorDistVerde = distAtual
        ultimaDist = distAtual
        iniciouCaminhadaEm = agora
        tempoMaxPinoMs = calcularTempoMaxPinoMs(distAtual)
    }

    private fun registrarLogAlvo(context: Context?, tela: Bitmap?) {
        val idPino = if (alvo in modelos.indices) modelos[alvo].indice else alvo
        val dirStr = if (direcaoIda) "IDA" else "VOLTA"
        val msg = "ALVO $idPino DIRECAO $dirStr"
        val ctx = context
        if (ctx != null && tela != null) {
            LogRota.registrarPasso(ctx, msg, tela)
        }
    }

    private fun registrarResumoContadores(context: Context?, tela: Bitmap?) {
        val ctx = context ?: return
        val msg = "RESUMO_CONTADORES chegadas=$chegadasConfirmadas pulos=$pulosPino lutas=$lutas loots=$autoloots"
        LogRota.registrarPasso(ctx, msg, tela)
    }

    /** Seguro para chamar de qualquer thread: a retomada é aplicada pela thread de análise antes do próximo passo. */
    fun retomarPausaPinoPerdido() {
        pendentes.add { retomarPausaPinoPerdidoInterno() }
    }

    private fun retomarPausaPinoPerdidoInterno() {
        pausadoPorPinoPerdido = false
        pausadoAte = 0L
        estadoPino.reiniciar(false)
        // O estado do pino foi zerado: sem isto a fase seguia CAMINHANDO com o cronômetro da caminhada antiga
        // (estourava tempoMaxPinoMs de imediato) e a comparação do minimapa usava um recorte velho.
        fase = FaseNavegacao.ESCOLHER
        iniciouCaminhadaEm = 0L
        ultimoRecorteMinimapa = null
        ultimoRecorteMinimapaW = 0
        ultimoRecorteMinimapaH = 0
        minimapaParadoDesde = 0L
    }

    private fun voltarPinoAnteriorRespeitandoDirecao() {
        val tamanho = modelos.size
        if (tamanho <= 1) {
            alvo = 0
            return
        }
        if (direcaoIda) {
            if (alvo > 0) {
                alvo--
            } else {
                direcaoIda = false
                alvo = 1.coerceAtMost(tamanho - 1)
            }
        } else {
            if (alvo < tamanho - 1) {
                alvo++
            } else {
                direcaoIda = true
                alvo = (tamanho - 2).coerceAtLeast(0)
            }
        }
    }

    private fun pausarPorPinoPerdido(pinoId: Int, context: Context?, tela: Bitmap?) {
        pausadoPorPinoPerdido = true
        pausadoAte = Long.MAX_VALUE
        fase = FaseNavegacao.ESCOLHER
        if (context != null) {
            if (alertaHabilitado) BotService.tocarAlarme(context, ultimoUriSom, ultimoVolume)
            LogRota.registrarPasso(context, "PAUSADO_POR_PINO_PERDIDO pino=$pinoId", tela)
        }
    }

    private fun tratarEsgotamentoPinoSemPulo(
        agora: Long,
        pinoId: Int,
        context: Context?,
        tela: Bitmap?
    ): Boolean {
        if (Cal.PERMITIR_PULO) {
            proximoPino(agora, "PULOU_POR_FALHAS", context, tela)
            return true
        }
        val existePinoAnteriorReal = modelos.size > 1 &&
            ((direcaoIda && alvo > 0) || (!direcaoIda && alvo < modelos.size - 1))
        if (!estadoPino.voltouAoPinoAnteriorParaTentar && existePinoAnteriorReal) {
            estadoPino.voltouAoPinoAnteriorParaTentar = true
            val idAntes = if (alvo in modelos.indices) modelos[alvo].indice else alvo
            voltarPinoAnteriorRespeitandoDirecao()
            val idDepois = if (alvo in modelos.indices) modelos[alvo].indice else alvo
            if (context != null) {
                LogRota.registrarPasso(context, "RECUPERACAO_VOLTOU_PINO de=$idAntes para=$idDepois", tela)
            }
            fase = FaseNavegacao.ESCOLHER
            iniciouCaminhadaEm = 0L
            pausadoAte = agora + 200L
            estadoPino.reiniciar(preservarFalhas = true)
            falhasPino = 0
            registrarLogAlvo(context, tela)
            return true
        }
        pausarPorPinoPerdido(pinoId, context, tela)
        return false
    }

    private fun chegouNoPino(
        dist: Float,
        estadoMini: EstadoComparacaoMinimapa,
        tempoParadoMs: Long,
        vistoVerdeParam: Boolean,
        verdeVeioDeTemplateParam: Boolean
    ): Boolean {
        val toleranciaCasaPx = Cal.TOLERANCIA_CHEGADA_CASAS * pixelsPorCasa
        val minimapaParadoSuficiente = !estadoMini.emMovimento && tempoParadoMs >= Cal.TEMPO_MINIMO_PARADO_CHEGADA_MS
        val dentroTolerancia = dist >= 0f && dist <= toleranciaCasaPx
        if (dentroTolerancia && minimapaParadoSuficiente && (vistoVerdeParam || verdeVeioDeTemplateParam)) {
            quadrosChegada++
            return quadrosChegada >= 2
        }
        quadrosChegada = 0
        return false
    }

    private fun proximoPino(
        agora: Long,
        motivoAvanco: String,
        context: Context? = null,
        tela: Bitmap? = null
    ) {
        val tamanho = modelos.size
        if (tamanho <= 0) return

        val pinoAntesId = if (alvo in modelos.indices) modelos[alvo].indice else alvo

        if (motivoAvanco == "CHEGADA_CONFIRMADA") {
            chegadasConfirmadas++
        } else {
            pulosPino++
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
        if (context != null) {
            LogRota.registrarPasso(
                context,
                "PROXIMO_PINO motivo=$motivoAvanco de=$pinoAntesId para=$pinoDepoisId chegadas=$chegadasConfirmadas pulos=$pulosPino",
                tela
            )
        }

        registrarLogAlvo(context, tela)
        registrarResumoContadores(context, tela)

        fase = FaseNavegacao.ESCOLHER
        ultimoClique = 0L
        iniciouCaminhadaEm = 0L
        tempoMaxPinoMs = 15000L
        pausadoAte = agora + 200L
        pausadoPorPinoPerdido = false
        val manterVoltou = motivoAvanco != "CHEGADA_CONFIRMADA" && estadoPino.voltouAoPinoAnteriorParaTentar
        estadoPino.reiniciar(preservarFalhas = false)
        if (manterVoltou) estadoPino.voltouAoPinoAnteriorParaTentar = true
        quadrosSemRota = 0
        quadrosSemRotaSeguidos = 0
        ignorarSemRotaAte = 0L
        estavaEmLutaCaminhando = false
        verdeRelAntesDaLuta = null
        indiceCliqueVariavel = 0
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
        motivo: String,
        context: Context? = null,
        tela: Bitmap? = null
    ): String {
        falhasPino++
        if (context != null) {
            LogRota.registrarPasso(
                context,
                "FALHA_PINO pino=$pinoId motivo=$motivo falhas=$falhasPino/${Cal.MAX_FALHAS_PINO}",
                tela
            )
        }
        if (falhasPino >= Cal.MAX_FALHAS_PINO) {
            val falhasNoLog = falhasPino
            // true = continua tentando (voltou ao pino anterior, ou pulou se PERMITIR_PULO); false = pausou por pino perdido
            val seguiu = tratarEsgotamentoPinoSemPulo(agora, pinoId, context, tela)
            val acao = when {
                !seguiu -> "PAUSADO_POR_PINO_PERDIDO (alarme, aguardando retomar)"
                Cal.PERMITIR_PULO -> "PULOU_PARA_PROXIMO_PINO"
                else -> "VOLTOU_AO_PINO_ANTERIOR (alvo agora #${if (alvo in modelos.indices) modelos[alvo].indice else alvo})"
            }
            return formatarLog(
                faseNome, pinoId, score, qtdVerde, rgbVerde, distPx, progressoAntesReset,
                "$motivo (falhas=$falhasNoLog/${Cal.MAX_FALHAS_PINO}) -> $acao"
            )
        }

        iniciouCaminhadaEm = 0L
        fase = FaseNavegacao.ESCOLHER
        estadoPino.reiniciar(preservarFalhas = true)
        quadrosSemRota = 0
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
            val ehTextoMensagem = r >= 250 && g >= 250 && b >= 250
            if (ehTextoMensagem) {
                pixelsTextoClaro++
            }
        }

        return pixelsTextoClaro in 100..600
    }

    @Volatile
    var intervaloLootMs: Long = 100L

    @Volatile
    var maxAutolootMs: Long = 40000L

    private fun esperarFatiado(
        totalMs: Long,
        inicioMs: Long,
        context: Context,
        tela: Bitmap,
        cliquesCount: Int
    ): Boolean {
        var restante = totalMs
        while (restante > 0L) {
            if (cancelarAutoloot) {
                val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                    tela
                )
                return false
            }
            if (SystemClock.uptimeMillis() - inicioMs >= maxAutolootMs) {
                LogRota.registrarPasso(context, "AUTOLOOT limite de tempo", tela)
                val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=nao",
                    tela
                )
                return false
            }
            val passoEspera = restante.coerceAtMost(50L)
            SystemClock.sleep(passoEspera)
            restante -= passoEspera
        }
        if (cancelarAutoloot) {
            val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
            LogRota.registrarPasso(
                context,
                "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                tela
            )
            return false
        }
        if (SystemClock.uptimeMillis() - inicioMs >= maxAutolootMs) {
            LogRota.registrarPasso(context, "AUTOLOOT limite de tempo", tela)
            val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
            LogRota.registrarPasso(
                context,
                "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=nao",
                tela
            )
            return false
        }
        return true
    }

    private fun obterCentroPersonagemJogo(larguraTela: Int, alturaTela: Int): Pair<Float, Float> {
        if (Cal.PONTOS_CORPO.isEmpty()) {
            return Pair(larguraTela * 0.525f, alturaTela * 0.500f)
        }
        var somaX = 0f
        var somaY = 0f
        for (p in Cal.PONTOS_CORPO) {
            somaX += p.first * larguraTela
            somaY += p.second * alturaTela
        }
        return Pair(somaX / Cal.PONTOS_CORPO.size, somaY / Cal.PONTOS_CORPO.size)
    }

    private fun extrairRecorteCasa(
        tela: Bitmap,
        centroX: Float,
        centroY: Float,
        dxCasa: Int,
        dyCasa: Int,
        ladoCasaPx: Int
    ): DoubleArray {
        val half = (ladoCasaPx / 2).coerceAtLeast(6)
        val cx = (centroX + dxCasa * ladoCasaPx).toInt()
        val cy = (centroY + dyCasa * ladoCasaPx).toInt()
        val x0 = (cx - half).coerceIn(0, (tela.width - 1).coerceAtLeast(0))
        val y0 = (cy - half).coerceIn(0, (tela.height - 1).coerceAtLeast(0))
        val x1 = (cx + half).coerceIn((x0 + 1).coerceAtMost(tela.width), tela.width)
        val y1 = (cy + half).coerceIn((y0 + 1).coerceAtMost(tela.height), tela.height)
        val rw = (x1 - x0).coerceAtLeast(1)
        val rh = (y1 - y0).coerceAtLeast(1)
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        return DoubleArray(px.size) { idx -> cinza(px[idx]) }
    }

    private fun calcularDiferencaMediaRecorte(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n <= 0) return 0.0
        var soma = 0.0
        for (i in 0 until n) {
            soma += abs(a[i] - b[i])
        }
        return soma / n.toDouble()
    }

    private fun atualizarMonitoramentoCasasDuranteLuta(tela: Bitmap) {
        if (tela.width <= 0 || tela.height <= 0) return
        val centro = obterCentroPersonagemJogo(tela.width, tela.height)
        val ladoCasa = tamanhoCasaPx.toInt().coerceIn(16, 260)
        val deltas = arrayOf(
            Pair(-1, -1), Pair(0, -1), Pair(1, -1),
            Pair(-1, 0),              Pair(1, 0),
            Pair(-1, 1),  Pair(0, 1),  Pair(1, 1)
        )
        for (i in 0 until 8) {
            val d = deltas[i]
            val atual = extrairRecorteCasa(tela, centro.first, centro.second, d.first, d.second, ladoCasa)
            val anterior = recortesCasasLuta[i]
            if (anterior == null) {
                recortesCasasLuta[i] = atual
            } else {
                val diff = calcularDiferencaMediaRecorte(anterior, atual)
                val novaVar = max(variacaoMaxCasasLuta[i], diff)
                variacaoMaxCasasLuta[i] = novaVar.coerceAtMost(Cal.PISO_ANIMACAO_MAX_PX)
                recortesCasasLuta[i] = atual
            }
        }
    }

    private fun prepararCapturaCasasAntesLuta(tela: Bitmap) {
        if (tela.width <= 0 || tela.height <= 0) return
        val centro = obterCentroPersonagemJogo(tela.width, tela.height)
        val ladoCasa = tamanhoCasaPx.toInt().coerceIn(16, 260)
        val deltas = arrayOf(
            Pair(-1, -1), Pair(0, -1), Pair(1, -1),
            Pair(-1, 0),              Pair(1, 0),
            Pair(-1, 1),  Pair(0, 1),  Pair(1, 1)
        )
        variacaoMaxCasasLuta = DoubleArray(8)
        for (i in 0 until 8) {
            val d = deltas[i]
            recortesCasasLuta[i] = extrairRecorteCasa(tela, centro.first, centro.second, d.first, d.second, ladoCasa)
        }
    }

    private fun janelaLootAbertaPorEscuro(quadro: Bitmap): Boolean {
        val x0 = (quadro.width * 0.48f).toInt().coerceIn(0, quadro.width - 1)
        val y0 = (quadro.height * 0.12f).toInt().coerceIn(0, quadro.height - 1)
        val x1 = (quadro.width * 0.78f).toInt().coerceIn(x0 + 1, quadro.width)
        val y1 = (quadro.height * 0.42f).toInt().coerceIn(y0 + 1, quadro.height)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return false
        val px = IntArray(rw * rh)
        quadro.getPixels(px, 0, rw, x0, y0, rw, rh)
        var escuros = 0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r < 45 && g < 45 && b < 55) {
                escuros++
            }
        }
        val razao = escuros.toFloat() / px.size.toFloat()
        return razao >= 0.15f
    }

    private fun acharBarraTituloLoot(quadro: Bitmap): Pair<Int, Int>? {
        val refX = (refBarraTituloX * quadro.width).toInt().coerceIn(0, quadro.width - 1)
        val refY = (refBarraTituloY * quadro.height).toInt().coerceIn(0, quadro.height - 1)
        val base = ultimoBarraTituloAbs ?: Pair(refX, refY)
        val raioX = (quadro.width * 0.08f).toInt().coerceAtLeast(24)
        val raioY = (quadro.height * 0.06f).toInt().coerceAtLeast(18)

        val minX = (base.first - raioX).coerceIn(1, quadro.width - 2)
        val maxX = (base.first + raioX).coerceIn(minX + 1, quadro.width - 2)
        val minY = (base.second - raioY).coerceIn(1, quadro.height - 2)
        val maxY = (base.second + raioY).coerceIn(minY + 1, quadro.height - 2)

        val rw = maxX - minX
        val rh = maxY - minY
        if (rw <= 8 || rh <= 4) return null
        val px = IntArray(rw * rh)
        quadro.getPixels(px, 0, rw, minX, minY, rw, rh)

        var melhorPontuacao = 0
        var melhorX = -1
        var melhorY = -1

        for (yy in 0 until rh) {
            val row = yy * rw
            var clarosNaLinha = 0
            for (xx in 0 until rw) {
                val p = px[row + xx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 150 && g > 140 && b > 120) {
                    clarosNaLinha++
                }
            }
            if (clarosNaLinha >= 12) {
                val distBase = hypot(((minX + rw / 2) - base.first).toDouble(), ((minY + yy) - base.second).toDouble())
                val score = clarosNaLinha - (distBase * 0.25).toInt()
                if (score > melhorPontuacao) {
                    melhorPontuacao = score
                    melhorX = minX + rw / 2
                    melhorY = minY + yy
                }
            }
        }

        return if (melhorPontuacao >= 12 && melhorX >= 0 && melhorY >= 0) {
            ultimoBarraTituloAbs = Pair(melhorX, melhorY)
            Pair(melhorX, melhorY)
        } else {
            null
        }
    }

    private fun pontoLootTemItem(quadro: Bitmap, lx: Int, ly: Int): Boolean {
        val raio = 8
        val x0 = (lx - raio).coerceIn(0, quadro.width - 1)
        val y0 = (ly - raio).coerceIn(0, quadro.height - 1)
        val x1 = (lx + raio).coerceIn((x0 + 1).coerceAtMost(quadro.width), quadro.width)
        val y1 = (ly + raio).coerceIn((y0 + 1).coerceAtMost(quadro.height), quadro.height)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return false
        val px = IntArray(rw * rh)
        quadro.getPixels(px, 0, rw, x0, y0, rw, rh)
        var naoFundo = 0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val ehFundoEscuro = r <= 42 && g <= 46 && b <= 56
            if (!ehFundoEscuro && (r + g + b) > 165) {
                naoFundo++
            }
        }
        return naoFundo >= 10
    }

    private fun capturarQuadroFresco(fallbackTela: Bitmap): Bitmap {
        val captor = capturadorQuadroLoot
        if (captor != null) {
            val fresco = try {
                captor.invoke()
            } catch (_: Exception) {
                null
            }
            if (fresco != null && !fresco.isRecycled) {
                return fresco
            }
        }
        return fallbackTela.copy(fallbackTela.config ?: Bitmap.Config.ARGB_8888, false)
    }

    private fun temMonstroQuadroFresco(fallbackTela: Bitmap, context: Context): Boolean {
        val q = capturarQuadroFresco(fallbackTela)
        return try {
            BatalhaEngine.atualizar(
                q, SystemClock.uptimeMillis(), context,
                if (alertaHabilitado) limiteAlarmeMs else null
            ).lutaConfirmada
        } finally {
            if (q !== fallbackTela && !q.isRecycled) q.recycle()
        }
    }

    private fun obterCruzMinimapaSegura(tela: Bitmap): String {
        val x0 = (tela.width * Cal.MINIMAPA_X0).toInt().coerceIn(0, tela.width - 1)
        val y0 = (tela.height * Cal.MINIMAPA_Y0).toInt().coerceIn(0, tela.height - 1)
        val x1 = (tela.width * Cal.MINIMAPA_X1).toInt().coerceIn(x0 + 1, tela.width)
        val y1 = (tela.height * Cal.MINIMAPA_Y1).toInt().coerceIn(y0 + 1, tela.height)
        val c = acharCruz(tela, x0, y0, x1, y1)
        return if (c != null) "${c.first},${c.second}" else "N/A"
    }

    private fun executarAutoloot(larguraTela: Int, alturaTela: Int, context: Context, tela: Bitmap) {
        val inicioMs = SystemClock.uptimeMillis()
        var cliquesCount = 0
        LogRota.registrarPasso(context, "AUTOLOOT inicio tamCasa=${tamanhoCasaPx.toInt()}px", tela)

        if (!esperarFatiado(1000L, inicioMs, context, tela, cliquesCount)) return

        val centro = obterCentroPersonagemJogo(larguraTela, alturaTela)
        val ladoCasa = tamanhoCasaPx.toInt().coerceIn(16, 260)
        val deltas = arrayOf(
            Pair(-1, -1), Pair(0, -1), Pair(1, -1),
            Pair(-1, 0),              Pair(1, 0),
            Pair(-1, 1),  Pair(0, 1),  Pair(1, 1)
        )

        data class CandidataCasa(
            val indiceCasa: Int,
            val px: Float,
            val py: Float,
            val distCentro: Double,
            val diff: Double,
            val piso: Double
        )

        val candidatas = ArrayList<CandidataCasa>()
        val detalhesAvaliacao = StringBuilder()

        for (i in 0 until 8) {
            val d = deltas[i]
            val cxCasa = centro.first + d.first * ladoCasa
            val cyCasa = centro.second + d.second * ladoCasa
            val corteAntes = recortesCasasLuta[i]
            val corteDepois = extrairRecorteCasa(tela, centro.first, centro.second, d.first, d.second, ladoCasa)
            val diff = if (corteAntes != null) calcularDiferencaMediaRecorte(corteAntes, corteDepois) else 0.0
            val piso = variacaoMaxCasasLuta[i].coerceIn(1.5, Cal.PISO_ANIMACAO_MAX_PX)
            if (i > 0) detalhesAvaliacao.append(";")
            detalhesAvaliacao.append("c${i + 1}(diff=${"%.2f".format(diff)}/piso=${"%.2f".format(piso)})")
            if (corteAntes != null && diff > piso) {
                val dist = hypot((cxCasa - centro.first).toDouble(), (cyCasa - centro.second).toDouble())
                candidatas.add(CandidataCasa(i, cxCasa, cyCasa, dist, diff, piso))
            }
        }

        LogRota.registrarPasso(
            context,
            "AUTOLOOT casas candidatas=${candidatas.size} [$detalhesAvaliacao]",
            tela
        )

        if (candidatas.isEmpty()) {
            LogRota.registrarPasso(context, "AUTOLOOT nenhuma casa mudou acima do piso; nenhuma coleta confirmada", tela)
            val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
            LogRota.registrarPasso(
                context,
                "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=nao",
                tela
            )
            return
        }

        val ordenadas = candidatas.sortedBy { it.distCentro }
        var totalItensPegos = 0
        val refX = (refBarraTituloX * larguraTela).toInt()
        val refY = (refBarraTituloY * alturaTela).toInt()

        for ((idxCorpo, cand) in ordenadas.withIndex()) {
            if (cancelarAutoloot) {
                val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                    tela
                )
                return
            }
            if (SystemClock.uptimeMillis() - inicioMs >= maxAutolootMs) {
                LogRota.registrarPasso(context, "AUTOLOOT limite de tempo", tela)
                val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=nao",
                    tela
                )
                return
            }

            if (temMonstroQuadroFresco(tela, context)) {
                LogRota.registrarPasso(context, "AUTOLOOT interrompido: monstro apareceu", tela)
                return
            }

            val inicioCorpoMs = SystemClock.uptimeMillis()
            val cruzAntes = obterCruzMinimapaSegura(tela)

            val gestoCorpo = ToqueService.clicarEAguardar(cand.px, cand.py, 30L)
if (!gestoCorpo.first) {
    LogRota.registrarPasso(context, "AUTOLOOT toque corpo falhou: " + gestoCorpo.second, tela)
    continue
}
            cliquesCount++
            if (!esperarFatiado(60L, inicioMs, context, tela, cliquesCount)) return

            if (cancelarAutoloot) {
                val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                    tela
                )
                return
            }
            val gestoAbertura = ToqueService.clicarEAguardar(cand.px, cand.py, 30L)
            if (!gestoAbertura.first) {
                LogRota.registrarPasso(context, "AUTOLOOT segundo toque no corpo falhou: " + gestoAbertura.second, tela)
                continue
            }
            cliquesCount++
            LogRota.registrarPasso(
                context,
                "AUTOLOOT corpo ${idxCorpo + 1}/${ordenadas.size} casa=${cand.indiceCasa + 1} diff=${"%.2f".format(cand.diff)} ${cand.px.toInt()},${cand.py.toInt()}",
                tela
            )

            var janelaAberta = false
            val esperaJanelaFim = SystemClock.uptimeMillis() + Cal.JANELA_ABERTURA_LOOT_MS
            while (SystemClock.uptimeMillis() < esperaJanelaFim) {
                if (cancelarAutoloot) {
                    val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                    LogRota.registrarPasso(
                        context,
                        "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                        tela
                    )
                    return
                }
                if (temMonstroQuadroFresco(tela, context)) {
                    LogRota.registrarPasso(context, "AUTOLOOT interrompido: monstro apareceu", tela)
                    return
                }
                val q = capturarQuadroFresco(tela)
                val aberta = try {
                    janelaLootAbertaPorEscuro(q)
                } finally {
                    if (q !== tela && !q.isRecycled) q.recycle()
                }
                if (aberta) {
                    janelaAberta = true
                    break
                }
                if (!esperarFatiado(80L, inicioMs, context, tela, cliquesCount)) return
            }

            if (!janelaAberta) {
                val cruzDepois = obterCruzMinimapaSegura(tela)
                LogRota.registrarPasso(
                    context,
                    "corpo ${idxCorpo + 1}: itens=0 pegos=0 janela=fechada cruzAntes=$cruzAntes cruzDepois=$cruzDepois",
                    tela
                )
                continue
            }

            val quadroBarra = capturarQuadroFresco(tela)
            val barraAchada = try {
                acharBarraTituloLoot(quadroBarra)
            } finally {
                if (quadroBarra !== tela && !quadroBarra.isRecycled) quadroBarra.recycle()
            }

            val usarOffsetBarra = barraAchada != null
            val dxBarra = if (barraAchada != null) (barraAchada.first - refX).toFloat() else 0f
            val dyBarra = if (barraAchada != null) (barraAchada.second - refY).toFloat() else 0f
            if (!usarOffsetBarra) {
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT barra titulo nao achada - usando PONTOS_LOOT fixos sem gravar cache",
                    tela
                )
            }

            var itensDetectados = 0
            var itensPegos = 0

            for (ponto in Cal.PONTOS_LOOT) {
                if (cancelarAutoloot) {
                    val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                    LogRota.registrarPasso(
                        context,
                        "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                        tela
                    )
                    return
                }
                if (SystemClock.uptimeMillis() - inicioMs >= maxAutolootMs) {
                    LogRota.registrarPasso(context, "AUTOLOOT limite de tempo", tela)
                    return
                }
                if (SystemClock.uptimeMillis() - inicioCorpoMs >= Cal.TEMPO_MAX_POR_CORPO_MS) {
                    break
                }

                if (temMonstroQuadroFresco(tela, context)) {
                    LogRota.registrarPasso(context, "AUTOLOOT interrompido: monstro apareceu", tela)
                    return
                }

                val lx = (ponto.first * larguraTela + dxBarra).coerceIn(0f, (larguraTela - 1).toFloat())
                val ly = (ponto.second * alturaTela + dyBarra).coerceIn(0f, (alturaTela - 1).toFloat())

                val qCheckItem = capturarQuadroFresco(tela)
                val temItemInicial = try {
                    pontoLootTemItem(qCheckItem, lx.toInt(), ly.toInt())
                } finally {
                    if (qCheckItem !== tela && !qCheckItem.isRecycled) qCheckItem.recycle()
                }

                if (!temItemInicial) continue
                itensDetectados++

                var pegouEste = false
                for (tentativa in 1..Cal.MAX_TENTATIVAS_ITEM_LOOT) {
                    if (SystemClock.uptimeMillis() - inicioCorpoMs >= Cal.TEMPO_MAX_POR_CORPO_MS) break
                    val gestoItem = ToqueService.clicarEAguardar(lx, ly, 30L)
if (!gestoItem.first) {
    LogRota.registrarPasso(context, "AUTOLOOT toque item falhou: " + gestoItem.second, tela)
    break
}
                    cliquesCount++
                    if (!esperarFatiado(intervaloLootMs, inicioMs, context, tela, cliquesCount)) return

                    val qPosClique = capturarQuadroFresco(tela)
                    val aindaTemItem = try {
                        pontoLootTemItem(qPosClique, lx.toInt(), ly.toInt())
                    } finally {
                        if (qPosClique !== tela && !qPosClique.isRecycled) qPosClique.recycle()
                    }
                    if (!aindaTemItem) {
                        pegouEste = true
                        itensPegos++
                        break
                    }
                }
                if (!pegouEste) {
                    // Tentou ate 2 vezes e seguiu adiante
                }
            }

            totalItensPegos += itensPegos
            val cruzDepois = obterCruzMinimapaSegura(tela)
            LogRota.registrarPasso(
                context,
                "corpo ${idxCorpo + 1}: itens=$itensDetectados pegos=$itensPegos cruzAntes=$cruzAntes cruzDepois=$cruzDepois",
                tela
            )
        }

        if (LootEngine.confirmarColeta(totalItensPegos)) autoloots++
        val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
        LogRota.registrarPasso(
            context,
            "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount itensPegos=$totalItensPegos cancelado=nao",
            tela
        )
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
        val diffStr = "%.2f".format(ultimaDiffMinimapa)
        val base = "fase=$faseNome | pino=$pinoId | acerto=$scoreStr | verde=$qtdVerde | $rgbStr | dist=$distStr | semProgresso=$progresso | diffMini=$diffStr"
        return if (extra != null && extra.isNotEmpty()) "$base | $extra" else base
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
            val linhaVersao = "VERSAO Rota ${BuildConfig.VERSION_NAME} | MARGEM_PINO=${"%.2f".format(Cal.MARGEM_PINO)} | LIMIAR_COR_ALVO=${"%.2f".format(Cal.LIMIAR_COR_ALVO)} | LIMIAR=${"%.2f".format(Cal.LIMIAR)} | LIMIAR_VERDE=${"%.2f".format(Cal.LIMIAR_VERDE)} | MARGEM_VERDE=${"%.2f".format(Cal.MARGEM_VERDE)} | INTERVALO_CLIQUE=${intervaloCliqueMs} | tela=${tela.width}x${tela.height}"
            LogRota.registrarPasso(context, linhaVersao, tela)
        }
        val texto = passoInterno(tela, context, usaLoot, usaAlerta, uriSom, volume, tocar)
        LogRota.registrarPasso(context, texto, tela)
        return texto
    }


    /** Wrapper fino: o detector e a máquina de estados vivem em BatalhaEngine. */
    private fun avaliarLutaComConfirmacao(
        tela: Bitmap,
        context: Context,
        agora: Long,
        usaAlerta: Boolean
    ): BatalhaEngine.Estado {
        val st = BatalhaEngine.atualizar(
            tela, agora, context,
            if (usaAlerta) limiteAlarmeMs else null
        )
        if (st.iniciouAgora) {
            prepararCapturaCasasAntesLuta(tela)
            if (!salvouQuadroInicioLuta) {
                salvouQuadroInicioLuta = true
                LogRota.salvarQuadroDiagnostico(context, "batalha_inicio", tela)
            }
        }
        if (st.terminouAgora) {
            salvouQuadroInicioLuta = false
            salvouQuadroInicioAlarmeLuta = false
        }
        if (st.lutaConfirmada) {
            atualizarMonitoramentoCasasDuranteLuta(tela)
        }
        return st
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

    /**
     * Um degrau da escada por chamada. Devolve o log do degrau executado (clique/resync) ou null quando a escada
     * está esgotada; nesse caso o chamador DEVE chamar registrarFalhaPino().
     */
    private fun executarEscadaRecuperacaoPino(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        cx: Float,
        cy: Float,
        toleranciaChegada: Float,
        agora: Long,
        context: Context,
        tocar: (Float, Float) -> Unit,
        modelo: Modelo
    ): String? {
        val minToqueX = 28f.coerceAtMost((tela.width - 1).toFloat())
        val maxToqueX = (tela.width * 0.985f - 20f).coerceAtLeast(minToqueX)
        val minToqueY = 28f.coerceAtMost((tela.height - 1).toFloat())
        val maxToqueY = (tela.height - 28f).coerceAtLeast(minToqueY)
        val pinoId = modelo.indice

        if (nivelRecuperacaoPino == 0) {
            val posReal = ultimoPosPinoConhecidaRel ?: acharPorTemplate(tela, x0, y0, x1, y1, modelo, cx, cy)?.let {
                Pair((it.first - x0).coerceIn(0, rwSafe(x0, x1) - 1), (it.second - y0).coerceIn(0, rhSafe(y0, y1) - 1))
            }
            if (posReal != null) {
                val px = (x0 + posReal.first).toFloat().coerceIn(minToqueX, maxToqueX)
                val py = (y0 + posReal.second).toFloat().coerceIn(minToqueY, maxToqueY)
                val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                ultimoClique = agora
                nivelRecuperacaoPino = 1
                val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                return formatarLog(
                    fase.name, pinoId, melhorScore, ultimoQtdVerde, ultimoRgbVerde,
                    ultimaDistVerde, semProgresso, "RECUPERACAO_1_RECLIQUE_REAL | $logToque"
                )
            }
            nivelRecuperacaoPino = 1
        }

        if (nivelRecuperacaoPino == 1) {
            val posBase = ultimoPosPinoConhecidaRel
            if (posBase != null) {
                val offsets = arrayOf(Pair(8f, 0f), Pair(-8f, 0f), Pair(0f, 8f), Pair(0f, -8f), Pair(10f, 6f), Pair(-10f, -6f), Pair(6f, -10f), Pair(-6f, 10f))
                val off = offsets[indiceCliqueVariavel++ % offsets.size]
                val px = (x0 + posBase.first + off.first).coerceIn((x0 + 15f).coerceAtMost(maxToqueX), (x1 - 15f).coerceAtMost(maxToqueX)).coerceIn(minToqueX, maxToqueX)
                val py = (y0 + posBase.second + off.second).coerceIn(y0 + 15f, y1 - 15f).coerceIn(minToqueY, maxToqueY)
                val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                ultimoClique = agora
                nivelRecuperacaoPino = 2
                val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                return formatarLog(
                    fase.name, pinoId, melhorScore, ultimoQtdVerde, ultimoRgbVerde,
                    ultimaDistVerde, semProgresso, "RECUPERACAO_2_DESLOCAMENTO_LATERAL | $logToque"
                )
            }
            nivelRecuperacaoPino = 2
        }

        if (nivelRecuperacaoPino == 2) {
            LogRota.registrarPasso(
                context,
                "AVISO_ZOOM_CURTO: app nao possui gesto de zoom curto implementado; avancando para resync/alarme",
                tela
            )
            nivelRecuperacaoPino = 3
        }

        if (!resyncUsadoNestePino) {
            resyncUsadoNestePino = true
            val resyncLog = tentarResyncPino(tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context)
            if (resyncLog != null) {
                return formatarLog(
                    fase.name,
                    if (alvo in modelos.indices) modelos[alvo].indice else pinoId,
                    melhorScore,
                    0,
                    ultimoRgbVerde,
                    -1f,
                    semProgresso,
                    "RECUPERACAO_RESYNC | $resyncLog"
                )
            }
        }

        // Escada esgotada (reclique, deslocamento lateral e resync já tentados): devolve null para o chamador
        // registrar a falha em registrarFalhaPino(). É ela que conta falhasPino, reinicia a escada (nivelRecuperacaoPino
        // volta a 0) e, ao atingir Cal.MAX_FALHAS_PINO, chama tratarEsgotamentoPinoSemPulo (voltar ao pino anterior
        // ou pausar por pino perdido).
        return null
    }

    private fun rwSafe(x0: Int, x1: Int): Int = (x1 - x0).coerceAtLeast(1)
    private fun rhSafe(y0: Int, y1: Int): Int = (y1 - y0).coerceAtLeast(1)

    private fun passoInterno(
        tela: Bitmap,
        context: Context,
        usaLoot: Boolean,
        usaAlerta: Boolean,
        uriSom: String,
        volume: Int,
        tocar: (Float, Float) -> Unit
    ): String {
        alertaHabilitado = usaAlerta
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

        val cx = cruz.first.toFloat()
        val cy = cruz.second.toFloat()
        val agora = SystemClock.uptimeMillis()

        val estadoMini = compararMinimapaComAnterior(tela, x0, y0, x1, y1, cx, cy)
        ultimaDiffMinimapa = estadoMini.diffMedia
        if (estadoMini.emMovimento) {
            minimapaParadoDesde = 0L
        } else if (minimapaParadoDesde == 0L) {
            minimapaParadoDesde = agora
        }
        val tempoParadoMs = if (minimapaParadoDesde > 0L) (agora - minimapaParadoDesde) else 0L

        val agoraLuta = agora
        // ignorarLutaAte nunca saía de 0, então a condição "agoraLuta >= ignorarLutaAte" era sempre verdadeira.
        val estadoBatalha: BatalhaEngine.Estado? = avaliarLutaComConfirmacao(tela, context, agoraLuta, usaAlerta)
        val monstroDetectado = estadoBatalha?.lutaConfirmada ?: false

        if (monstroDetectado) {
            if (!estavaEmLuta) {
                estavaEmLuta = true
                tempoInicioLuta = agora
                estavaEmLutaCaminhando = (fase == FaseNavegacao.CAMINHANDO)
                verdeRelAntesDaLuta = ultimoVerdeRel
            }

            val inicioRef = if (estadoBatalha != null && estadoBatalha.inicioLutaMs > 0L) estadoBatalha.inicioLutaMs else tempoInicioLuta
            val segsLuta = (agora - inicioRef).coerceAtLeast(0L) / 1000L
            if (usaAlerta && estadoBatalha != null && estadoBatalha.alarmeDevido) {
                if (!salvouQuadroInicioAlarmeLuta) {
                    salvouQuadroInicioAlarmeLuta = true
                    LogRota.salvarQuadroDiagnostico(context, "alarme_luta", tela)
                }
                BotService.tocarAlarme(context, uriSom, volume)
                return "ALARME: batalha há ${segsLuta}s"
            }

            return "LUTA ${segsLuta}s (pausado)"
        }

        if (!pausadoPorPinoPerdido) {
            BotService.pararAlarme()
        }

        if (estavaEmLuta) {
            val duracaoTotalLutaMs = if (tempoInicioLuta > 0L) (agora - tempoInicioLuta).coerceAtLeast(0L) else 0L
            val duracaoTotalSeg = duracaoTotalLutaMs / 1000L
            val veioDeCaminhando = estavaEmLutaCaminhando
            val refVerde = verdeRelAntesDaLuta

            estavaEmLuta = false
            tempoInicioLuta = 0L
            estavaEmLutaCaminhando = false
            verdeRelAntesDaLuta = null
            salvouQuadroInicioAlarmeLuta = false

            if (usaLoot && LootEngine.ignorarLutaCurta(duracaoTotalLutaMs)) {
                LogRota.registrarPasso(context, "AUTOLOOT ignorado: luta curta", tela)
                ultimoClique = 0L
                pausadoAte = agora + 200L
                quadrosChegada = 0
                quadrosSumidoVerde = 0
                ultimaDist = -1f
                ultimaDistVerde = -1f
                menorDistVerde = -1f
                semProgresso = 0
                cliquesVerde = 0
                naoVisto = 0
                fase = FaseNavegacao.ESCOLHER
                retomarVerdeAte = 0L
                verdeVeioDeTemplate = false
                ultimoVerdeRel = null
                ultimoQtdVerde = 0
                ultimoRgbVerde = null
                vistoVerde = false
                return "AUTOLOOT ignorado: luta curta"
            }

            lutas++

            val antesLootMs = SystemClock.uptimeMillis()
            if (usaLoot) {
                executarAutoloot(tela.width, tela.height, context, tela)
            }
            val fimProcessamentoLutaLootMs = SystemClock.uptimeMillis()
            fimLutaEm = fimProcessamentoLutaLootMs
            val duracaoPausaTotalMs = duracaoTotalLutaMs + (fimProcessamentoLutaLootMs - antesLootMs).coerceAtLeast(0L)
            if (iniciouCaminhadaEm > 0L && duracaoPausaTotalMs > 0L) {
                iniciouCaminhadaEm += duracaoPausaTotalMs
            }

            ultimoClique = 0L
            pausadoAte = agora + 200L
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
                tempoMaxPinoMs = calcularTempoMaxPinoMs(if (ultimaDistVerde > 0f) ultimaDistVerde else pixelsPorCasa * 4f)
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
            resetarInterno()
        }

        val modelo = modelos[alvo]
        val indice = modelo.indice
        val toleranciaChegada = Cal.TOLERANCIA_CHEGADA_CASAS * pixelsPorCasa

        if (agora - ultimoLogResumoEm >= 15000L) {
            ultimoLogResumoEm = agora
            registrarResumoContadores(context, tela)
        }

        if (pausadoPorPinoPerdido) {
            return formatarLog(
                fase.name, indice, melhorScore, ultimoQtdVerde, ultimoRgbVerde, ultimaDistVerde, semProgresso, "PAUSADO_POR_PINO_PERDIDO"
            )
        }

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
                quadrosSemRotaSeguidos++
                if (quadrosSemRotaSeguidos >= 2) {
                    quadrosSemRota = 0
                    quadrosSemRotaSeguidos = 0
                    ignorarSemRotaAte = agora + Cal.IGNORAR_SEM_ROTA_MS
                    // registrarFalhaPino() já incrementa falhasPino.
                    return registrarFalhaPino(
                        agora, fase.name, indice, melhorScore, ultimoQtdVerde, ultimoRgbVerde,
                        ultimaDistVerde, semProgresso, "nao ha rota", context, tela
                    )
                }
            } else {
                quadrosSemRotaSeguidos = 0
            }

            if (iniciouCaminhadaEm > 0L && (agora - iniciouCaminhadaEm) >= tempoMaxPinoMs) {
                val recMsg = executarEscadaRecuperacaoPino(
                    tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
                )
                if (recMsg != null) {
                    iniciouCaminhadaEm = agora
                    return recMsg
                }
                // Escada esgotada sem chegar: conta a falha (antes caía adiante sem contar, pois a escada nunca devolvia null)
                return registrarFalhaPino(
                    agora, fase.name, indice, melhorScore, ultimoQtdVerde, ultimoRgbVerde,
                    ultimaDistVerde, semProgresso, "tempo maximo no pino excedido", context, tela
                )
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
                ultimoPosPinoConhecidaRel = verdeRel
                ultimoPosPinoEraVerde = true

                val absX = (x0 + verdeRel.first).toFloat()
                val absY = (y0 + verdeRel.second).toFloat()
                val dist = hypot((absX - cx).toDouble(), (absY - cy).toDouble()).toFloat()
                ultimaDistVerde = dist
                if (menorDistVerde < 0f || dist < menorDistVerde) {
                    menorDistVerde = dist
                }

                val minimapaParadoSuficiente = !estadoMini.emMovimento && tempoParadoMs >= Cal.TEMPO_MINIMO_PARADO_CHEGADA_MS
                if (chegouNoPino(dist, estadoMini, tempoParadoMs, vistoVerde, verdeVeioDeTemplate)) {
                    ultimoConfirmado = alvo
                    proximoPino(agora, "CHEGADA_CONFIRMADA", context, tela)
                    return formatarLog(
                        "CHEGOU", indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso,
                        "CHEGOU motivo=confirmada"
                    )
                }
                if (dist <= toleranciaChegada && minimapaParadoSuficiente && (vistoVerde || verdeVeioDeTemplate)) {
                    return formatarLog(
                        fase.name, indice, melhorScore, qtdVerde, rgbVerde, dist, semProgresso, "chegando"
                    )
                }

                var logClique: String? = null

                if (!estadoMini.emMovimento && tempoParadoMs >= Cal.TEMPO_PARADO_RECLIQUE_MS && (agora - ultimoClique >= intervaloCliqueMs)) {
                    semProgresso++
                    val px = absX.coerceIn((x0 + 15f).coerceAtMost(maxToqueX(tela)), (x1 - 15f).coerceAtMost(maxToqueX(tela))).coerceIn(minToqueX(tela), maxToqueX(tela))
                    val py = absY.coerceIn(y0 + 15f, y1 - 15f).coerceIn(minToqueY(tela), maxToqueY(tela))
                    val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                    ultimoClique = agora
                    cliquesVerde++
                    logClique = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                } else if (estadoMini.emMovimento) {
                    semProgresso = 0
                    indiceCliqueVariavel = 0
                }
                ultimaDist = dist

                if (!estadoMini.emMovimento && (semProgresso >= 6 || cliquesVerde >= 12)) {
                    val recMsg = executarEscadaRecuperacaoPino(
                        tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
                    )
                    if (recMsg != null) {
                        semProgresso = 0
                        cliquesVerde = 0
                        return recMsg
                    }
                    val progressoAntesReset = semProgresso
                    return registrarFalhaPino(
                        agora, fase.name, indice, melhorScore, qtdVerde, rgbVerde,
                        dist, progressoAntesReset, "sem progresso - reescolhendo", context, tela
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

                val confirmadoSumido = if (vistoVerde && verdeVeioDeTemplate && pertoDaCruz && quadrosSumidoVerde < 6) {
                    chegouNoPino(distRefAtual, estadoMini, tempoParadoMs, vistoVerde, verdeVeioDeTemplate)
                } else {
                    quadrosChegada = 0
                    false
                }
                if (confirmadoSumido) {
                    val rgbRef = ultimoRgbVerde
                    ultimoConfirmado = alvo
                    proximoPino(agora, "CHEGADA_CONFIRMADA", context, tela)
                    return formatarLog(
                        "CHEGOU", indice, melhorScore, 0, rgbRef, distRefAtual, semProgresso,
                        "CHEGOU motivo=confirmada sob a cruz"
                    )
                }

                if (!estadoMini.emMovimento && tempoParadoMs >= Cal.TEMPO_PARADO_RECLIQUE_MS && (agora - ultimoClique >= intervaloCliqueMs)) {
                    val posReclique = ultimoPosPinoConhecidaRel
                    if (posReclique != null) {
                        val px = (x0 + posReclique.first).toFloat().coerceIn(minToqueX(tela), maxToqueX(tela))
                        val py = (y0 + posReclique.second).toFloat().coerceIn(minToqueY(tela), maxToqueY(tela))
                        val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                        ultimoClique = agora
                        val logReclique = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                        return formatarLog(
                            fase.name, indice, melhorScore, 0, ultimoRgbVerde, distRefAtual, semProgresso,
                            "reclique sem avancar alvo | $logReclique"
                        )
                    }
                }

                if (!estadoMini.emMovimento && quadrosSumidoVerde >= 6) {
                    val recMsg = executarEscadaRecuperacaoPino(
                        tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
                    )
                    if (recMsg != null) {
                        quadrosSumidoVerde = 0
                        return recMsg
                    }
                    return registrarFalhaPino(
                        agora, fase.name, indice, melhorScore, 0, ultimoRgbVerde,
                        ultimaDistVerde, semProgresso, "verde sumiu - reescolhendo", context, tela
                    )
                }

                return formatarLog(
                    fase.name, indice, melhorScore, 0, ultimoRgbVerde, ultimaDistVerde, semProgresso, "aguardando verde"
                )
            }
        }

        val minToqueXVal = minToqueX(tela)
        val maxToqueXVal = maxToqueX(tela)
        val minToqueYVal = minToqueY(tela)
        val maxToqueYVal = maxToqueY(tela)

        val verdes = buscarComponentesVerdes(tela, x0, y0, x1, y1)
        val avaliadosVerdes = verdes.map { Pair(it, avaliarPinoVerdeComModelos(tela, x0, y0, x1, y1, it.posicao, modelo)) }
        val validosVerdes = avaliadosVerdes.filter { it.second.valido }
        var recusaPino: String? = null
        if (verdes.isNotEmpty() && validosVerdes.size > 1) {
            recusaPino = "AMBIGUO_VERDE nao clicou: ${validosVerdes.size} pinos verdes validos para alvo=#$indice"
        } else if (verdes.isNotEmpty() && validosVerdes.isEmpty()) {
            val a0 = avaliadosVerdes.first().second
            recusaPino = "VERDE_RECUSADO nao clicou: alvo=#$indice score=${"%.2f".format(a0.scoreAlvo)}(min ${"%.2f".format(Cal.LIMIAR_VERDE)}) " +
                "rival=" + (if (a0.rivalIndice != null) "#${a0.rivalIndice}(${"%.2f".format(a0.rivalScore)})" else "nenhum") +
                " margem=${"%+.2f".format(a0.margem)}(min ${"%.2f".format(Cal.MARGEM_VERDE)})"
        }
        val escolhidoVerde = if (validosVerdes.size == 1) validosVerdes[0] else null
        if (escolhidoVerde != null) {
            val verde = escolhidoVerde.first
            val avaliacao = escolhidoVerde.second
            if (avaliacao.valido) {
                val rivalInfo = if (avaliacao.rivalIndice != null) {
                    "rival=#${avaliacao.rivalIndice}(${"%.2f".format(avaliacao.rivalScore)}) margem=${"%+.2f".format(avaliacao.margem)}"
                } else {
                    "rival=nenhum"
                }
                val distVerde = hypot(((x0 + verde.posicao.first) - cx).toDouble(), ((y0 + verde.posicao.second) - cy).toDouble()).toFloat()
                if (distVerde > toleranciaChegada && agora - ultimoClique >= intervaloCliqueMs) {
                    val px = (x0 + verde.posicao.first).toFloat().coerceIn(minToqueXVal, maxToqueXVal)
                    val py = (y0 + verde.posicao.second).toFloat().coerceIn(minToqueYVal, maxToqueYVal)
                    val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                    ultimoClique = agora
                    iniciarCaminhada(verde.posicao, distVerde, agora, veioDeTemplate = true)
                    vistoVerde = true
                    ultimoQtdVerde = verde.quantidade
                    ultimoRgbVerde = verde.rgbMedio
                    val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                    return formatarLog(
                        fase.name, indice, avaliacao.scoreAlvo, verde.quantidade, verde.rgbMedio,
                        distVerde, semProgresso, "pino verde template | $rivalInfo | $logToque"
                    )
                }
            }
        }

        val achado = acharPorTemplate(tela, x0, y0, x1, y1, modelo, cx, cy)
        val scoreAtual = melhorScore
        var rivalLog = if (achado != null) {
            val refCentroRel = Pair(
                (achado.first - x0).coerceIn(0, (x1 - x0 - 1).coerceAtLeast(0)),
                (achado.second - y0).coerceIn(0, (y1 - y0 - 1).coerceAtLeast(0))
            )
            val avaliacaoCandidato = avaliarPinoVerdeComModelos(tela, x0, y0, x1, y1, refCentroRel, modelo)
            if (avaliacaoCandidato.rivalIndice != null) {
                "rival=#${avaliacaoCandidato.rivalIndice}(${"%.2f".format(avaliacaoCandidato.rivalScore)}) margem=${"%+.2f".format(avaliacaoCandidato.margem)}"
            } else {
                "rival=nenhum"
            }
        } else {
            "rival=nenhum"
        }
        if (achado == null) {
            if (recusaPino != null) rivalLog += " | $recusaPino"
            val recusaTpl = ultimaRecusaTemplate
            if (recusaTpl != null) rivalLog += " | $recusaTpl"
        }

        if (achado == null) {
            quadrosPertoDaCruz = 0
            val candidatoPos = candidatoConsistente
            if (scoreAtual >= Cal.LIMIAR_FALLBACK_CONSISTENTE && candidatoPos != null) {
                quadrosQuaseLimiar++
            } else {
                quadrosQuaseLimiar = 0
                candidatoConsistente = null
            }

            val ambFb = if (quadrosQuaseLimiar >= Cal.QUADROS_FALLBACK_CONSISTENTE && candidatoPos != null) {
                avaliarAmbiguidadeEm(tela, x0, y0, x1, y1, candidatoPos, modelo)
            } else null
            if (ambFb != null && ambFb.recusado) {
                rivalLog += " | ${ambFb.motivo()}_FALLBACK nao clicou: " + ambFb.texto(indice)
                quadrosQuaseLimiar = 0
                ultimoCandidatoQuaseLimiar = null
                candidatoConsistente = null
            }
            if (quadrosQuaseLimiar >= Cal.QUADROS_FALLBACK_CONSISTENTE && candidatoPos != null) {
                quadrosQuaseLimiar = 0
                ultimoCandidatoQuaseLimiar = null
                candidatoConsistente = null
                val distFb = hypot((candidatoPos.first - cx).toDouble(), (candidatoPos.second - cy).toDouble()).toFloat()
                if (distFb > toleranciaChegada && agora - ultimoClique >= intervaloCliqueMs) {
                    val px = candidatoPos.first.toFloat().coerceIn(minToqueXVal, maxToqueXVal)
                    val py = candidatoPos.second.toFloat().coerceIn(minToqueYVal, maxToqueYVal)
                    val toque = dispararToqueSeguro(
                        tela.width, tela.height, px, py, tocar
                    )
                    ultimoClique = agora
                    iniciarCaminhada(Pair(candidatoPos.first - x0, candidatoPos.second - y0), distFb, agora, veioDeTemplate = true)
                    val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
                    val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                    return formatarLog(
                        fase.name, indice, scoreAtual, 0, ultimoRgbVerde, distFb, semProgresso,
                        "$sentidoStr | $rivalLog | $logToque (fallback score - sem avancar alvo)"
                    )
                }
            }

            val refCandidato = ultimoCandidatoQuaseLimiar
            if (refCandidato != null && scoreAtual >= Cal.LIMIAR_CANDIDATO_VERDE) {
                val verdeNoMapa = acharPinoVerdeProximo(tela, x0, y0, x1, y1, refCandidato, cx, cy, toleranciaChegada)
                val avVerdeCand = if (verdeNoMapa != null) {
                    avaliarPinoVerdeComModelos(tela, x0, y0, x1, y1, verdeNoMapa.posicao, modelo)
                } else null
                if (avVerdeCand != null && !avVerdeCand.valido) {
                    rivalLog += " | VERDE_CANDIDATO_RECUSADO nao clicou: alvo=#$indice score=${"%.2f".format(avVerdeCand.scoreAlvo)}(min ${"%.2f".format(Cal.LIMIAR_VERDE)}) " +
                        "margem=${"%+.2f".format(avVerdeCand.margem)}(min ${"%.2f".format(Cal.MARGEM_VERDE)})"
                }
                if (verdeNoMapa != null && avVerdeCand != null && avVerdeCand.valido) {
                    val verdeRel = verdeNoMapa.posicao
                    val distVerde = hypot(
                        ((x0 + verdeRel.first) - cx).toDouble(),
                        ((y0 + verdeRel.second) - cy).toDouble()
                    ).toFloat()
                    if (distVerde > toleranciaChegada && agora - ultimoClique >= intervaloCliqueMs) {
                        val px = (x0 + verdeRel.first).toFloat().coerceIn(minToqueXVal, maxToqueXVal)
                        val py = (y0 + verdeRel.second).toFloat().coerceIn(minToqueYVal, maxToqueYVal)
                        val toque = dispararToqueSeguro(
                            tela.width, tela.height, px, py, tocar
                        )
                        ultimoClique = agora
                        iniciarCaminhada(verdeRel, distVerde, agora, veioDeTemplate = true)
                        vistoVerde = true
                        ultimoQtdVerde = verdeNoMapa.quantidade
                        ultimoRgbVerde = verdeNoMapa.rgbMedio
                        val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
                        val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                        return formatarLog(
                            fase.name, indice, scoreAtual, verdeNoMapa.quantidade, verdeNoMapa.rgbMedio,
                            distVerde, semProgresso, "$sentidoStr | $rivalLog | $logToque (verde validado candidato)"
                        )
                    }
                }
            }

            naoVisto++
            if (!estadoMini.emMovimento && naoVisto >= Cal.NAO_VISTO_LIMITE) {
                val recMsg = executarEscadaRecuperacaoPino(
                    tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
                )
                naoVisto = 0
                if (recMsg != null) {
                    return recMsg
                }
                return registrarFalhaPino(
                    agora, fase.name, indice, scoreAtual, 0, ultimoRgbVerde,
                    -1f, semProgresso, "nao localizado | $rivalLog", context, tela
                )
            }
            return formatarLog(
                fase.name, indice, scoreAtual, 0, ultimoRgbVerde, -1f, semProgresso, "procurando | $rivalLog"
            )
        }

        naoVisto = 0
        quadrosQuaseLimiar = 0
        ultimoCandidatoQuaseLimiar = null
        candidatoConsistente = null
        ultimoPosPinoConhecidaRel = Pair(achado.first - x0, achado.second - y0)
        ultimoPosPinoEraVerde = false
        val dist = hypot((achado.first - cx).toDouble(), (achado.second - cy).toDouble()).toFloat()

        if (dist <= toleranciaChegada) {
            quadrosPertoDaCruz++
            val chegouPertoCruz = chegouNoPino(dist, estadoMini, tempoParadoMs, true, true)
            if (quadrosPertoDaCruz >= 3 && chegouPertoCruz) {
                quadrosPertoDaCruz = 0
                ultimoConfirmado = alvo
                proximoPino(agora, "CHEGADA_CONFIRMADA", context, tela)
                return formatarLog(
                    "CHEGOU", indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso,
                    "CHEGOU motivo=confirmada perto da cruz | $rivalLog"
                )
            }
            if (agora - ultimoClique >= intervaloCliqueMs) {
                val px = achado.first.toFloat().coerceIn(minToqueXVal, maxToqueXVal)
                val py = achado.second.toFloat().coerceIn(minToqueYVal, maxToqueYVal)
                val toque = dispararToqueSeguro(tela.width, tela.height, px, py, tocar)
                ultimoClique = agora
                val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                return formatarLog(
                    fase.name, indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso,
                    "perto da cruz reclique sem avancar | $rivalLog | $logToque"
                )
            }
            return formatarLog(
                fase.name, indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso, "ignorado perto da cruz | $rivalLog"
            )
        }

        quadrosPertoDaCruz = 0

        var logClique: String? = null
        if (agora - ultimoClique >= intervaloCliqueMs) {
            val px = achado.first.toFloat().coerceIn(minToqueXVal, maxToqueXVal)
            val py = achado.second.toFloat().coerceIn(minToqueYVal, maxToqueYVal)
            val toque = dispararToqueSeguro(
                tela.width, tela.height, px, py, tocar
            )
            ultimoClique = agora
            val relPos = Pair(achado.first - x0, achado.second - y0)
            iniciarCaminhada(relPos, dist, agora, veioDeTemplate = true)
            logClique = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
        }

        val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
        val extra = if (logClique != null) "$sentidoStr | $rivalLog | $logClique" else "$sentidoStr | $rivalLog"
        return formatarLog(
            fase.name, indice, scoreAtual, 0, ultimoRgbVerde, dist, semProgresso, extra
        )
    }

    private fun minToqueX(tela: Bitmap): Float = 28f.coerceAtMost((tela.width - 1).toFloat())
    private fun maxToqueX(tela: Bitmap): Float = (tela.width * 0.985f - 20f).coerceAtLeast(minToqueX(tela))
    private fun minToqueY(tela: Bitmap): Float = 28f.coerceAtMost((tela.height - 1).toFloat())
    private fun maxToqueY(tela: Bitmap): Float = (tela.height - 28f).coerceAtLeast(minToqueY(tela))

    private fun tentarResyncPino(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        cx: Float,
        cy: Float,
        toleranciaChegada: Float,
        agora: Long,
        context: Context
    ): String? {
        val refIdx = ultimoConfirmado ?: return null
        if (modelos.isEmpty() || refIdx !in modelos.indices) return null

        while (timestampsResync.isNotEmpty() && agora - timestampsResync.first() > 60000L) {
            timestampsResync.removeFirst()
        }
        if (timestampsResync.size >= 2) {
            return null
        }

        val tamanho = modelos.size
        val esperadoIdx: Int? = if (tamanho <= 1) {
            0
        } else if (direcaoIda) {
            if (refIdx < tamanho - 1) refIdx + 1 else (refIdx - 1).coerceAtLeast(0)
        } else {
            if (refIdx > 0) refIdx - 1 else (refIdx + 1).coerceAtMost(tamanho - 1)
        }

        fun avaliarCandidato(idxCandidato: Int?): Pair<Boolean, Float>? {
            if (idxCandidato == null || idxCandidato !in modelos.indices) return null
            val pos = acharPorTemplate(tela, x0, y0, x1, y1, modelos[idxCandidato], cx, cy) ?: return null
            if (melhorScore < Cal.LIMIAR.toDouble()) return null
            val dist = hypot((pos.first - cx).toDouble(), (pos.second - cy).toDouble()).toFloat()
            if (dist <= toleranciaChegada) return null
            return Pair(true, dist)
        }

        val resEsperado = avaliarCandidato(esperadoIdx)
        val escolhidoIdx: Int? = if (resEsperado != null) {
            esperadoIdx
        } else {
            val vizinhos = ArrayList<Int>()
            if (esperadoIdx != null && esperadoIdx != refIdx) vizinhos.add(esperadoIdx)
            val outroVizinho = if (direcaoIda) refIdx - 1 else refIdx + 1
            if (outroVizinho in modelos.indices && outroVizinho != esperadoIdx && outroVizinho != refIdx) {
                vizinhos.add(outroVizinho)
            }
            var achadoVizinho: Int? = null
            for (vIdx in vizinhos) {
                if (avaliarCandidato(vIdx) != null) {
                    achadoVizinho = vIdx
                    break
                }
            }
            if (achadoVizinho != null) {
                achadoVizinho
            } else if (avaliarCandidato(refIdx) != null) {
                refIdx
            } else {
                null
            }
        }

        if (escolhidoIdx == null || escolhidoIdx !in modelos.indices) return null
        if (abs(escolhidoIdx - refIdx) > 1) return null
        if (escolhidoIdx == alvo) return null

        val idAntigo = if (alvo in modelos.indices) modelos[alvo].indice else alvo
        val idNovo = modelos[escolhidoIdx].indice
        alvo = escolhidoIdx
        estadoPino.reiniciar(preservarFalhas = false)
        fase = FaseNavegacao.ESCOLHER
        timestampsResync.addLast(agora)

        registrarLogAlvo(context, tela)
        return "RESYNC alvo $idAntigo->$idNovo"
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

    /**
     * Pino verde ativo = componente conexo verde com área e caixa de pino.
     * Grama/áreas verdes grandes do minimapa são recusadas (área, tamanho da caixa e preenchimento).
     * Devolve todos os componentes válidos, o mais próximo da área de referência primeiro.
     */
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
                    Math.abs(n - areaRef),
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

    /** Versão por posição absoluta do centro (usada nos fallbacks, fora de acharPorTemplate). */
    private fun avaliarAmbiguidadeEm(
        tela: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, centroAbs: Pair<Int, Int>, alvo: Modelo
    ): AvaliacaoPino {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return AvaliacaoPino(-2.0, null, -2.0, -4.0, true, true)
        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)
        return avaliarAmbiguidadePino(
            px, rw, rh,
            centroAbs.first - x0 - alvo.w / 2, centroAbs.second - y0 - alvo.h / 2, alvo
        )
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

            // Janela é inclusiva [winX0..winX1]; o modelo cabe até x = winX1 - w + 1 (antes faltava a última coluna/linha
            // e, com winW == m.w, maxRefX < winX0 fazia coerceIn lançar IllegalArgumentException).
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
        ultimaRecusaTemplate = null
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
        // Todos os pontos (grade de 2 px) acima do LIMIAR em cinza; cada um ainda precisa passar na validação em cor.
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

        val centroCandidato = Pair(
            x0 + bx + m.w / 2,
            y0 + by + m.h / 2
        )

        if (melhor >= Cal.LIMIAR_CANDIDATO_CONSISTENTE) {
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
            // Refina ±1 px um candidato da grade de 2 px
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

            // Melhor ponto primeiro; depois os próximos mais fortes, sem repetir a mesma vizinhança.
            // Um falso pino com cinza alto não pode esconder o pino verdadeiro (que pode ter cinza um pouco menor).
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

            val recusas = ArrayList<String>()
            for ((sc, tx, ty) in tentados) {
                val centro = Pair(x0 + tx + m.w / 2, y0 + ty + m.h / 2)
                val amb = avaliarAmbiguidadePino(px, rw, rh, tx, ty, m)
                if (!amb.recusado) {
                    melhorScore = sc
                    return centro
                }
                recusas.add(
                    "${amb.motivo()} nao clicou: score=${"%.2f".format(sc)} em ${centro.first},${centro.second} " + amb.texto(m.indice)
                )
            }
            ultimaRecusaTemplate = recusas.joinToString(" || ")
            ultimoCandidatoQuaseLimiar = null
            candidatoConsistente = null
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