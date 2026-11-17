package com.rotabot

import android.content.Context

// Guarda o erro completo quando o app trava, para aparecer na próxima abertura.
object RelatorioErro {
    private const val ARQUIVO = "erro"
    private const val CHAVE = "texto"

    fun instalar(ctx: Context) {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            try {
                val texto = erro.stackTraceToString().take(3500)
                ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
                    .edit().putString(CHAVE, texto).commit()
            } catch (_: Exception) {
            }
            anterior?.uncaughtException(thread, erro)
        }
    }

    fun registrar(ctx: Context, titulo: String, erro: Throwable? = null) {
        try {
            val atual = ler(ctx).orEmpty()
            val novo = buildString {
                append(atual)
                append("\n\n[").append(java.time.LocalDateTime.now()).append("] ").append(titulo)
                erro?.let { append("\n").append(it.stackTraceToString()) }
            }.takeLast(12000)
            ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).edit().putString(CHAVE, novo).commit()
        } catch (_: Exception) {}
    }

    fun ler(ctx: Context): String? =
        ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).getString(CHAVE, null)

    fun limpar(ctx: Context) {
        ctx.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).edit().remove(CHAVE).commit()
    }
}
