package com.rotabot

import android.content.Context
import android.graphics.BitmapFactory

/**
 * Catálogo validado dos marcadores da rota.
 *
 * A rota usa posições sequenciais internamente. Portanto, um intervalo só pode
 * ser iniciado quando TODOS os arquivos pedidos existem e são imagens válidas.
 * Isso impede que um marcador ausente comprima a lista e faça o bot saltar IDs.
 */
internal object CatalogoPinos {
    data class Validacao(val ids: List<Int>, val erro: String? = null) {
        val valido: Boolean get() = erro == null && ids.isNotEmpty()
    }

    fun validar(context: Context, inicio: Int, fim: Int): Validacao {
        if (inicio !in 1..Cal.TOTAL_ICONES || fim !in inicio..Cal.TOTAL_ICONES) {
            return Validacao(emptyList(), "Intervalo de pinos inválido: $inicio..$fim")
        }
        val ids = (inicio..fim).toList()
        for (id in ids) {
            val existe = try {
                context.assets.open("pins/p%02d.png".format(id)).use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    options.outWidth > 0 && options.outHeight > 0
                }
            } catch (_: Exception) {
                false
            }
            if (!existe) {
                return Validacao(emptyList(), "Pino $id ausente ou inválido. Rota não iniciada para evitar pular marcadores.")
            }
        }
        return Validacao(ids)
    }
}
