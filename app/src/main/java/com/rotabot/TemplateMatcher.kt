package com.rotabot

import android.graphics.Bitmap
import kotlin.math.sqrt

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
