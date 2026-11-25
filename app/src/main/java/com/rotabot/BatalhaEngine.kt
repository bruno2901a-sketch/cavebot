package com.rotabot

import android.content.Context
import android.graphics.Bitmap

/**
 * ÚNICO detector de batalha do app.
 *
 * - [medir]      leitura de UM quadro (sem estado, sem log).
 * - [atualizar]  máquina de estados: luta só começa após Cal.QUADROS_INICIO_LUTA quadros
 *                seguidos com monstro e só termina após Cal.QUADROS_FIM_LUTA quadros
 *                seguidos sem monstro. Também decide o alarme e registra no log cada
 *                mudança de estado (região, linhas ocupadas, pontuação).
 *
 * Todos os fluxos (passo, passoSoAlarme, autoloot) usam [atualizar].
 */
object BatalhaEngine {

    enum class Fase { SEM_MONSTRO, CONFIRMANDO_INICIO, EM_LUTA, CONFIRMANDO_FIM }

    class Leitura(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val modo: String,          // DIVISOR | FIXO | PAINEL_AUSENTE
        val linhasOcupadas: Int,
        val pontuacao: Int,
        val pixelsSprite: Int,
        val pixelsBarra: Int,
        val fracFundo: Float,
        val presente: Boolean
    ) {
        fun resumo(): String =
            "regiao=[$x0,$y0,$x1,$y1] modo=$modo linhas=$linhasOcupadas " +
                "pontuacao=$pontuacao sprite=$pixelsSprite barra=$pixelsBarra " +
                "fundo=${"%.2f".format(fracFundo)} presente=$presente"
    }

    class Estado(
        val fase: Fase,
        val lutaConfirmada: Boolean,
        val inicioLutaMs: Long,
        val iniciouAgora: Boolean,
        val terminouAgora: Boolean,
        val alarmeDevido: Boolean,
        val leitura: Leitura
    )

    private var fase = Fase.SEM_MONSTRO
    private var quadrosCom = 0
    private var quadrosSem = 0
    private var inicioLutaMs = 0L
    private var alarmeAtivo = false
    private var ultimoModo = ""

    @Synchronized
    fun resetar() {
        fase = Fase.SEM_MONSTRO
        quadrosCom = 0
        quadrosSem = 0
        inicioLutaMs = 0L
        alarmeAtivo = false
        ultimoModo = ""
    }

    /** Zera só as sequências de quadros (usado na janela "ignorar luta"). Mantém a luta confirmada. */
    @Synchronized
    fun zerarSequencias() {
        quadrosCom = 0
        quadrosSem = 0
        if (fase == Fase.CONFIRMANDO_INICIO) fase = Fase.SEM_MONSTRO
        if (fase == Fase.CONFIRMANDO_FIM) fase = Fase.EM_LUTA
    }

    @Synchronized
    fun lutaConfirmada(): Boolean = fase == Fase.EM_LUTA || fase == Fase.CONFIRMANDO_FIM

    /**
     * Processa UM quadro.
     * @param limiteAlarmeMs tempo de luta confirmada para tocar; null = alarme desligado.
     */
    @Synchronized
    fun atualizar(tela: Bitmap, agora: Long, context: Context?, limiteAlarmeMs: Long?): Estado {
        val l = medir(tela)
        val faseAntes = fase
        var iniciou = false
        var terminou = false

        if (l.presente) {
            quadrosSem = 0
            quadrosCom++
            when (fase) {
                Fase.SEM_MONSTRO -> fase = Fase.CONFIRMANDO_INICIO
                Fase.CONFIRMANDO_FIM -> fase = Fase.EM_LUTA
                else -> {}
            }
            if (fase == Fase.CONFIRMANDO_INICIO && quadrosCom >= Cal.QUADROS_INICIO_LUTA) {
                fase = Fase.EM_LUTA
                inicioLutaMs = agora
                iniciou = true
            }
        } else {
            quadrosCom = 0
            quadrosSem++
            when (fase) {
                Fase.CONFIRMANDO_INICIO -> fase = Fase.SEM_MONSTRO
                Fase.EM_LUTA -> fase = Fase.CONFIRMANDO_FIM
                else -> {}
            }
            if (fase == Fase.CONFIRMANDO_FIM && quadrosSem >= Cal.QUADROS_FIM_LUTA) {
                fase = Fase.SEM_MONSTRO
                terminou = true
            }
        }

        // Log a cada mudança de estado (fase) ou de modo da região
        val mudouFase = fase != faseAntes
        if (mudouFase || l.modo != ultimoModo) {
            val tag = when {
                iniciou -> "BATALHA_INICIO"
                terminou -> "BATALHA_FIM"
                else -> "BATALHA_ESTADO"
            }
            log(
                context, tela,
                "$tag $faseAntes->$fase quadrosCom=$quadrosCom/${Cal.QUADROS_INICIO_LUTA} " +
                    "quadrosSem=$quadrosSem/${Cal.QUADROS_FIM_LUTA} ${l.resumo()}"
            )
            ultimoModo = l.modo
        }
        if (terminou) {
            val dur = (agora - inicioLutaMs).coerceAtLeast(0L)
            inicioLutaMs = 0L
            if (alarmeAtivo) {
                alarmeAtivo = false
                log(context, tela, "ALARME_FIM luta=${dur / 1000L}s ${l.resumo()}")
            }
        }

        // Alarme: só com luta confirmada há >= limite; cai assim que o FIM é confirmado
        val emLuta = fase == Fase.EM_LUTA || fase == Fase.CONFIRMANDO_FIM
        val devido = emLuta && limiteAlarmeMs != null && inicioLutaMs > 0L &&
            (agora - inicioLutaMs) >= limiteAlarmeMs
        if (devido && !alarmeAtivo) {
            alarmeAtivo = true
            log(context, tela, "ALARME_INICIO luta=${(agora - inicioLutaMs) / 1000L}s ${l.resumo()}")
        } else if (!devido && alarmeAtivo) {
            alarmeAtivo = false
            log(context, tela, "ALARME_FIM ${l.resumo()}")
        }

        return Estado(fase, emLuta, inicioLutaMs, iniciou, terminou, devido, l)
    }

    private fun log(context: Context?, tela: Bitmap, msg: String) {
        if (context != null) LogRota.registrarPasso(context, msg, tela)
    }

    private fun ehFundo(r: Int, g: Int, b: Int) =
        r <= Cal.FUNDO_R_MAX && g <= Cal.FUNDO_G_MAX && b in Cal.FUNDO_B_MIN..Cal.FUNDO_B_MAX

    private fun ehHp(r: Int, g: Int, b: Int) =
        (g >= Cal.HP_VERDE_G_MIN && r <= Cal.HP_VERDE_R_MAX && b <= Cal.HP_VERDE_B_MAX) ||
            (r >= Cal.HP_AMARELO_R_MIN && g in Cal.HP_AMARELO_G_MIN..Cal.HP_AMARELO_G_MAX && b <= Cal.HP_AMARELO_B_MAX) ||
            (r >= Cal.HP_VERMELHO_R_MIN && g <= Cal.HP_VERMELHO_G_MAX && b <= Cal.HP_VERMELHO_B_MAX)

    private fun ehDivisor(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return Math.abs(r - Cal.DIVISOR_R) <= Cal.DIVISOR_TOL &&
            Math.abs(g - Cal.DIVISOR_G) <= Cal.DIVISOR_TOL &&
            Math.abs(b - Cal.DIVISOR_B) <= Cal.DIVISOR_TOL
    }

    /** Leitura de um quadro. Sem estado e sem log. */
    fun medir(tela: Bitmap): Leitura {
        val w = tela.width
        val h = tela.height
        val x0 = (w * Cal.BATALHA_X0).toInt().coerceIn(0, w - 2)
        val x1 = (w * Cal.BATALHA_X1).toInt().coerceIn(x0 + 1, w)
        var y0 = (h * Cal.BATALHA_Y0).toInt().coerceIn(0, h - 2)
        val alturaReg = ((h * Cal.BATALHA_Y1).toInt() - (h * Cal.BATALHA_Y0).toInt()).coerceAtLeast(8)
        var modo = "FIXO"

        // 1) Alinha pelo divisor, SEMPRE dentro da faixa x do painel (nunca varre o mapa do jogo)
        if (Cal.BATALHA_USAR_DIVISOR) {
            val bw = x1 - x0
            val buscaY0 = (h * Cal.DIVISOR_BUSCA_Y0).toInt().coerceIn(0, h - 1)
            val buscaY1 = (h * Cal.DIVISOR_BUSCA_Y1).toInt().coerceIn(buscaY0 + 1, h)
            val linha = IntArray(bw)
            var y = buscaY0
            var achouTopo = -1
            while (y < buscaY1) {
                tela.getPixels(linha, 0, bw, x0, y, bw, 1)
                var n = 0
                for (p in linha) if (ehDivisor(p)) n++
                val ok = n.toFloat() / bw >= Cal.DIVISOR_FRAC_MIN
                if (ok && achouTopo < 0) achouTopo = y
                if (!ok && achouTopo >= 0) {
                    y0 = y.coerceIn(0, h - 2)
                    modo = "DIVISOR"
                    break
                }
                y++
            }
        }
        val y1 = (y0 + alturaReg).coerceIn(y0 + 1, h)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 8 || rh < 8) return Leitura(x0, y0, x1, y1, modo, 0, 0, 0, 0, 0f, false)

        val px = IntArray(rw * rh)
        tela.getPixels(px, 0, rw, x0, y0, rw, rh)

        val esc = (w / 2460f).coerceIn(0.4f, 1.5f)
        val sprW = (Cal.SPRITE_LARGURA_PX * esc).toInt().coerceIn(8, 36).coerceAtMost(rw - 1)
        val linhaH = (Cal.LINHA_ALTURA_PX * esc).toInt().coerceIn(8, 36)
        val barraMin = (Cal.BARRA_LARGURA_MIN_PX * esc).toInt().coerceAtLeast(6)
        val hMin = Math.round(Cal.BARRA_ALTURA_MIN_PX * esc).coerceAtLeast(1)
        val hMax = Math.round(Cal.BARRA_ALTURA_MAX_PX * esc).coerceAtLeast(hMin)
        val sep = (Cal.LINHAS_SEPARACAO_MIN_PX * esc).toInt().coerceAtLeast(4)

        // 2) Máscaras
        val hpMask = BooleanArray(rw * rh)
        val destMask = BooleanArray(rw * rh)
        var fundoN = 0
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val f = ehFundo(r, g, b)
            if (f) fundoN++
            else if (r > Cal.SPRITE_R_MIN || g > Cal.SPRITE_G_MIN || b > Cal.SPRITE_B_MIN) destMask[i] = true
            if (ehHp(r, g, b)) hpMask[i] = true
        }
        val fracFundo = fundoN.toFloat() / px.size

        // 3) Se o painel não está ali (fundo azul ausente), não conta nada
        if (fracFundo < Cal.PAINEL_FUNDO_FRAC_MIN) {
            return Leitura(x0, y0, x1, y1, "PAINEL_AUSENTE", 0, 0, 0, 0, fracFundo, false)
        }

        // 4) Maior sequência horizontal de HP por linha de pixels (à direita do sprite)
        val runs = IntArray(rh)
        for (yy in 0 until rh) {
            var cur = 0
            var best = 0
            val base = yy * rw
            for (xx in sprW until rw) {
                if (hpMask[base + xx]) {
                    cur++
                    if (cur > best) best = cur
                } else cur = 0
            }
            runs[yy] = best
        }

        // 5) Faixas verticais contíguas = candidatas a barra; valida espessura, sprite e separação
        var linhas = 0
        var pontuacao = 0
        var somaSprite = 0
        var somaBarra = 0
        var ultimoTopo = Int.MIN_VALUE / 2
        var yy = 0
        while (yy < rh) {
            if (runs[yy] < barraMin) { yy++; continue }
            val s = yy
            while (yy < rh && runs[yy] >= barraMin) yy++
            val e = yy - 1
            val espessura = e - s + 1
            if (espessura < hMin || espessura > hMax) continue
            if (linhas > 0 && s - ultimoTopo < sep) continue

            val ya = (s - linhaH).coerceAtLeast(0)
            val yb = (e + linhaH / 2 + 1).coerceAtMost(rh)
            var spr = 0
            for (y2 in ya until yb) {
                val base = y2 * rw
                for (x2 in 0 until sprW) if (destMask[base + x2]) spr++
            }
            if (spr < Cal.SPRITE_PIXELS_MIN) continue

            var barra = 0
            for (y2 in s..e) {
                val base = y2 * rw
                for (x2 in sprW until rw) if (hpMask[base + x2]) barra++
            }
            linhas++
            somaSprite += spr
            somaBarra += barra
            pontuacao += spr + barra
            ultimoTopo = s
        }

        val presente = linhas >= Cal.BATALHA_LINHAS_MIN && pontuacao >= Cal.BATALHA_PONTUACAO_MIN
        return Leitura(x0, y0, x1, y1, modo, linhas, pontuacao, somaSprite, somaBarra, fracFundo, presente)
    }
}
