package com.rotabot

import android.content.Context
import android.graphics.Bitmap

/** Portas da camada de navegação para isolar Android e permitir testes. */
interface Toucher {
    fun tocar(x: Float, y: Float, callback: ((Boolean, String) -> Unit)? = null): Boolean
}

interface ScreenSource {
    fun capturar(): Bitmap?
}

interface AlarmPlayer {
    fun tocar(context: Context, uri: String, volume: Int)
    fun parar()
}

interface Logger {
    fun registrar(context: Context, mensagem: String, quadro: Bitmap? = null)
    fun diagnostico(context: Context, nome: String, quadro: Bitmap)
}

class ProjectionScreenSource(
    private val capturarFrame: () -> Bitmap?
) : ScreenSource {
    override fun capturar(): Bitmap? = capturarFrame()
}

object AccessibilityToucher : Toucher {
    override fun tocar(x: Float, y: Float, callback: ((Boolean, String) -> Unit)?): Boolean =
        ToqueService.clicar(x, y, callback = callback)
}

object ServiceAlarmPlayer : AlarmPlayer {
    override fun tocar(context: Context, uri: String, volume: Int) =
        BotService.tocarAlarme(context, uri, volume)

    override fun parar() = BotService.pararAlarme()
}

object AndroidLogger : Logger {
    override fun registrar(context: Context, mensagem: String, quadro: Bitmap?) =
        LogRota.registrarPasso(context, mensagem, quadro)

    override fun diagnostico(context: Context, nome: String, quadro: Bitmap) =
        LogRota.salvarQuadroDiagnostico(context, nome, quadro)
}
