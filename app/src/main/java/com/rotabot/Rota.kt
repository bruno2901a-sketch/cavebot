package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

data class PinWaypoint(val nome: String, val bitmap: Bitmap)

object Rota {
    private val listaPins = mutableListOf<PinWaypoint>()

    fun carregarPins(context: Context, inicio: Int, fim: Int): List<PinWaypoint> {
        listaPins.clear()
        try {
            for (numero in inicio..fim) {
                val nome = "p%02d.png".format(numero)
                context.assets.open("pins/$nome").use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { listaPins.add(PinWaypoint(nome, it)) }
                }
            }
        } catch (e: Exception) {
            Log.e("RotaBot", "Falha ao carregar marcadores", e)
        }
        return listaPins.toList()
    }

    fun obterPins(): List<PinWaypoint> = listaPins.toList()

    fun limpar() {
        listaPins.forEach { it.bitmap.recycle() }
        listaPins.clear()
    }
}
