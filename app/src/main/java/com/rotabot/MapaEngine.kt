package com.rotabot

import android.graphics.Bitmap
import kotlin.math.sqrt

object Cal {
    const val LADO_ICONE = 26
    // ---- Pinos: template (NCC em cinza). Referência 2460 px; Redmi 12 = 2460x1080 => escala 1.0 ----
    const val LIMIAR = 0.90f
    // Verde (pino ativo): era 0.78 / 0.03. Print real: alvo 0.95, rival 0.85.
    const val LIMIAR_VERDE = 0.85f
    const val MARGEM_VERDE = 0.05f
    // Antes literais em Rota.kt
    const val LIMIAR_FALLBACK_CONSISTENTE = 0.90f   // era 0.90 (fallback "quase limiar" por 5 quadros)
    const val LIMIAR_CANDIDATO_VERDE = 0.45f        // era 0.45 (candidato fraco + verde proximo)
    const val LIMIAR_CANDIDATO_CONSISTENTE = 0.48f  // era 0.48 (candidato estavel entre quadros)
    const val FATOR_LARANJA_ROTA = 0.45f            // era 0.45 (atenua a linha laranja da rota no cinza)
    // Margem sobre os OUTROS pinos (NCC em COR, máscara circular) no ponto do candidato
    const val MARGEM_PINO = 0.05f
    const val PINO_RIVAL_JANELA_PX = 2              // desvio (px) testado ao comparar com os outros modelos
    const val PINO_MASCARA_FRAC = 0.85f             // diâmetro da máscara circular / lado do ícone
    // Piso do NCC em COR do próprio alvo. Print real: pino verdadeiro = 1.00; falso pino com cinza 0.95 = 0.27.
    // Sem este piso, um ponto qualquer com cinza alto e cor baixa passava só por ter margem sobre rivais igualmente ruins.
    const val LIMIAR_COR_ALVO = 0.80f
    // Quantos candidatos acima do LIMIAR (cinza) são validados em cor por quadro, do mais forte ao mais fraco
    const val MAX_CANDIDATOS_PINO = 4
    // Fração mínima de pixels do modelo que precisa estar visível (fora da cruz) para pontuar
    const val COBERTURA_MIN_PINO = 0.40f
    // Quadros seguidos com candidato estável perto do limiar antes de clicar pelo fallback
    const val QUADROS_FALLBACK_CONSISTENTE = 5
    // Pino verde ativo: componente conexo (print real: 323 px, 22x27, preenchimento 0.54; grama: >=800 px)
    const val VERDE_AREA_MIN = 230
    const val VERDE_AREA_MAX = 520
    const val VERDE_AREA_REF = 323
    const val VERDE_AREA_RASTREIO_MIN = 120         // mínimo ao seguir o verde (cruz pode cobrir parte)
    const val VERDE_LARG_MIN = 16
    const val VERDE_LARG_MAX = 34
    const val VERDE_ALT_MIN = 20
    const val VERDE_ALT_MAX = 40
    const val VERDE_PREENCHIMENTO_MIN = 0.40f
    const val VERDE_PREENCHIMENTO_RASTREIO_MIN = 0.30f
    const val TOLERANCIA_CHEGADA_CASAS = 1.5f
    const val PERMITIR_PULO = false
    const val TOTAL_ICONES = 20
    const val MINIMAPA_X0 = 0.848f
    const val MINIMAPA_Y0 = 0.135f
    const val MINIMAPA_X1 = 0.996f
    const val MINIMAPA_Y1 = 0.615f
    // ---- Lista de batalha (frações da tela; ref. 2460x1080) ----
    // Interior da lista, sem a barra de rolagem (print: painel x=104..456, rolagem 456..486)
    const val BATALHA_X0 = 0.045f
    const val BATALHA_X1 = 0.181f
    // Região FIXA (só usada se o divisor não for achado). Altura = Y1-Y0 também vale para o modo DIVISOR.
    const val BATALHA_Y0 = 0.203f
    const val BATALHA_Y1 = 0.392f
    // Divisor cinza (2px) logo abaixo dos ícones de filtro: y=224 no print A, y=208 no print B
    const val BATALHA_USAR_DIVISOR = true
    const val DIVISOR_BUSCA_Y0 = 0.12f
    const val DIVISOR_BUSCA_Y1 = 0.30f
    const val DIVISOR_R = 57
    const val DIVISOR_G = 56
    const val DIVISOR_B = 54
    const val DIVISOR_TOL = 14
    const val DIVISOR_FRAC_MIN = 0.60f
    // Fundo azul-marinho do painel (print: rgb 11,16,35)
    const val FUNDO_R_MAX = 32
    const val FUNDO_G_MAX = 42
    const val FUNDO_B_MIN = 18
    const val FUNDO_B_MAX = 75
    // Fração mínima de pixels de fundo na região para aceitar que o painel está ali
    const val PAINEL_FUNDO_FRAC_MIN = 0.70f
    // Sprite do monstro (pixel "aceso" = não-fundo e acima destes limites)
    const val SPRITE_R_MIN = 95
    const val SPRITE_G_MIN = 105
    const val SPRITE_B_MIN = 125
    const val SPRITE_LARGURA_PX = 22      // MEDIR (ref. 2460 px)
    const val SPRITE_PIXELS_MIN = 12
    const val LINHA_ALTURA_PX = 20        // MEDIR (ref. 2460 px)
    // Barra de vida (cada cor vira "pixel de HP")
    const val HP_VERDE_G_MIN = 155
    const val HP_VERDE_R_MAX = 85
    const val HP_VERDE_B_MAX = 90
    const val HP_AMARELO_R_MIN = 165
    const val HP_AMARELO_G_MIN = 70
    const val HP_AMARELO_G_MAX = 225
    const val HP_AMARELO_B_MAX = 85
    const val HP_VERMELHO_R_MIN = 155
    const val HP_VERMELHO_G_MAX = 75
    const val HP_VERMELHO_B_MAX = 75
    const val BARRA_LARGURA_MIN_PX = 14   // MEDIR (ref. 2460 px)
    const val BARRA_ALTURA_MIN_PX = 2
    const val BARRA_ALTURA_MAX_PX = 5
    const val LINHAS_SEPARACAO_MIN_PX = 20
    // Decisão
    const val BATALHA_LINHAS_MIN = 1
    const val BATALHA_PONTUACAO_MIN = 40  // MEDIR: ~metade da pontuação de 1 monstro real
    const val QUADROS_INICIO_LUTA = 3     // N quadros SEGUIDOS com monstro
    const val QUADROS_FIM_LUTA = 4        // M quadros SEGUIDOS sem monstro
    const val SEM_ROTA_X0 = 0.485f
    const val SEM_ROTA_Y0 = 0.750f
    const val SEM_ROTA_X1 = 0.555f
    const val SEM_ROTA_Y1 = 0.785f
    const val MAX_FALHAS_PINO = 3
    // Luta mais curta que isto não dispara autoloot (única fonte; usada por LootEngine.ignorarLutaCurta)
    const val DURACAO_MINIMA_LUTA_MS = 2000L
    const val IGNORAR_SEM_ROTA_MS = 2500L
    const val NAO_VISTO_LIMITE = 24
    const val PIXELS_POR_CASA_PADRAO = 16.0f
    const val TAMANHO_CASA_PX_PADRAO = 88.0f
    const val LIMIAR_MINIMAPA_PARADO_PADRAO = 3.2f
    const val REF_BARRA_TITULO_X_PADRAO = 0.620f
    const val REF_BARRA_TITULO_Y_PADRAO = 0.145f
    const val TEMPO_MINIMO_PARADO_CHEGADA_MS = 500L
    const val TEMPO_PARADO_RECLIQUE_MS = 1500L
    const val TEMPO_POR_CASA_MS = 700L
    const val FATOR_TEMPO_MAX_PINO = 3L
    const val FOLGA_TEMPO_MAX_PINO_MS = 4000L
    const val PISO_ANIMACAO_MAX_PX = 28.0
    const val JANELA_ABERTURA_LOOT_MS = 1500L
    const val TEMPO_MAX_POR_CORPO_MS = 6000L
    const val MAX_TENTATIVAS_ITEM_LOOT = 2
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
    val mascaraPino: BooleanArray
    val rgbZ: DoubleArray
    val normaRgb: Double

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

        // Máscara circular + vetor RGB centrado (usado só para decidir ambiguidade entre pinos)
        val circ = BooleanArray(total)
        val centro = (w - 1) / 2.0
        val raio = w * Cal.PINO_MASCARA_FRAC / 2.0
        var somaCanais = 0.0
        var nCanais = 0
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val i = yy * w + xx
                val dx = xx - centro
                val dy = yy - ((h - 1) / 2.0)
                if (mask[i] && dx * dx + dy * dy <= raio * raio) {
                    circ[i] = true
                    val p = px[i]
                    somaCanais += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
                    nCanais += 3
                }
            }
        }
        val mediaCanais = if (nCanais > 0) somaCanais / nCanais else 0.0
        val rgb = DoubleArray(total * 3)
        var somaSqRgb = 0.0
        for (i in 0 until total) {
            if (!circ[i]) continue
            val p = px[i]
            val vr = ((p shr 16) and 0xFF) - mediaCanais
            val vg = ((p shr 8) and 0xFF) - mediaCanais
            val vb = (p and 0xFF) - mediaCanais
            rgb[i * 3] = vr
            rgb[i * 3 + 1] = vg
            rgb[i * 3 + 2] = vb
            somaSqRgb += vr * vr + vg * vg + vb * vb
        }
        mascaraPino = circ
        rgbZ = rgb
        normaRgb = sqrt(somaSqRgb)
    }
}
