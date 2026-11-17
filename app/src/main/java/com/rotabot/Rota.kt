package com.rotabot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

data class PinWaypoint(
    val nome: String,
    val bitmap: Bitmap
)

object Rota {
    private val listaPins = mutableListOf<PinWaypoint>()

    fun carregarTodosOsPins(context: Context): List<PinWaypoint> {
        listaPins.clear()
        val assetManager = context.assets
        try {
            val arquivos = assetManager.list("pins")
                ?.filter { it.endsWith(".png", ignoreCase = true) }
                ?.sorted() ?: emptyList()

            for (nomeArquivo in arquivos) {
                assetManager.open("pins/$nomeArquivo").use { inputStream ->
                    val bmp = BitmapFactory.decodeStream(inputStream)
                    if (bmp != null) {
                        listaPins.add(PinWaypoint(nomeArquivo, bmp))
                        Log.d("RotaBot", "Pin carregado: $nomeArquivo")
                    }
                }
            }
            Log.i("RotaBot", "Total de pins carregados: ${listaPins.size}")
        } catch (e: Exception) {
            Log.e("RotaBot", "Erro ao carregar pins: ${e.message}")
        }
        return listaPins
    }

    fun obterPins(): List<PinWaypoint> = listaPins
}
