package com.rotabot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sqrt

class BotService : Service() {
    companion object {
        const val ACAO_INICIAR = "com.rotabot.INICIAR"
        const val ACAO_PAUSAR = "com.rotabot.PAUSAR"
        const val ACAO_RETOMAR = "com.rotabot.RETOMAR"
        const val ACAO_PARAR = "com.rotabot.PARAR"
        const val EXTRA_INICIO = "inicio"
        const val EXTRA_FIM = "fim"
        const val EXTRA_RESULTADO = "resultado"
        const val EXTRA_DADOS = "dados"
        private const val CANAL = "rotabot_rota"
        private const val NOTIFICACAO_ID = 71
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var rodando = false
    private var pausado = false
    private var pausaManual = false
    private var batalhaDetectada = false
    private var processando = false
    private var indicePin = 0
    private var pinAcionado = false
    private var inicio = 1
    private var fim = 5
    private var ultimaAcao = 0L
    private var largura = 0
    private var altura = 0
    private var densidade = 0
    private var ultimoFrame: Bitmap? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            pararTudo("A captura de tela foi encerrada pelo Android.")
        }
    }

    private val ciclo = object : Runnable {
        override fun run() {
            if (!rodando) return
            executarCiclo()
            handler.postDelayed(this, 450L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        criarCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_INICIAR -> iniciar(intent)
            ACAO_PAUSAR -> {
                pausaManual = true
                pausado = true
                atualizarBotao()
            }
            ACAO_RETOMAR -> {
                pausaManual = false
                pausado = batalhaDetectada
                atualizarBotao()
            }
            ACAO_PARAR -> pararTudo("RotaBot encerrado.")
        }
        return START_NOT_STICKY
    }

    private fun iniciar(intent: Intent) {
        if (rodando) return
        if (!Settings.canDrawOverlays(this) || ToqueService.instance == null) {
            pararTudo("Ative sobreposição e acessibilidade antes de iniciar.")
            return
        }
        inicio = intent.getIntExtra(EXTRA_INICIO, 1).coerceIn(1, 5)
        fim = intent.getIntExtra(EXTRA_FIM, 5).coerceIn(inicio, 5)
        val resultCode = intent.getIntExtra(EXTRA_RESULTADO, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DADOS, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DADOS)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            pararTudo("Autorização de captura ausente. Abra o RotaBot e autorize novamente.")
            return
        }

        iniciarNotificacao()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        largura = metrics.widthPixels
        altura = metrics.heightPixels
        densidade = metrics.densityDpi
        if (largura <= 0 || altura <= 0) {
            pararTudo("Não foi possível identificar o tamanho da tela.")
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, handler)
            imageReader = ImageReader.newInstance(largura, altura, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "RotaBotScreenCapture", largura, altura, densidade,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
            if (virtualDisplay == null) {
                pararTudo("Não foi possível iniciar a captura da tela.")
                return
            }
            Rota.carregarPins(this, inicio, fim)
            indicePin = 0
            pinAcionado = false
            rodando = true
            pausado = false
            pausaManual = false
            batalhaDetectada = false
            criarOverlay()
            handler.post(ciclo)
        } catch (e: Exception) {
            android.util.Log.e("RotaBot", "Falha ao iniciar captura", e)
            pararTudo("Falha ao iniciar: ${e.message ?: "erro desconhecido"}")
        }
    }

    private fun executarCiclo() {
        if (processando) return
        val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val frame = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPixels = rowStride / pixelStride
            val raw = Bitmap.createBitmap(rowPixels, altura, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(raw, 0, 0, largura, altura)
            raw.recycle()
            cropped
        } catch (e: Exception) {
            android.util.Log.e("RotaBot", "Erro ao ler frame", e)
            null
        } finally {
            image.close()
        } ?: return

        ultimoFrame?.recycle()
        ultimoFrame = frame
        val battleRect = Rect(
            (largura * 0.045f).toInt(), (altura * 0.11f).toInt(),
            (largura * 0.195f).toInt(), (altura * 0.65f).toInt()
        )
        val battle = frame.safeCrop(battleRect) ?: return
        batalhaDetectada = temIndicadorDeBatalha(battle)
        battle.recycle()
        pausado = pausaManual || batalhaDetectada
        atualizarBotao()
        if (pausado) return

        val pins = Rota.obterPins()
        if (pins.isEmpty()) return
        val mapRect = Rect(
            (largura * 0.80f).toInt(), (altura * 0.05f).toInt(),
            (largura * 0.99f).toInt(), (altura * 0.50f).toInt()
        )
        val minimapa = frame.safeCrop(mapRect) ?: return
        val pin = pins.getOrNull(indicePin) ?: run { minimapa.recycle(); return }
        val pinPos = localizarTemplate(minimapa, pin.bitmap)
        val jogador = localizarCruzBranca(minimapa)
        if (pinPos != null && jogador != null) {
            val distancia = sqrt(
                ((pinPos.first - jogador.first) * (pinPos.first - jogador.first) +
                 (pinPos.second - jogador.second) * (pinPos.second - jogador.second)).toDouble()
            )
            if (distancia <= maxOf(18.0, pin.bitmap.width * 1.25)) {
                indicePin = if (indicePin >= pins.lastIndex) 0 else indicePin + 1
                pinAcionado = false
                ultimaAcao = System.currentTimeMillis()
            } else if (!pinAcionado && System.currentTimeMillis() - ultimaAcao > 1300L) {
                processando = true
                val x = mapRect.left + pinPos.first
                val y = mapRect.top + pinPos.second
                val aceito = ToqueService.clicar(x.toFloat(), y.toFloat()) {
                    processando = false
                    pinAcionado = true
                    ultimaAcao = System.currentTimeMillis()
                }
                if (!aceito) processando = false
            }
        }
        minimapa.recycle()
    }

    private fun temIndicadorDeBatalha(bitmap: Bitmap): Boolean {
        var sinais = 0
        for (y in 0 until bitmap.height step 3) {
            for (x in 0 until bitmap.width step 3) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val vermelho = r > 175 && g < 105 && b < 105
                val verde = g > 170 && r < 105 && b < 120
                if (vermelho || verde) {
                    sinais++
                    if (sinais >= 12) return true
                }
            }
        }
        return false
    }

    private fun localizarCruzBranca(map: Bitmap): Pair<Int, Int>? {
        var bestX = -1
        var bestY = -1
        var bestScore = 0
        val xMin = (map.width * 0.45f).toInt()
        val xMax = (map.width * 0.99f).toInt()
        val yMin = (map.height * 0.05f).toInt()
        val yMax = (map.height * 0.95f).toInt()
        for (y in yMin until yMax step 2) {
            for (x in xMin until xMax step 2) {
                val c = map.getPixel(x, y)
                if (Color.red(c) > 225 && Color.green(c) > 225 && Color.blue(c) > 225) {
                    var score = 0
                    for (d in 2..8 step 2) {
                        if (x-d >= 0 && isWhite(map.getPixel(x-d,y))) score++
                        if (x+d < map.width && isWhite(map.getPixel(x+d,y))) score++
                        if (y-d >= 0 && isWhite(map.getPixel(x,y-d))) score++
                        if (y+d < map.height && isWhite(map.getPixel(x,y+d))) score++
                    }
                    if (score > bestScore) { bestScore = score; bestX = x; bestY = y }
                }
            }
        }
        return if (bestScore >= 4) Pair(bestX, bestY) else null
    }

    private fun isWhite(c: Int) = Color.red(c) > 225 && Color.green(c) > 225 && Color.blue(c) > 225

    private fun localizarTemplate(area: Bitmap, template: Bitmap): Pair<Int, Int>? {
        if (template.width >= area.width || template.height >= area.height) return null
        val step = 2
        for (y in 0..(area.height-template.height) step step) {
            for (x in 0..(area.width-template.width) step step) {
                if (validarTemplate(area, template, x, y)) {
                    return Pair(x + template.width/2, y + template.height/2)
                }
            }
        }
        return null
    }

    private fun validarTemplate(area: Bitmap, template: Bitmap, sx: Int, sy: Int): Boolean {
        var acertos = 0
        var total = 0
        for (y in 0 until template.height step 2) {
            for (x in 0 until template.width step 2) {
                val esperado = template.getPixel(x,y)
                if (Color.alpha(esperado) < 150) continue
                total++
                val atual = area.getPixel(sx+x,sy+y)
                if (abs(Color.red(atual)-Color.red(esperado)) <= 38 &&
                    abs(Color.green(atual)-Color.green(esperado)) <= 38 &&
                    abs(Color.blue(atual)-Color.blue(esperado)) <= 38) acertos++
            }
        }
        return total > 0 && acertos.toFloat()/total >= 0.78f
    }

    private fun Bitmap.safeCrop(rect: Rect): Bitmap? {
        val l = rect.left.coerceIn(0, width-1)
        val t = rect.top.coerceIn(0, height-1)
        val r = rect.right.coerceIn(l+1, width)
        val b = rect.bottom.coerceIn(t+1, height)
        return Bitmap.createBitmap(this, l, t, r-l, b-t)
    }

    private fun criarOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            setBackgroundColor(Color.argb(220, 25, 25, 25))
        }
        val toggle = Button(this).apply {
            text = "Pausar"
            setOnClickListener {
                pausaManual = !pausaManual
                pausado = pausaManual || batalhaDetectada
                text = if (pausaManual) "Retomar" else if (batalhaDetectada) "Em batalha" else "Pausar"
            }
        }
        val stop = Button(this).apply {
            text = "Parar"
            setOnClickListener { pararTudo("RotaBot encerrado.") }
        }
        painel.addView(toggle)
        painel.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 160
        }
        try {
            wm.addView(painel, params)
            overlay = painel
        } catch (e: Exception) {
            android.util.Log.e("RotaBot", "Não foi possível exibir controles", e)
        }
    }

    private fun atualizarBotao() {
        val painel = overlay ?: return
        val button = painel.getChildAt(0) as? Button ?: return
        button.text = if (pausaManual) "Retomar" else if (batalhaDetectada) "Em batalha" else "Pausar"
    }

    private fun iniciarNotificacao() {
        val notification: Notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("RotaBot em execução")
            .setContentText("Captura de tela e rota ativas")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICACAO_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICACAO_ID, notification)
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CANAL, "RotaBot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun pararTudo(mensagem: String) {
        rodando = false
        pausado = false
        pausaManual = false
        batalhaDetectada = false
        handler.removeCallbacks(ciclo)
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            overlay?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        overlay = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        ultimoFrame?.recycle()
        ultimoFrame = null
        Rota.limpar()
        if (mensagem.isNotBlank()) Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        rodando = false
        handler.removeCallbacks(ciclo)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
