package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import java.util.Locale

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
    return g >= Cal.VERDE_G_MIN && r <= Cal.VERDE_R_MAX && b <= Cal.VERDE_B_MAX
}

private fun ehPixelVerdeCaixa(p: Int): Boolean = ehVerdePinoAtivo(p)

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
    private var presencaDesde = 0L
    private var pausaManualDesde = 0L
    private var quadrosSemMonstroAlarme = 0
    private var estadoLogAlarme = 0
    private var ultimoLogAlarmeEm = 0L
    private var ultimoConfirmado: Int? = null
    private var timestampsResync: ArrayDeque<Long> = ArrayDeque()
    private var estadoLogLutaAlarme = 0
    private var ultimoLogLutaAlarmeEm = 0L

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
    private var quadrosPresencaLutaSeguidos = 0

    private var battleAncoraX: Int = -1
    private var battleAncoraY: Int = -1
    private var battleAncoraW: Int = 0
    private var battleAncoraH: Int = 0
    private var battleAncoraAssinatura: DoubleArray? = null
    private var inicioPresencaLutaMs: Long = 0L
    private var inicioAusenciaLutaMs: Long = 0L
    private var lutaConfirmadaAtiva: Boolean = false
    private var salvouQuadroInicioLuta = false
    private var salvouQuadroInicioAlarmeLuta = false

    private var recortesCasasLuta: Array<DoubleArray?> = arrayOfNulls(8)
    private var variacaoMaxCasasLuta: DoubleArray = DoubleArray(8)
    private var ultimoBarraTituloAbs: Pair<Int, Int>? = null
    private var ultimoLogResumoEm = 0L

    @Volatile
    private var melhorScore = 0.0

    @Synchronized
    fun carregar(ctx: Context, inicio: Int, fim: Int, larguraTela: Int) {
        val catalogo = CatalogoPinos.validar(ctx, inicio, fim)
        check(catalogo.valido) { catalogo.erro ?: "Catálogo de pinos vazio." }

        val lado = (Cal.LADO_ICONE * larguraTela / 2460f)
            .toInt().coerceAtLeast(8)
        val lista = ArrayList<Modelo>(catalogo.ids.size)

        for (n in catalogo.ids) {
            val raw = ctx.assets.open("pins/p%02d.png".format(Locale.ROOT, n)).use {
                BitmapFactory.decodeStream(it)
            } ?: error("Não foi possível decodificar o pino $n.")

            val bmp = if (raw.width == lado && raw.height == lado) raw
            else Bitmap.createScaledBitmap(raw, lado, lado, true).also {
                if (it !== raw) raw.recycle()
            }
            try {
                lista.add(Modelo(n, bmp))
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }

        check(lista.size == fim - inicio + 1) {
            "Catálogo incompleto: esperado ${fim - inicio + 1}, carregado ${lista.size}."
        }
        modelos = lista.toList()
        resetar()
    }

    @Synchronized
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

    @Synchronized
    fun resetar() {
        fase = FaseNavegacao.ESCOLHER
        alvo = 0
        direcaoIda = true
        ultimoClique = 0L
        iniciouCaminhadaEm = 0L
        tempoMaxPinoMs = 15000L
        pausadoAte = 0L
        pausadoPorPinoPerdido = false
        estadoPino.reiniciar(preservarFalhas = false)
        ignorarLutaAte = 0L
        estavaEmLuta = false
        tempoInicioLuta = 0L
        estavaEmLutaCaminhando = false
        verdeRelAntesDaLuta = null
        indiceCliqueVariavel = 0
        fimLutaEm = 0L
        versaoRegistradaNoLog = false
        presencaDesde = 0L
        pausaManualDesde = 0L
        quadrosSemMonstroAlarme = 0
        estadoLogAlarme = 0
        ultimoLogAlarmeEm = 0L
        ultimoConfirmado = null
        timestampsResync.clear()
        estadoLogLutaAlarme = 0
        ultimoLogLutaAlarmeEm = 0L
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
        inicioPresencaLutaMs = 0L
        inicioAusenciaLutaMs = 0L
        quadrosPresencaLutaSeguidos = 0
        lutaConfirmadaAtiva = false
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

    @Synchronized
    fun iniciarPausaManual() {
        if (pausaManualDesde == 0L) {
            pausaManualDesde = SystemClock.uptimeMillis()
        }
    }

    @Synchronized
    fun compensarPausaManual() {
        val inicio = pausaManualDesde
        if (inicio <= 0L) return
        val duracao = (SystemClock.uptimeMillis() - inicio).coerceAtLeast(0L)
        if (duracao > 0L) {
            if (tempoInicioLuta > 0L) tempoInicioLuta += duracao
            if (presencaDesde > 0L) presencaDesde += duracao
            if (iniciouCaminhadaEm > 0L) iniciouCaminhadaEm += duracao
            if (retomarVerdeAte > 0L) retomarVerdeAte += duracao
            if (pausadoAte > 0L) pausadoAte += duracao
            if (fimLutaEm > 0L) fimLutaEm += duracao
        }
        pausaManualDesde = 0L
    }

    fun estaPausadoPorPinoPerdido(): Boolean = pausadoPorPinoPerdido

    @Synchronized
    fun retomarPausaPinoPerdido() {
        pausadoPorPinoPerdido = false
        pausadoAte = 0L
        estadoPino.reiniciar(false)
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
            val logAvanco = formatarLog(
                faseNome, pinoId, score, qtdVerde, rgbVerde, distPx, progressoAntesReset,
                "$motivo (falhas=$falhasPino -> recuperacao/pausa)"
            )
            tratarEsgotamentoPinoSemPulo(agora, pinoId, context, tela)
            return logAvanco
        }

        fase = FaseNavegacao.ESCOLHER
        estadoPino.reiniciar(preservarFalhas = true)
        return formatarLog(
            faseNome, pinoId, score, qtdVerde, rgbVerde, distPx, progressoAntesReset,
            "$motivo (falhas=$falhasPino/${Cal.MAX_FALHAS_PINO})"
        )
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
                // Mantém o quadro de referência anterior à luta; atualizar aqui esconderia o corpo.
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
            if (clarosNaLinha >= (12f * (tela.width / 2460f)).toInt().coerceAtLeast(4)) {
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
        val raio = (8f * (quadro.width / 2460f)).toInt().coerceAtLeast(2)
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

    private fun temMonstroQuadroFresco(fallbackTela: Bitmap): Boolean {
        val q = capturarQuadroFresco(fallbackTela)
        return try {
            temMonstro(q)
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

    private fun clicarLootComRetry(x: Float, y: Float): Pair<Boolean, String> {
        var ultimo = Pair(false, "Toque não executado")
        repeat(3) { tentativa ->
            ultimo = ToqueService.clicarEAguardar(x, y, 30L)
            if (ultimo.first) return ultimo
            val aguardandoGesto = ultimo.second.contains("gesto anterior", ignoreCase = true) ||
                ultimo.second.contains("Toque anterior ainda não foi concluído", ignoreCase = true)
            if (!aguardandoGesto || tentativa == 2) return ultimo
            SystemClock.sleep(120L)
        }
        return ultimo
    }

    private fun executarAutoloot(larguraTela: Int, alturaTela: Int, context: Context, tela: Bitmap) {
        val inicioMs = SystemClock.uptimeMillis()
        var cliquesCount = 0
        LogRota.registrarPasso(context, "AUTOLOOT inicio tamCasa=${tamanhoCasaPx.toInt()}px", tela)
        fun registrarInterrupcaoMonstro() {
            LogRota.registrarPasso(context, "AUTOLOOT interrompido: monstro apareceu", tela)
            LogRota.registrarPasso(context, "AUTOLOOT fim interrompido=monstro cancelado=sim", tela)
        }

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

        // A espera inicial do autoloot pode deixar o bitmap recebido desatualizado.
        // Captura um quadro novo e extrai todos os recortes antes de liberar a imagem.
        val cortesDepois = Array(8) { DoubleArray(0) }
        val quadroComparacao = capturarQuadroFresco(tela)
        try {
            for (i in 0 until 8) {
                val d = deltas[i]
                cortesDepois[i] = extrairRecorteCasa(
                    quadroComparacao, centro.first, centro.second, d.first, d.second, ladoCasa
                )
            }
        } finally {
            if (quadroComparacao !== tela && !quadroComparacao.isRecycled) {
                quadroComparacao.recycle()
            }
        }

        for (i in 0 until 8) {
            val d = deltas[i]
            val cxCasa = centro.first + d.first * ladoCasa
            val cyCasa = centro.second + d.second * ladoCasa
            val corteAntes = recortesCasasLuta[i]
            val corteDepois = cortesDepois[i]
            val diff = if (corteAntes != null) calcularDiferencaMediaRecorte(corteAntes, corteDepois) else 0.0
            val piso = variacaoMaxCasasLuta[i].coerceIn(1.5, Cal.PISO_ANIMACAO_MAX_PX)
            if (i > 0) detalhesAvaliacao.append(";")
            detalhesAvaliacao.append("c${i + 1}(diff=${"%.2f".format(Locale.ROOT, diff)}/piso=${"%.2f".format(Locale.ROOT, piso)})")
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

            if (temMonstroQuadroFresco(tela)) {
                registrarInterrupcaoMonstro()
                return
            }

            val inicioCorpoMs = SystemClock.uptimeMillis()
            val cruzAntes = obterCruzMinimapaSegura(tela)

            // Um unico gesto por corpo evita double-tap ou comando de caminhada ate o corpo.
            val gestoCorpo = clicarLootComRetry(cand.px, cand.py)
            if (!gestoCorpo.first) {
                LogRota.registrarPasso(context, "AUTOLOOT toque corpo falhou: " + gestoCorpo.second, tela)
                continue
            }
            cliquesCount++

            if (cancelarAutoloot) {
                val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                LogRota.registrarPasso(
                    context,
                    "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim",
                    tela
                )
                return
            }
            LogRota.registrarPasso(
                context,
                "AUTOLOOT corpo ${idxCorpo + 1}/${ordenadas.size} casa=${cand.indiceCasa + 1} diff=${"%.2f".format(Locale.ROOT, cand.diff)} ${cand.px.toInt()},${cand.py.toInt()}",
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
                if (temMonstroQuadroFresco(tela)) {
                    registrarInterrupcaoMonstro()
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
                    val durSeg = (SystemClock.uptimeMillis() - inicioMs) / 1000L
                    LogRota.registrarPasso(context, "AUTOLOOT fim ${durSeg}s cliques=$cliquesCount cancelado=sim", tela)
                    return
                }
                if (SystemClock.uptimeMillis() - inicioCorpoMs >= Cal.TEMPO_MAX_POR_CORPO_MS) {
                    break
                }

                if (temMonstroQuadroFresco(tela)) {
                    registrarInterrupcaoMonstro()
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
                    val gestoItem = clicarLootComRetry(lx, ly)
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
        val distStr = if (distPx >= 0f) "%.1fpx".format(Locale.ROOT, distPx) else "N/A"
        val scoreStr = "%.2f".format(Locale.ROOT, score)
        val rgbStr = if (rgbVerde != null) {
            "rgb(${rgbVerde.first},${rgbVerde.second},${rgbVerde.third})"
        } else {
            "rgb(0,0,0)"
        }
        val diffStr = "%.2f".format(Locale.ROOT, ultimaDiffMinimapa)
        val base = "fase=$faseNome | pino=$pinoId | acerto=$scoreStr | verde=$qtdVerde | $rgbStr | dist=$distStr | semProgresso=$progresso | diffMini=$diffStr"
        return if (extra != null && extra.isNotEmpty()) "$base | $extra" else base
    }

    private fun registrarLogMudancaAlarme(
        novoEstado: Int,
        mensagem: String,
        agora: Long,
        context: Context,
        tela: Bitmap
    ) {
        if (estadoLogAlarme == novoEstado) return
        if (agora - ultimoLogAlarmeEm < 1000L) return
        estadoLogAlarme = novoEstado
        ultimoLogAlarmeEm = agora
        LogRota.registrarPasso(context, mensagem, tela)
    }

    fun passoSoAlarme(tela: Bitmap, context: Context, uriSom: String, volume: Int): String {
        ultimoUriSom = uriSom
        ultimoVolume = volume
        if (tela.height > tela.width) {
            return "Aguardando o jogo em paisagem"
        }
        val agora = SystemClock.uptimeMillis()
        val limiteSeg = (limiteAlarmeMs / 1000L).coerceAtLeast(1L)
        if (avaliarLutaComConfirmacao(tela, context, agora)) {
            quadrosSemMonstroAlarme = 0
            if (presencaDesde == 0L) {
                presencaDesde = agora
            }
            val decorridoMs = agora - presencaDesde
            val segundos = decorridoMs / 1000L
            return if (decorridoMs >= limiteAlarmeMs) {
                BotService.tocarAlarme(context, uriSom, volume)
                val msg = "ALARME: batalha há ${segundos}s"
                registrarLogMudancaAlarme(2, msg, agora, context, tela)
                msg
            } else {
                val msg = "Batalha: ${segundos}s de ${limiteSeg}s"
                registrarLogMudancaAlarme(1, msg, agora, context, tela)
                msg
            }
        } else {
            quadrosSemMonstroAlarme++
            return if (quadrosSemMonstroAlarme >= 2) {
                val decorridoAntes = if (presencaDesde > 0L) (agora - presencaDesde) / 1000L else 0L
                val estavaEmAlarme = estadoLogAlarme == 2
                presencaDesde = 0L
                BotService.pararAlarme()
                if (estavaEmAlarme) {
                    registrarLogMudancaAlarme(0, "Alarme parou por falta de monstro (durou ${decorridoAntes}s)", agora, context, tela)
                } else {
                    estadoLogAlarme = 0
                }
                "Sem monstros na Batalha"
            } else {
                "Verificando..."
            }
        }
    }

    @Synchronized
    fun passo(
        tela: Bitmap,
        context: Context,
        usaLoot: Boolean,
        usaAlerta: Boolean,
        uriSom: String,
        volume: Int,
        tocar: (Float, Float) -> Unit
    ): String {
        ultimoUriSom = uriSom
        ultimoVolume = volume
        if (!versaoRegistradaNoLog) {
            versaoRegistradaNoLog = true
            val linhaVersao = "VERSAO Rota 1.9.4 | LIMIAR=${"%.2f".format(Locale.ROOT, Cal.LIMIAR)} | LIMIAR_VERDE=${"%.2f".format(Locale.ROOT, Cal.LIMIAR_VERDE)} | MARGEM_VERDE=${"%.2f".format(Locale.ROOT, Cal.MARGEM_VERDE)} | INTERVALO_CLIQUE=${intervaloCliqueMs} | tela=${tela.width}x${tela.height}"
            LogRota.registrarPasso(context, linhaVersao, tela)
        }
        val texto = passoInterno(tela, context, usaLoot, usaAlerta, uriSom, volume, tocar)
        LogRota.registrarPasso(context, texto, tela)
        return texto
    }


    private fun localizarRegiaoBattle(tela: Bitmap): Pair<IntArray, Int> {
        val defX0 = (tela.width * Cal.BATALHA_X0).toInt().coerceIn(0, tela.width - 1)
        val defY0 = (tela.height * Cal.BATALHA_Y0).toInt().coerceIn(0, tela.height - 1)
        val defX1 = (tela.width * Cal.BATALHA_X1).toInt().coerceIn(defX0 + 1, tela.width)
        val defY1 = (tela.height * Cal.BATALHA_Y1).toInt().coerceIn(defY0 + 1, tela.height)

        val ancX = battleAncoraX
        val ancY = battleAncoraY
        val ancW = battleAncoraW
        val ancH = battleAncoraH
        val ancSig = battleAncoraAssinatura
        if (ancX >= 0 && ancY >= 0 && ancW > 0 && ancH > 0 && ancSig != null &&
            ancX + ancW <= tela.width && ancY + ancH <= tela.height
        ) {
            val pxAnc = IntArray(ancW * ancH)
            tela.getPixels(pxAnc, 0, ancW, ancX, ancY, ancW, ancH)
            var diff = 0.0
            for (i in 0 until minOf(pxAnc.size, ancSig.size)) {
                diff += abs(cinza(pxAnc[i]) - ancSig[i])
            }
            diff /= ancSig.size.toDouble().coerceAtLeast(1.0)
            if (diff <= 14.0) {
                val bx0 = ancX.coerceIn(0, tela.width - 2)
                val by0 = (ancY + ancH).coerceIn(0, tela.height - 2)
                val bx1 = (bx0 + (defX1 - defX0).coerceAtLeast(40)).coerceIn(bx0 + 1, tela.width)
                val by1 = (by0 + (defY1 - defY0).coerceAtLeast(60)).coerceIn(by0 + 1, tela.height)
                return Pair(intArrayOf(bx0, by0, bx1, by1), 1)
            } else {
                battleAncoraX = -1
                battleAncoraY = -1
                battleAncoraAssinatura = null
            }
        }

        val buscaX0 = 0
        val buscaX1 = (tela.width * 0.35f).toInt().coerceIn(20, tela.width)
        val buscaY0 = (tela.height * 0.08f).toInt().coerceIn(0, tela.height - 1)
        val buscaY1 = (tela.height * 0.45f).toInt().coerceIn(buscaY0 + 10, tela.height)
        val bw = buscaX1 - buscaX0
        val bh = buscaY1 - buscaY0
        if (bw > 16 && bh > 12) {
            val pxBusca = IntArray(bw * bh)
            tela.getPixels(pxBusca, 0, bw, buscaX0, buscaY0, bw, bh)
            val faixaH = 16.coerceAtMost(bh)
            val faixaW = 90.coerceAtMost(bw)
            var melhorLinhaClaros = 0
            var melhorYRel = -1
            var melhorXRel = 0
            var yy = 0
            while (yy <= bh - faixaH) {
                var xx = 0
                while (xx <= bw - faixaW) {
                    var claros = 0
                    for (dy in 0 until faixaH) {
                        val row = (yy + dy) * bw + xx
                        for (dx in 0 until faixaW) {
                            val p = pxBusca[row + dx]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            if (r > 155 && g > 145 && b > 125) claros++
                        }
                    }
                    if (claros > melhorLinhaClaros) {
                        melhorLinhaClaros = claros
                        melhorXRel = xx
                        melhorYRel = yy
                    }
                    xx += 8
                }
                yy += 4
            }
            if (melhorLinhaClaros >= (24f * escalaBusca).toInt().coerceAtLeast(8) && melhorYRel >= 0) {
                val ax = buscaX0 + melhorXRel
                val ay = buscaY0 + melhorYRel
                val aw = faixaW
                val ah = faixaH
                val pxSig = IntArray(aw * ah)
                tela.getPixels(pxSig, 0, aw, ax, ay, aw, ah)
                battleAncoraX = ax
                battleAncoraY = ay
                battleAncoraW = aw
                battleAncoraH = ah
                battleAncoraAssinatura = DoubleArray(pxSig.size) { cinza(pxSig[it]) }

                val bx0 = ax.coerceIn(0, tela.width - 2)
                val by0 = (ay + ah).coerceIn(0, tela.height - 2)
                val bx1 = (bx0 + (defX1 - defX0).coerceAtLeast(40)).coerceIn(bx0 + 1, tela.width)
                val by1 = (by0 + (defY1 - defY0).coerceAtLeast(60)).coerceIn(by0 + 1, tela.height)
                return Pair(intArrayOf(bx0, by0, bx1, by1), 2)
            }
        }

        return Pair(intArrayOf(defX0, defY0, defX1, defY1), 0)
    }

    private fun contarLinhasOcupadasBattle(tela: Bitmap): Int {
        val (rect, _) = localizarRegiaoBattle(tela)
        val x0 = rect[0]
        val y0 = rect[1]
        val x1 = rect[2]
        val y1 = rect[3]
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 8 || rh <= 6) return 0

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        val escala = (tela.width / 2460f).coerceIn(0.4f, 1.5f)
        val alturaLinha = (20f * escala).toInt().coerceIn(8, 36)
        val spriteLargura = (22f * escala).toInt().coerceIn(8, 36)

        var linhasOcupadas = 0
        var yStart = 0
        while (yStart + 4 <= rh) {
            val yEnd = (yStart + alturaLinha).coerceAtMost(rh)
            val hLinha = yEnd - yStart

            var temSprite = false
            for (dy in 0 until hLinha) {
                val row = (yStart + dy) * rw
                for (dx in 0 until spriteLargura.coerceAtMost(rw)) {
                    val p = px[row + dx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val fundo = r <= 35 && g <= 45 && b in 18..80
                    if (!fundo && (r > 95 || g > 105 || b > 125)) {
                        temSprite = true
                        break
                    }
                }
                if (temSprite) break
            }

            var temBarraVidaFina = false
            if (temSprite) {
                val barraXStart = spriteLargura.coerceAtMost(rw - 1)
                val minComprimentoBarra = (14f * escala).toInt().coerceAtLeast(6)
                for (dy in 0 until hLinha) {
                    var runLen = 0
                    for (dx in barraXStart until rw) {
                        val p = px[(yStart + dy) * rw + dx]
                        if (BatalhaEngine.ehCorHp(p)) {
                            runLen++
                        } else {
                            if (runLen >= minComprimentoBarra) {
                                var alturaRun = 1
                                val checkX = dx - 1
                                var up = dy - 1
                                while (up >= 0) {
                                    val pu = px[(yStart + up) * rw + checkX]
                                    if (!BatalhaEngine.ehCorHp(pu)) break
                                    alturaRun++
                                    up--
                                }
                                var dn = dy + 1
                                while (dn < hLinha) {
                                    val pd = px[(yStart + dn) * rw + checkX]
                                    if (!BatalhaEngine.ehCorHp(pd)) break
                                    alturaRun++
                                    dn++
                                }
                                if (alturaRun in 2..5) {
                                    temBarraVidaFina = true
                                    break
                                }
                            }
                            runLen = 0
                        }
                    }
                    if (!temBarraVidaFina && runLen >= minComprimentoBarra) {
                        temBarraVidaFina = true
                    }
                    if (temBarraVidaFina) break
                }
            }

            if (temSprite && temBarraVidaFina) {
                linhasOcupadas++
            }

            yStart += alturaLinha
        }

        return linhasOcupadas
    }

    private fun avaliarLutaComConfirmacao(tela: Bitmap, context: Context, agora: Long): Boolean {
        val linhasNovo = contarLinhasOcupadasBattle(tela)
        val temLegado = BatalhaEngine.temMonstroLegado(tela)
        val temAlgoNovo = BatalhaEngine.confirmarPresenca(linhasNovo, temLegado)
        if (temAlgoNovo) quadrosPresencaLutaSeguidos++ else quadrosPresencaLutaSeguidos = 0
        if (temAlgoNovo) {
            inicioAusenciaLutaMs = 0L
            if (inicioPresencaLutaMs == 0L) {
                inicioPresencaLutaMs = agora
            }
            if (!lutaConfirmadaAtiva && quadrosPresencaLutaSeguidos >= 2 && (agora - inicioPresencaLutaMs) >= 150L) {
                lutaConfirmadaAtiva = true
                prepararCapturaCasasAntesLuta(tela)
                LogRota.registrarPasso(context, "BATALHA_INICIO linhasOcupadas=$linhasNovo", tela)
                if (!salvouQuadroInicioLuta) {
                    salvouQuadroInicioLuta = true
                    LogRota.salvarQuadroDiagnostico(context, "batalha_inicio", tela)
                }
            }
        } else {
            inicioPresencaLutaMs = 0L
            if (lutaConfirmadaAtiva) {
                if (inicioAusenciaLutaMs == 0L) {
                    inicioAusenciaLutaMs = agora
                }
                if ((agora - inicioAusenciaLutaMs) >= 300L) {
                    lutaConfirmadaAtiva = false
                    inicioAusenciaLutaMs = 0L
                    salvouQuadroInicioLuta = false
                    salvouQuadroInicioAlarmeLuta = false
                }
            }
        }

        if (lutaConfirmadaAtiva) {
            atualizarMonitoramentoCasasDuranteLuta(tela)
        }

        return lutaConfirmadaAtiva
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
    ): String {
        val minToqueX = 28f.coerceAtMost((tela.width - 1).toFloat())
        val maxToqueX = (tela.width * 0.985f - 20f).coerceAtLeast(minToqueX)
        val minToqueY = 28f.coerceAtMost((tela.height - 1).toFloat())
        val maxToqueY = (tela.height - 28f).coerceAtLeast(minToqueY)
        val pinoId = modelo.indice

        if (nivelRecuperacaoPino == 0) {
            val buscaTemplate = buscarTemplateSemAlterarEstado(tela, x0, y0, x1, y1, modelo, cx, cy)
            val posReal = ultimoPosPinoConhecidaRel ?: buscaTemplate.first?.let {
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

        // Uma escada completa sem localizar o pino conta como uma falha.
        // O limite MAX_FALHAS_PINO passa a valer também para pino ausente/sem progresso,
        // não apenas para o antigo caso "nao ha rota".
        return registrarFalhaPino(
            agora = agora,
            faseNome = fase.name,
            pinoId = pinoId,
            score = melhorScore,
            qtdVerde = ultimoQtdVerde,
            rgbVerde = ultimoRgbVerde,
            distPx = ultimaDistVerde,
            progressoAntesReset = semProgresso,
            motivo = "pino nao localizado apos escada de recuperacao",
            context = context,
            tela = tela
        )
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
        if (cruz == null) {
            val agoraSemCruz = SystemClock.uptimeMillis()
            val batalhaSemCruz = avaliarLutaComConfirmacao(tela, context, agoraSemCruz)
            if (batalhaSemCruz) {
                if (!estavaEmLuta) {
                    estavaEmLuta = true
                    tempoInicioLuta = agoraSemCruz
                    estavaEmLutaCaminhando = (fase == FaseNavegacao.CAMINHANDO)
                    verdeRelAntesDaLuta = ultimoVerdeRel
                    estadoLogLutaAlarme = 0
                }
                val duracao = (agoraSemCruz - tempoInicioLuta).coerceAtLeast(0L)
                val segundos = duracao / 1000L
                if (usaAlerta && duracao >= limiteAlarmeMs) {
                    BotService.tocarAlarme(context, uriSom, volume)
                    if (estadoLogLutaAlarme != 1) {
                        estadoLogLutaAlarme = 1
                        LogRota.registrarPasso(context, "ALARME: batalha há " + segundos + "s (sem cruz)", tela)
                    }
                    return "ALARME: batalha há " + segundos + "s (sem cruz)"
                }
                return "LUTA " + segundos + "s - sem cruz no minimapa"
            }
            if (estavaEmLuta) {
                if (estadoLogLutaAlarme == 1) BotService.pararAlarme()
                return "BATALHA ENCERRADA - aguardando cruz para retomar"
            }
            if (usaAlerta) BotService.pararAlarme()
            return "SEM CRUZ NO MINIMAPA (pausado)"
        }

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
        val monstroDetectado = if (agoraLuta >= ignorarLutaAte) {
            avaliarLutaComConfirmacao(tela, context, agoraLuta)
        } else {
            false
        }

        if (monstroDetectado) {
            if (!estavaEmLuta) {
                estavaEmLuta = true
                tempoInicioLuta = agora
                estavaEmLutaCaminhando = (fase == FaseNavegacao.CAMINHANDO)
                verdeRelAntesDaLuta = ultimoVerdeRel
                estadoLogLutaAlarme = 0
            }

            val duracaoLuta = agora - tempoInicioLuta
            val segsLuta = duracaoLuta / 1000L
            if (usaAlerta && duracaoLuta >= limiteAlarmeMs) {
                if (!salvouQuadroInicioAlarmeLuta) {
                    salvouQuadroInicioAlarmeLuta = true
                    val linhasAtuais = contarLinhasOcupadasBattle(tela)
                    LogRota.registrarPasso(context, "ALARME_INICIO linhasOcupadas=$linhasAtuais duracao=${segsLuta}s", tela)
                    LogRota.registrarPasso(context, "luta excedeu limite duracao=${segsLuta}s", tela)
                    LogRota.salvarQuadroDiagnostico(context, "alarme_luta", tela)
                }
                BotService.tocarAlarme(context, uriSom, volume)
                val msgAlarme = "ALARME: batalha há ${segsLuta}s"
                if (estadoLogLutaAlarme != 1 && agora - ultimoLogLutaAlarmeEm >= 1000L) {
                    estadoLogLutaAlarme = 1
                    ultimoLogLutaAlarmeEm = agora
                    LogRota.registrarPasso(context, msgAlarme, tela)
                }
                return msgAlarme
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

            if (estadoLogLutaAlarme == 1 && agora - ultimoLogLutaAlarmeEm >= 1000L) {
                estadoLogLutaAlarme = 0
                ultimoLogLutaAlarmeEm = agora
                LogRota.registrarPasso(context, "Alarme de luta encerrado após ${duracaoTotalSeg}s", tela)
            } else {
                estadoLogLutaAlarme = 0
            }

            estavaEmLuta = false
            tempoInicioLuta = 0L
            estavaEmLutaCaminhando = false
            verdeRelAntesDaLuta = null
            quadrosMonstro = 0
            salvouQuadroInicioAlarmeLuta = false

            if (usaLoot && LootEngine.ignorarLutaCurta(duracaoTotalLutaMs)) {
                LogRota.registrarPasso(context, "AUTOLOOT ignorado: luta curta", tela)
                ultimoClique = 0L
                pausadoAte = agora + 200L
                quadrosChegada = 0
                quadrosSumidoVerde = 0
                semProgresso = 0
                cliquesVerde = 0
                naoVisto = 0

                val verdeRetomada = if (veioDeCaminhando && refVerde != null) {
                    modelos.getOrNull(alvo)?.let { modeloAtual ->
                        acharPinoVerdeLocal(tela, x0, y0, x1, y1, modeloAtual, refVerde)
                    }
                } else {
                    null
                }
                if (verdeRetomada != null) {
                    val posRetomada = verdeRetomada.posicao
                    val distRetomada = hypot(
                        ((x0 + posRetomada.first) - cx).toDouble(),
                        ((y0 + posRetomada.second) - cy).toDouble()
                    ).toFloat()
                    fase = FaseNavegacao.CAMINHANDO
                    ultimoVerdeRel = posRetomada
                    ultimoPosPinoConhecidaRel = posRetomada
                    ultimoPosPinoEraVerde = true
                    vistoVerde = true
                    ultimoQtdVerde = verdeRetomada.quantidade
                    ultimoRgbVerde = verdeRetomada.rgbMedio
                    ultimaDistVerde = distRetomada
                    if (menorDistVerde < 0f || distRetomada < menorDistVerde) {
                        menorDistVerde = distRetomada
                    }
                    retomarVerdeAte = agora + 2000L
                    iniciouCaminhadaEm = agora
                    tempoMaxPinoMs = calcularTempoMaxPinoMs(
                        if (distRetomada > 0f) distRetomada else pixelsPorCasa * 4f
                    )
                    LogRota.registrarPasso(context, "AUTOLOOT ignorado: luta curta - retomando caminhada", tela)
                    return "AUTOLOOT ignorado: luta curta - retomando caminhada"
                }

                fase = FaseNavegacao.ESCOLHER
                retomarVerdeAte = 0L
                verdeVeioDeTemplate = false
                ultimoVerdeRel = null
                ultimoQtdVerde = 0
                ultimoRgbVerde = null
                vistoVerde = false
                return "AUTOLOOT ignorado: luta curta - procurando pino"
            }

            lutas++

            val antesLootMs = SystemClock.uptimeMillis()
            if (usaLoot) {
                executarAutoloot(tela.width, tela.height, context, tela)
            }
            val agoraPosLoot = SystemClock.uptimeMillis()
            fimLutaEm = agoraPosLoot
            val duracaoPausaTotalMs = duracaoTotalLutaMs + (agoraPosLoot - antesLootMs).coerceAtLeast(0L)
            if (iniciouCaminhadaEm > 0L && duracaoPausaTotalMs > 0L) {
                iniciouCaminhadaEm += duracaoPausaTotalMs
            }

            ultimoClique = 0L
            pausadoAte = agoraPosLoot + 200L
            quadrosChegada = 0
            quadrosSumidoVerde = 0
            ultimaDist = -1f
            ultimaDistVerde = -1f
            menorDistVerde = -1f
            semProgresso = 0
            cliquesVerde = 0
            naoVisto = 0

            // O autoloot pode durar dezenas de segundos. Nunca retome a rota
            // usando o bitmap e o relógio capturados antes da coleta.
            val telaPosLoot = if (usaLoot) capturarQuadroFresco(tela) else tela
            try {
                val cruzPosLoot = acharCruz(telaPosLoot, x0, y0, x1, y1)
                val centroXPosLoot = cruzPosLoot?.first?.toFloat() ?: cx
                val centroYPosLoot = cruzPosLoot?.second?.toFloat() ?: cy
                val verdePosLoot = if (veioDeCaminhando && refVerde != null) {
                    modelos.getOrNull(alvo)?.let { modeloAtual ->
                        acharPinoVerdeLocal(telaPosLoot, x0, y0, x1, y1, modeloAtual, refVerde)
                    }
                } else {
                    null
                }

                if (verdePosLoot != null) {
                    val posRel = verdePosLoot.posicao
                    val distPosLoot = hypot(
                        ((x0 + posRel.first) - centroXPosLoot).toDouble(),
                        ((y0 + posRel.second) - centroYPosLoot).toDouble()
                    ).toFloat()
                    fase = FaseNavegacao.CAMINHANDO
                    ultimoVerdeRel = posRel
                    ultimoPosPinoConhecidaRel = posRel
                    ultimoPosPinoEraVerde = true
                    verdeVeioDeTemplate = false
                    vistoVerde = true
                    ultimoQtdVerde = verdePosLoot.quantidade
                    ultimoRgbVerde = verdePosLoot.rgbMedio
                    ultimaDistVerde = distPosLoot
                    menorDistVerde = distPosLoot
                    retomarVerdeAte = agoraPosLoot + 2000L
                    iniciouCaminhadaEm = agoraPosLoot
                    tempoMaxPinoMs = calcularTempoMaxPinoMs(
                        if (distPosLoot > 0f) distPosLoot else pixelsPorCasa * 4f
                    )
                    return if (usaLoot) {
                        "AUTOLOOT concluído - retomando verde atualizado"
                    } else {
                        "MONSTRO MORREU - retomando verde atualizado"
                    }
                }

                fase = FaseNavegacao.ESCOLHER
                retomarVerdeAte = 0L
                verdeVeioDeTemplate = false
                ultimoVerdeRel = null
                ultimoQtdVerde = 0
                ultimoRgbVerde = null
                vistoVerde = false
                falhasPino = 0

                return if (usaLoot) {
                    "AUTOLOOT concluído - procurando pino"
                } else {
                    "MONSTRO MORREU - procurando pino"
                }
            } finally {
                if (telaPosLoot !== tela && !telaPosLoot.isRecycled) {
                    telaPosLoot.recycle()
                }
            }
        }

        val tamanho = modelos.size
        if (alvo < 0 || alvo >= tamanho) {
            resetar()
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

            if (iniciouCaminhadaEm > 0L && (agora - iniciouCaminhadaEm) >= tempoMaxPinoMs) {
                iniciouCaminhadaEm = agora
                return executarEscadaRecuperacaoPino(
                    tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
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
                    semProgresso = 0
                    cliquesVerde = 0
                    return executarEscadaRecuperacaoPino(
                        tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
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
                    quadrosSumidoVerde = 0
                    return executarEscadaRecuperacaoPino(
                        tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
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
        if (verdes.isNotEmpty()) {
            val verde = verdes.first()
            val avaliacao = avaliarPinoVerdeComModelos(tela, x0, y0, x1, y1, verde.posicao, modelo)
            if (avaliacao.valido) {
                val rivalInfo = if (avaliacao.rivalIndice != null) {
                    "rival=#${avaliacao.rivalIndice}(${"%.2f".format(Locale.ROOT, avaliacao.rivalScore)}) margem=${"%+.2f".format(Locale.ROOT, avaliacao.margem)}"
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
        val rivalLog = if (achado != null) {
            val refCentroRel = Pair(
                (achado.first - x0).coerceIn(0, (x1 - x0 - 1).coerceAtLeast(0)),
                (achado.second - y0).coerceIn(0, (y1 - y0 - 1).coerceAtLeast(0))
            )
            val avaliacaoCandidato = avaliarPinoVerdeComModelos(tela, x0, y0, x1, y1, refCentroRel, modelo)
            if (avaliacaoCandidato.rivalIndice != null) {
                "rival=#${avaliacaoCandidato.rivalIndice}(${"%.2f".format(Locale.ROOT, avaliacaoCandidato.rivalScore)}) margem=${"%+.2f".format(Locale.ROOT, avaliacaoCandidato.margem)}"
            } else {
                "rival=nenhum"
            }
        } else {
            "rival=nenhum"
        }

        if (achado == null) {
            quadrosPertoDaCruz = 0
            val candidatoPos = candidatoConsistente
            if (scoreAtual >= 0.90 && candidatoPos != null) {
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
                if (distFb > toleranciaChegada && agora - ultimoClique >= intervaloCliqueMs) {
                    val px = candidatoPos.first.toFloat().coerceIn(minToqueXVal, maxToqueXVal)
                    val py = candidatoPos.second.toFloat().coerceIn(minToqueYVal, maxToqueYVal)
                    val toque = dispararToqueSeguro(
                        tela.width, tela.height, px, py, tocar
                    )
                    ultimoClique = agora
                    iniciarCaminhada(Pair(candidatoPos.first - x0, candidatoPos.second - y0), distFb, agora, veioDeTemplate = false)
                    val sentidoStr = if (direcaoIda) "IDA" else "VOLTA"
                    val logToque = anexarTempoPosLuta("CLIQUE ${toque.first.toInt()},${toque.second.toInt()}", agora)
                    return formatarLog(
                        fase.name, indice, scoreAtual, 0, ultimoRgbVerde, distFb, semProgresso,
                        "$sentidoStr | $rivalLog | $logToque (fallback score - sem avancar alvo)"
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
                    if (distVerde > toleranciaChegada && agora - ultimoClique >= intervaloCliqueMs) {
                        val px = (x0 + verdeRel.first).toFloat().coerceIn(minToqueXVal, maxToqueXVal)
                        val py = (y0 + verdeRel.second).toFloat().coerceIn(minToqueYVal, maxToqueYVal)
                        val toque = dispararToqueSeguro(
                            tela.width, tela.height, px, py, tocar
                        )
                        ultimoClique = agora
                        iniciarCaminhada(verdeRel, distVerde, agora, veioDeTemplate = false)
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
                naoVisto = 0
                return executarEscadaRecuperacaoPino(
                    tela, x0, y0, x1, y1, cx, cy, toleranciaChegada, agora, context, tocar, modelo
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

    private fun buscarTemplateSemAlterarEstado(
        tela: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        modelo: Modelo,
        cx: Float,
        cy: Float
    ): Pair<Pair<Int, Int>?, Double> {
        val melhorScoreAnterior = melhorScore
        val ultimoCandidatoAnterior = ultimoCandidatoQuaseLimiar
        val candidatoConsistenteAnterior = candidatoConsistente
        return try {
            val posicao = acharPorTemplate(tela, x0, y0, x1, y1, modelo, cx, cy)
            Pair(posicao, melhorScore)
        } finally {
            melhorScore = melhorScoreAnterior
            ultimoCandidatoQuaseLimiar = ultimoCandidatoAnterior
            candidatoConsistente = candidatoConsistenteAnterior
        }
    }

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
        if (timestampsResync.size >= 2) return null

        val tamanho = modelos.size
        val esperadoIdx: Int? = if (tamanho <= 1) {
            0
        } else if (direcaoIda) {
            if (refIdx < tamanho - 1) refIdx + 1 else (refIdx - 1).coerceAtLeast(0)
        } else {
            if (refIdx > 0) refIdx - 1 else (refIdx + 1).coerceAtMost(tamanho - 1)
        }

        val posicoesCandidatas = HashMap<Int, Pair<Int, Int>>()
        val origensVerdes = HashSet<Int>()

        fun avaliarCandidato(idxCandidato: Int?): Pair<Boolean, Float>? {
            if (idxCandidato == null || idxCandidato !in modelos.indices) return null
            val modeloCandidato = modelos[idxCandidato]

            val buscaTemplate = buscarTemplateSemAlterarEstado(
                tela, x0, y0, x1, y1, modeloCandidato, cx, cy
            )
            val posTemplate = buscaTemplate.first
            if (posTemplate != null && buscaTemplate.second >= Cal.LIMIAR.toDouble()) {
                val distTemplate = hypot(
                    (posTemplate.first - cx).toDouble(),
                    (posTemplate.second - cy).toDouble()
                ).toFloat()
                if (distTemplate > toleranciaChegada) {
                    posicoesCandidatas[idxCandidato] = posTemplate
                    origensVerdes.remove(idxCandidato)
                    return Pair(true, distTemplate)
                }
            }

            // Se o template não localizar o ícone, tenta o marcador verde,
            // mas só aceita quando o desenho corresponde exclusivamente ao pino avaliado.
            val verdes = buscarComponentesVerdes(tela, x0, y0, x1, y1)
            for (verde in verdes) {
                val validacao = avaliarPinoVerdeComModelos(
                    tela, x0, y0, x1, y1, verde.posicao, modeloCandidato
                )
                if (!validacao.valido) continue

                val posAbs = Pair(x0 + verde.posicao.first, y0 + verde.posicao.second)
                val distVerde = hypot(
                    (posAbs.first - cx).toDouble(),
                    (posAbs.second - cy).toDouble()
                ).toFloat()
                if (distVerde <= toleranciaChegada) continue

                posicoesCandidatas[idxCandidato] = posAbs
                origensVerdes.add(idxCandidato)
                return Pair(true, distVerde)
            }
            return null
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
        if (abs(escolhidoIdx - refIdx) > 1 || escolhidoIdx == alvo) return null

        val idAntigo = if (alvo in modelos.indices) modelos[alvo].indice else alvo
        val idNovo = modelos[escolhidoIdx].indice
        val posicaoConfirmada = posicoesCandidatas[escolhidoIdx]
        val origemVerde = escolhidoIdx in origensVerdes

        alvo = escolhidoIdx
        estadoPino.reiniciar(preservarFalhas = false)
        if (posicaoConfirmada != null && origemVerde) {
            val posRel = Pair(posicaoConfirmada.first - x0, posicaoConfirmada.second - y0)
            ultimoPosPinoConhecidaRel = posRel
            ultimoPosPinoEraVerde = true
            ultimoVerdeRel = posRel
            vistoVerde = true
        }
        fase = FaseNavegacao.ESCOLHER
        timestampsResync.addLast(agora)

        registrarLogAlvo(context, tela)
        val origem = if (origemVerde) "verde_validado" else "template"
        return "RESYNC alvo $idAntigo->$idNovo origem=$origem"
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
        val caixaW = (44f * escala).toInt().coerceAtLeast(1)
        val caixaH = (48f * escala).toInt().coerceAtLeast(1)
        val escalaArea = (escala * escala).toDouble()
        val minQtd = (230.0 * escalaArea).toInt().coerceAtLeast(1)
        val maxQtd = (520.0 * escalaArea).toInt().coerceAtLeast(minQtd)

        val stride = w + 1
        val integral = IntArray((h + 1) * stride)

        for (yy in 0 until h) {
            var linhaSoma = 0
            val rowOffset = yy * w
            val intPrevOffset = yy * stride
            val intCurrOffset = (yy + 1) * stride
            for (xx in 0 until w) {
                if (ehPixelVerdeCaixa(px[rowOffset + xx])) {
                    linhaSoma++
                }
                integral[intCurrOffset + xx + 1] = integral[intPrevOffset + xx + 1] + linhaSoma
            }
        }

        var melhorContagem = -1
        var melhorX0 = 0
        var melhorY0 = 0
        var melhorX1 = 0
        var melhorY1 = 0

        var y = 0
        while (y < h) {
            val yBox1 = (y + caixaH - 1).coerceAtMost(h - 1)
            val rA = y * stride
            val rB = (yBox1 + 1) * stride
            var x = 0
            while (x < w) {
                val xBox1 = (x + caixaW - 1).coerceAtMost(w - 1)
                val cA = x
                val cB = xBox1 + 1
                val contagem = integral[rB + cB] - integral[rA + cB] - integral[rB + cA] + integral[rA + cA]
                if (contagem in minQtd..maxQtd && contagem > melhorContagem) {
                    melhorContagem = contagem
                    melhorX0 = x
                    melhorY0 = y
                    melhorX1 = xBox1
                    melhorY1 = yBox1
                }
                x += 2
            }
            y += 2
        }

        if (melhorContagem < 0) return emptyList()

        var sx = 0L
        var sy = 0L
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0

        for (yy in melhorY0..melhorY1) {
            val rowOffset = yy * w
            for (xx in melhorX0..melhorX1) {
                val p = px[rowOffset + xx]
                if (ehPixelVerdeCaixa(p)) {
                    sx += xx.toLong()
                    sy += yy.toLong()
                    sr += ((p shr 16) and 0xFF).toLong()
                    sg += ((p shr 8) and 0xFF).toLong()
                    sb += (p and 0xFF).toLong()
                    n++
                }
            }
        }

        if (n <= 0) return emptyList()

        return listOf(
            DeteccaoVerde(
                posicao = Pair((sx / n).toInt(), (sy / n).toInt()),
                quantidade = n,
                rgbMedio = Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
            )
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

        if (quantidade < m.pixelsValidos * 0.40) return -1.0

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

            var yy = winY0
            while (yy <= winY1 - m.h) {
                var xx = winX0
                while (xx <= winX1 - m.w) {
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

            val maxRefX = winX1 - m.w
            val maxRefY = winY1 - m.h
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
        val linhas = contarLinhasOcupadasBattle(tela)
        return BatalhaEngine.confirmarPresenca(linhas, BatalhaEngine.temMonstroLegado(tela))
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