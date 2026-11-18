package com.rotabot

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

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
        const val EXTRA_USA_LOOT = "usa_loot"
        const val EXTRA_USA_ALERTA = "usa_alerta"
        private const val CANAL = "rotabot_rota"
        private const val NOTIFICACAO_ID = 71
        private const val INTERVALO_CICLO_MS = 450L

        private var ringtone: Ringtone? = null

        fun tocarAlarme(ctx: Context) {
            try {
                if (ringtone == null) {
                    val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ringtone = RingtoneManager.getRingtone(ctx, alertUri)
                }
                if (ringtone?.isPlaying == false) {
                    ringtone?.play()
                }
            } catch (_: Exception) {}
        }

        fun pararAlarme() {
            try {
                if (ringtone?.isPlaying == true) {
                    ringtone?.stop()
                }
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    @Volatile private var statusAtual = "Aguardando início"
    private var ultimaChave = ""
    @Volatile private var rodando = false
    @Volatile private var rotaIniciada = false
    @Volatile private var pausaManual = false
    @Volatile private var batalhaDetectada = false
    @Volatile private var processando = false
    @Volatile private var toqueDesde = 0L
    @Volatile private var ultimoToque = ""
    private var inicio = 1
    private var fim = 5
    private var usaLoot = false
    private var usaAlerta = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            pararTudo("A captura de tela foi encerrada pelo Android.")
        }
    }

    private val ciclo = object : Runnable {
        override fun run() {
            if (!rodando) return
            try {
                executarCiclo()
            } catch (e: Exception) {
                RelatorioErro.registrar(this@BotService, "Erro no ciclo", e)
                registrarStatus("Erro: ${e.javaClass.simpleName}")
            }
            if (rodando) workerHandler.postDelayed(this, INTERVALO_CICLO_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("RotaBot-Analise").also { it.start() }
        workerHandler = Handler(workerThread.looper)
        criarCanal()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_INICIAR -> iniciar(intent)
            ACAO_PAUSAR -> { pausaManual = true; atualizarBotao() }
            ACAO_RETOMAR -> { pausaManual = false; atualizarBotao() }
            ACAO_PARAR -> pararTudo("RotaBot encerrado.")
        }
        return START_NOT_STICKY
    }

    private fun iniciar(intent: Intent) {
        iniciarNotificacao()
        if (rodando) return
        if (!Settings.canDrawOverlays(this) || ToqueService.instance == null) {
            pararTudo("Ative sobreposição e acessibilidade antes de iniciar.")
            return
        }
        inicio = intent.getIntExtra(EXTRA_INICIO, 1).coerceIn(1, Cal.TOTAL_ICONES)
        fim = intent.getIntExtra(EXTRA_FIM, 5).coerceIn(inicio, Cal.TOTAL_ICONES)
        usaLoot = intent.getBooleanExtra(EXTRA_USA_LOOT, false)
        usaAlerta = intent.getBooleanExtra(EXTRA_USA_ALERTA, true)

        val resultCode = intent.getIntExtra(EXTRA_RESULTADO, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DADOS, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_DADOS)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            pararTudo("Autorização de captura ausente.")
            return
        }

        liberarCaptura()
        removerOverlay()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, workerHandler)
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "RotaBotScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, workerHandler
            )
            Rota.carregar(this, inicio, fim, maxOf(metrics.widthPixels, metrics.heightPixels))
            statusAtual = "Captura pronta. Toque em Iniciar."
            rodando = false
            rotaIniciada = false
            pausaManual = false
            batalhaDetectada = false
            processando = false
            ultimoToque = ""
            criarOverlay()
        } catch (e: Exception) {
            pararTudo("Falha ao iniciar: ${e.message}")
        }
    }

    private fun ajustarRotacao() {
        val atual = imageReader ?: return
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        if (m.widthPixels == atual.width && m.heightPixels == atual.height) return
        val novo = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(m.widthPixels, m.heightPixels, m.densityDpi)
        virtualDisplay?.surface = novo.surface
        imageReader = novo
        atual.close()
    }

    private fun executarCiclo() {
        if (processando) {
            if (SystemClock.uptimeMillis() - toqueDesde < 3000L) return
            processando = false
        }
        ajustarRotacao()
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val frame: Bitmap? = try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val raw = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            if (rowPixels != image.width) {
                val cortado = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                raw.recycle()
                cortado
            } else raw
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (frame == null) return

        try {
            val resultado = if (pausaManual) "Pausado manualmente" else Rota.passo(frame, this, usaLoot, usaAlerta) { x, y -> tocarNaTela(x, y) }
            val emLuta = resultado.startsWith("LUTA") || resultado.startsWith("ALERTA")
            if (emLuta != batalhaDetectada) {
                batalhaDetectada = emLuta
                atualizarBotao()
            }
            registrarStatus(if (ultimoToque.isEmpty()) resultado else "$resultado | $ultimoToque")
        } finally {
            frame.recycle()
        }
    }

    private fun tocarNaTela(x: Float, y: Float) {
        if (processando) return
        processando = true
        toqueDesde = SystemClock.uptimeMillis()
        val aceito = ToqueService.clicar(x, y) { _, mensagem ->
            ultimoToque = mensagem
            processando = false
        }
        if (!aceito) {
            processando = false
            ultimoToque = "Android recusou o toque"
        }
    }

    private fun registrarStatus(mensagem: String) {
        if (statusAtual == mensagem) return
        statusAtual = mensagem
        val chave = mensagem.replace(Regex("[0-9.,]+"), "#")
        if (chave != ultimaChave) {
            ultimaChave = chave
            RelatorioErro.registrar(this, "Status: $mensagem")
        }
        handler.post { statusView?.text = mensagem }
    }

    private fun criarOverlay() {
        handler.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val painel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                setBackgroundColor(Color.argb(230, 25, 25, 25))
            }
            overlay = painel

            statusView = TextView(this).apply {
                text = statusAtual
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(8, 4, 8, 8)
            }
            painel.addView(statusView)

            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnIniciarPausar = Button(this).apply {
                text = "Iniciar"
                setOnClickListener {
                    if (!rotaIniciada) {
                        Rota.resetar()
                        rotaIniciada = true
                        rodando = true
                        pausaManual = false
                        batalhaDetectada = false
                        text = "Pausar"
                        workerHandler.post(ciclo)
                    } else {
                        pausaManual = !pausaManual
                        text = if (pausaManual) "Retomar" else "Pausar"
                    }
                }
            }
            val btnParar = Button(this).apply {
                text = "Parar"
                setOnClickListener { pararTudo("RotaBot encerrado.") }
            }
            linha.addView(btnIniciarPausar)
            linha.addView(btnParar)
            painel.addView(linha)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 }

            try { wm.addView(painel, params) } catch (_: Exception) {}
        }
    }

    private fun atualizarBotao() {}

    private fun removerOverlay() {
        pararAlarme()
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
            overlay = null
        }
    }

    private fun liberarCaptura() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
    }

    private fun pararTudo(motivo: String) {
        rodando = false
        rotaIniciada = false
        liberarCaptura()
        removerOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun iniciarNotificacao() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CANAL, "RotaBot Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("RotaBot Ativo")
            .setContentText("Cavebot rodando em segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(NOTIFICACAO_ID, notification)
    }

    override fun onDestroy() {
        pararAlarme()
        pararTudo("Serviço destruído.")
        workerThread.quitSafely()
        super.onDestroy()
    }
}
