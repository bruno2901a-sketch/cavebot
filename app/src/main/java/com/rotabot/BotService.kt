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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
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
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        private const val CANAL = "rotabot_rota"
        private const val NOTIFICACAO_ID = 71
        private const val INTERVALO_CICLO_MS = 450L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private var botaoIniciar: Button? = null
    private var botaoPausa: Button? = null
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_INICIAR -> iniciar(intent)
            ACAO_PAUSAR -> {
                pausaManual = true
                atualizarBotao()
            }
            ACAO_RETOMAR -> {
                pausaManual = false
                atualizarBotao()
            }
            ACAO_PARAR -> pararTudo("RotaBot encerrado.")
        }
        return START_NOT_STICKY
    }

    private fun iniciar(intent: Intent) {
        // O Android exige startForeground logo depois de startForegroundService,
        // mesmo que o início falhe logo em seguida. Por isso vem primeiro.
        iniciarNotificacao()
        if (rodando) return
        if (!Settings.canDrawOverlays(this) || ToqueService.instance == null) {
            pararTudo("Ative sobreposição e acessibilidade antes de iniciar.")
            return
        }
        inicio = intent.getIntExtra(EXTRA_INICIO, 1).coerceIn(1, Cal.TOTAL_ICONES)
        fim = intent.getIntExtra(EXTRA_FIM, 5).coerceIn(inicio, Cal.TOTAL_ICONES)
        val resultCode = intent.getIntExtra(EXTRA_RESULTADO, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DADOS, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_DADOS)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            pararTudo("Autorização de captura ausente. Abra o RotaBot e autorize novamente.")
            return
        }

        // Se já havia uma captura ou controles de uma execução anterior, libera antes.
        liberarCaptura()
        removerOverlay()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            pararTudo("Não foi possível identificar o tamanho da tela.")
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            // Android 14+ exige registrar o callback antes de criar a captura.
            projection?.registerCallback(projectionCallback, workerHandler)
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "RotaBotScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, workerHandler
            )
            if (virtualDisplay == null) {
                pararTudo("Não foi possível iniciar a captura da tela.")
                return
            }
            // Os ícones são ajustados pela largura da tela deitada (o lado maior),
            // mesmo que o app tenha sido aberto com o celular em pé.
            Rota.carregar(this, inicio, fim, maxOf(metrics.widthPixels, metrics.heightPixels))
            if (Rota.quantidade() == 0) {
                pararTudo("Não encontrei os arquivos dos marcadores selecionados.")
                return
            }
            statusAtual = "Captura pronta. Toque em Iniciar."
            rodando = false
            rotaIniciada = false
            pausaManual = false
            batalhaDetectada = false
            processando = false
            ultimoToque = ""
            criarOverlay()
        } catch (e: Exception) {
            RelatorioErro.registrar(this, "Falha ao iniciar captura", e)
            android.util.Log.e("RotaBot", "Falha ao iniciar captura", e)
            pararTudo("Falha ao iniciar: ${e.message ?: "erro desconhecido"}")
        }
    }

    /** Se o celular girou, recria o leitor com o tamanho novo da tela. */
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
            // se o Android nunca avisar o fim do toque, não trava para sempre
            if (SystemClock.uptimeMillis() - toqueDesde < 3000L) return
            processando = false
        }
        ajustarRotacao()
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            registrarStatus("Falha na captura: ${e.message}")
            null
        }
        if (image == null) {
            registrarStatus("Aguardando imagem da tela...")
            return
        }
        val frame: Bitmap? = try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val raw = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            if (rowPixels != image.width) {
                // tira a sobra de bytes no fim de cada linha
                val cortado = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
                raw.recycle()
                cortado
            } else {
                raw
            }
        } catch (e: Exception) {
            RelatorioErro.registrar(this, "Erro ao ler frame", e)
            null
        } finally {
            image.close()
        }
        if (frame == null) return

        try {
            val resultado = if (pausaManual) {
                "Pausado manualmente"
            } else {
                Rota.passo(frame) { x, y -> tocarNaTela(x, y) }
            }
            val emLuta = resultado.startsWith("LUTA")
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
            ultimoToque = "Android recusou o toque (acessibilidade desconectada?)"
        }
    }

    private fun registrarStatus(mensagem: String) {
        if (statusAtual == mensagem) return
        statusAtual = mensagem
        // No relatório entra só quando o tipo de estado muda (sem os números),
        // para não encher de linhas parecidas a cada leitura.
        val chave = mensagem.replace(Regex("[0-9.,]+"), "#")
        if (chave != ultimaChave) {
            ultimaChave = chave
            RelatorioErro.registrar(this, "Status: $mensagem")
        }
        handler.post { statusView?.text = mensagem }
    }

    private fun criarOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.argb(220, 25, 25, 25))
        }
        val status = TextView(this).apply {
            text = statusAtual
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(8, 4, 8, 8)
            maxWidth = 640
        }
        statusView = status
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply {
            text = "Iniciar"
            setOnClickListener {
                if (!rotaIniciada) {
                    Rota.resetar()
                    rotaIniciada = true
                    rodando = true
                    pausaManual = false
                    batalhaDetectada = false
                    atualizarBotao()
                    workerHandler.post(ciclo)
                }
            }
        }
        val toggle = Button(this).apply {
            text = "Pausar"
            isEnabled = false
            setOnClickListener {
                pausaManual = !pausaManual
                atualizarBotao()
            }
        }
        val stop = Button(this).apply {
            text = "Parar"
            setOnClickListener { pararTudo("RotaBot encerrado.") }
        }
        linha.addView(start)
        linha.addView(toggle)
        linha.addView(stop)
        painel.addView(status)
        painel.addView(linha)
        botaoIniciar = start
        botaoPausa = toggle

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Centralizado no alto da tela, longe do minimapa (direita) e da lista de
            // batalha (esquerda): a captura enxerga os controles e eles não podem cobrir
            // essas áreas.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 12
        }
        var toqueX = 0f
        var toqueY = 0f
        var inicioX = 0
        var inicioY = 0
        painel.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    toqueX = event.rawX
                    toqueY = event.rawY
                    inicioX = params.x
                    inicioY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (inicioX + event.rawX - toqueX).toInt()
                    params.y = (inicioY + event.rawY - toqueY).toInt().coerceAtLeast(0)
                    try {
                        wm.updateViewLayout(painel, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(painel, params)
            overlay = painel
        } catch (e: Exception) {
            RelatorioErro.registrar(this, "Falha ao exibir controles flutuantes", e)
            android.util.Log.e("RotaBot", "Não foi possível exibir controles", e)
        }
    }

    private fun atualizarBotao() {
        handler.post {
            botaoIniciar?.isEnabled = !rotaIniciada
            botaoPausa?.isEnabled = rotaIniciada
            botaoPausa?.text = if (pausaManual) "Retomar" else if (batalhaDetectada) "Em luta" else "Pausar"
        }
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
        } else {
            startForeground(NOTIFICACAO_ID, notification)
        }
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CANAL, "RotaBot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun removerOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            overlay?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
        statusView = null
        botaoIniciar = null
        botaoPausa = null
    }

    private fun liberarCaptura() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
    }

    private fun pararTudo(mensagem: String) {
        // Mexer em janelas e no serviço só pode ser na thread principal.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { pararTudo(mensagem) }
            return
        }
        rodando = false
        rotaIniciada = false
        pausaManual = false
        batalhaDetectada = false
        workerHandler.removeCallbacks(ciclo)
        removerOverlay()
        liberarCaptura()
        Rota.limpar()
        if (mensagem.isNotBlank()) Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        rodando = false
        workerHandler.removeCallbacks(ciclo)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
