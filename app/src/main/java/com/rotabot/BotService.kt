package com.rotabot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class BotService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var rodando = false
    private var indicePinAtual = 0

    // Coordenadas estritas baseadas na sua print (Batalha à esquerda, Minimapa à direita)
    private val battleLeftPct = 0.02f
    private val battleTopPct = 0.05f
    private val battleRightPct = 0.25f
    private val battleBottomPct = 0.45f

    private val minimapLeftPct = 0.80f
    private val minimapTopPct = 0.05f
    private val minimapRightPct = 0.99f
    private val minimapBottomPct = 0.50f

    companion object {
        var ultimoFrameCapturado: Bitmap? = null
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!rodando) return
            executarCiclo()
            handler.postDelayed(this, 350L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacao()
        Rota.carregarTodosOsPins(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!rodando) {
            rodando = true
            indicePinAtual = 0
            handler.post(loopRunnable)
            Log.i("BotService", "Cavebot iniciado.")
        }
        return START_STICKY
    }

    private fun executarCiclo() {
        if (ToqueService.instance == null) return
        val telaAtual: Bitmap = ultimoFrameCapturado ?: return
        val largura = telaAtual.width
        val altura = telaAtual.height

        val rectBatalha = Rect(
            (largura * battleLeftPct).toInt(),
            (altura * battleTopPct).toInt(),
            (largura * battleRightPct).toInt(),
            (altura * battleBottomPct).toInt()
        )
        val recorteBatalha = recortarSeguro(telaAtual, rectBatalha)

        if (recorteBatalha != null && temMonstroNaBatalha(recorteBatalha)) {
            ToqueService.clicar(rectBatalha.centerX().toFloat(), (rectBatalha.top + 45).toFloat())
            return
        }

        val pins = Rota.obterPins()
        if (pins.isEmpty()) return
        if (indicePinAtual >= pins.size) indicePinAtual = 0

        val pinAlvo = pins[indicePinAtual]
        val rectMinimapa = Rect(
            (largura * minimapLeftPct).toInt(),
            (altura * minimapTopPct).toInt(),
            (largura * minimapRightPct).toInt(),
            (altura * minimapBottomPct).toInt()
        )

        val recorteMinimapa = recortarSeguro(telaAtual, rectMinimapa)
        if (recorteMinimapa != null) {
            val pontoEncontrado = encontrarImagemNaArea(recorteMinimapa, pinAlvo.bitmap)
            if (pontoEncontrado != null) {
                val cliqueRealX = rectMinimapa.left + pontoEncontrado.first + (pinAlvo.bitmap.width / 2f)
                val cliqueRealY = rectMinimapa.top + pontoEncontrado.second + (pinAlvo.bitmap.height / 2f)
                ToqueService.clicar(cliqueRealX, cliqueRealY)
                indicePinAtual = (indicePinAtual + 1) % pins.size
            } else {
                indicePinAtual = (indicePinAtual + 1) % pins.size
            }
        }
    }

    private fun recortarSeguro(origem: Bitmap, area: Rect): Bitmap? {
        val left = area.left.coerceIn(0, origem.width - 1)
        val top = area.top.coerceIn(0, origem.height - 1)
        val width = (area.right - left).coerceIn(1, origem.width - left)
        val height = (area.bottom - top).coerceIn(1, origem.height - top)
        return Bitmap.createBitmap(origem, left, top, width, height)
    }

    private fun temMonstroNaBatalha(painelBatalha: Bitmap): Boolean {
        var pixelsBarraHp = 0
        for (y in 0 until painelBatalha.height step 3) {
            for (x in 0 until painelBatalha.width step 3) {
                val pixel = painelBatalha.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if ((g > 180 && r < 80 && b < 80) || (r > 180 && g < 70 && b < 70)) {
                    pixelsBarraHp++
                    if (pixelsBarraHp >= 8) return true
                }
            }
        }
        return false
    }

    private fun encontrarImagemNaArea(areaBusca: Bitmap, template: Bitmap): Pair<Int, Int>? {
        val maxX = areaBusca.width - template.width
        val maxY = areaBusca.height - template.height
        if (maxX <= 0 || maxY <= 0) return null
        val centroX = template.width / 2
        val centroY = template.height / 2
        val corAlvoCentro = template.getPixel(centroX, centroY)

        for (y in 0..maxY) {
            for (x in 0..maxX) {
                if (coresProximas(areaBusca.getPixel(x + centroX, y + centroY), corAlvoCentro, 25)) {
                    if (validarBlocoCompleto(areaBusca, template, x, y, 30)) return Pair(x, y)
                }
            }
        }
        return null
    }

    private fun validarBlocoCompleto(area: Bitmap, template: Bitmap, startX: Int, startY: Int, tolerancia: Int): Boolean {
        var acertos = 0
        var totalAmostras = 0
        for (ty in 0 until template.height step 2) {
            for (tx in 0 until template.width step 2) {
                val pixelTemplate = template.getPixel(tx, ty)
                if (Color.alpha(pixelTemplate) < 128) continue
                totalAmostras++
                if (coresProximas(area.getPixel(startX + tx, startY + ty), pixelTemplate, tolerancia)) acertos++
            }
        }
        return totalAmostras > 0 && (acertos.toFloat() / totalAmostras) >= 0.75f
    }

    private fun coresProximas(c1: Int, c2: Int, tol: Int): Boolean {
        return kotlin.math.abs(Color.red(c1) - Color.red(c2)) <= tol &&
               kotlin.math.abs(Color.green(c1) - Color.green(c2)) <= tol &&
               kotlin.math.abs(Color.blue(c1) - Color.blue(c2)) <= tol
    }

    private fun iniciarNotificacao() {
        val channelId = "cavebot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Cavebot Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cavebot Ativo")
            .setContentText("Monitorando rota e batalha")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        rodando = false
        handler.removeCallbacks(loopRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
