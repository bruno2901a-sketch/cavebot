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
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

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
        const val EXTRA_SO_ALARME = "so_alarme"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_URI_SOM = "uri_som"
        const val EXTRA_ALARME_S = "alarme_s"
        const val EXTRA_LOOT_MS = "loot_ms"
        const val EXTRA_LOOT_MAX_S = "loot_max_s"
        const val EXTRA_PINO_MS = "pino_ms"
        const val EXTRA_PIXELS_POR_CASA = "pixels_por_casa"
        const val EXTRA_TAMANHO_CASA_PX = "tamanho_casa_px"
        const val EXTRA_LIMIAR_MINIMAPA_PARADO = "limiar_minimapa_parado"
        const val EXTRA_REF_BARRA_TITULO_X = "ref_barra_titulo_x"
        const val EXTRA_REF_BARRA_TITULO_Y = "ref_barra_titulo_y"

        private const val CANAL = "rotabot_rota"
        private const val NOTIFICACAO_ID = 71
        private const val INTERVALO_CICLO_MS = 450L

        private var mediaPlayer: MediaPlayer? = null

        fun tocarAlarme(ctx: Context, uriStr: String, volumeProgresso: Int) {
            try {
                if (mediaPlayer == null) {
                    val uri = if (uriStr.isNotEmpty() && uriStr != "alarme_pino") Uri.parse(uriStr) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(ctx, uri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        prepare()
                    }
                }
                val vol = (volumeProgresso.coerceIn(0, 100) / 100f)
                mediaPlayer?.setVolume(vol, vol)

                if (mediaPlayer?.isPlaying == false) {
                    mediaPlayer?.start()
                }
            } catch (_: Exception) {}
        }

        fun pararAlarme() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
                mediaPlayer = null
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private val capturaLock = Any()
    private val encerramentoEmAndamento = AtomicBoolean(false)
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private var contadorView: TextView? = null
    private var controleRotaView: Button? = null
    @Volatile private var alarmeSegAtual = 30
    @Volatile private var statusAtual = "Aguardando início"
    @Volatile private var rodando = false
    @Volatile private var rotaIniciada = false
    @Volatile private var pausaManual = false
    @Volatile private var processando = false
    @Volatile private var toqueDesde = 0L
    @Volatile private var ultimoToque = ""
    @Volatile private var ultimoFrameEm = 0L
    @Volatile private var ultimaRecriacaoCapturaEm = 0L
    private var ultimoFrameLog: Bitmap? = null
    private var inicio = 1
    private var fim = 5
    private var usaLoot = false
    private var usaAlerta = true
    private var soAlarme = false
    private var volumeAlerta = 80
    private var uriSomAlerta = ""

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { handler.post { pararTudo("Captura encerrada.") } }
    }

    private val ciclo = object : Runnable {
        override fun run() {
            if (!rodando) return
            try { executarCiclo() } catch (e: Exception) { registrarStatus("Erro: ${e.javaClass.simpleName}") }
            if (rodando) workerHandler.postDelayed(this, INTERVALO_CICLO_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogRota.init(this)
        workerThread = HandlerThread("RotaBot-Analise").also { it.start() }
        workerHandler = Handler(workerThread.looper)
        criarCanal()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_INICIAR -> iniciar(intent)
            ACAO_PAUSAR -> {
                Rota.iniciarPausaManual()
                pausaManual = true
                EstadoBotController.definir(EstadoBot.PAUSADO)
                Rota.cancelarAutoloot = true
            }
            ACAO_RETOMAR -> {
                Rota.compensarPausaManual()
                Rota.cancelarAutoloot = false
                pausaManual = false
                EstadoBotController.definir(if (rodando) EstadoBot.NAVEGANDO else EstadoBot.PRONTO)
                Rota.retomarPausaPinoPerdido()
                BotService.pararAlarme()
            }
            ACAO_PARAR -> {
                Rota.cancelarAutoloot = true
                pararTudo("Encerrado.")
            }
        }
        return START_NOT_STICKY
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CANAL, "RotaBot Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun copiarImagemAtual(): Bitmap? = synchronized(capturaLock) {
        val img = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null } ?: return@synchronized null
        try {
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val raw = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(plane.buffer)
            if (rowPixels != img.width) {
                val cortado = Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
                raw.recycle()
                cortado
            } else raw
        } catch (_: Exception) { null } finally { img.close() }
    }

    private fun capturarQuadroSobDemanda(): Bitmap? = copiarImagemAtual()

    private fun iniciar(intent: Intent) {
        if (rodando) return
        encerramentoEmAndamento.set(false)
        EstadoBotController.definir(EstadoBot.PREPARANDO)
        iniciarNotificacao()
        if (rodando) return
        inicio = intent.getIntExtra(EXTRA_INICIO, 1).coerceIn(1, Cal.TOTAL_ICONES)
        fim = intent.getIntExtra(EXTRA_FIM, 5).coerceIn(inicio, Cal.TOTAL_ICONES)
        usaLoot = intent.getBooleanExtra(EXTRA_USA_LOOT, false)
        usaAlerta = intent.getBooleanExtra(EXTRA_USA_ALERTA, true)
        soAlarme = intent.getBooleanExtra(EXTRA_SO_ALARME, false)
        volumeAlerta = intent.getIntExtra(EXTRA_VOLUME, 80)
        uriSomAlerta = intent.getStringExtra(EXTRA_URI_SOM) ?: ""

        val alarmeS = intent.getIntExtra(EXTRA_ALARME_S, 30).coerceIn(5, 300)
        val lootMs = intent.getIntExtra(EXTRA_LOOT_MS, 100).coerceIn(30, 500)
        val lootMaxS = intent.getIntExtra(EXTRA_LOOT_MAX_S, 40).coerceIn(5, 120)
        val pinoMs = intent.getIntExtra(EXTRA_PINO_MS, 700).coerceIn(300, 2000)
        val pxPorCasa = intent.getFloatExtra(EXTRA_PIXELS_POR_CASA, Cal.PIXELS_POR_CASA_PADRAO).coerceIn(4f, 200f)
        val tamCasaPx = intent.getFloatExtra(EXTRA_TAMANHO_CASA_PX, Cal.TAMANHO_CASA_PX_PADRAO).coerceIn(10f, 300f)
        val limiarParado = intent.getFloatExtra(EXTRA_LIMIAR_MINIMAPA_PARADO, Cal.LIMIAR_MINIMAPA_PARADO_PADRAO).coerceIn(0.1f, 100f)
        val refBarraX = intent.getFloatExtra(EXTRA_REF_BARRA_TITULO_X, Cal.REF_BARRA_TITULO_X_PADRAO).coerceIn(0f, 1f)
        val refBarraY = intent.getFloatExtra(EXTRA_REF_BARRA_TITULO_Y, Cal.REF_BARRA_TITULO_Y_PADRAO).coerceIn(0f, 1f)

        alarmeSegAtual = alarmeS
        Rota.limiteAlarmeMs = alarmeS * 1000L
        Rota.intervaloLootMs = lootMs.toLong()
        Rota.maxAutolootMs = lootMaxS * 1000L
        Rota.intervaloCliqueMs = pinoMs.toLong()
        Rota.pixelsPorCasa = pxPorCasa
        Rota.tamanhoCasaPx = tamCasaPx
        Rota.limiarMinimapaParado = limiarParado
        Rota.refBarraTituloX = refBarraX
        Rota.refBarraTituloY = refBarraY
        Rota.cancelarAutoloot = false
        Rota.capturadorQuadroLoot = { capturarQuadroSobDemanda() }

        val resultCode = intent.getIntExtra(EXTRA_RESULTADO, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DADOS, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_DADOS)
        }
        if (resultCode != Activity.RESULT_OK || data == null) { EstadoBotController.definir(EstadoBot.ERRO); pararTudo("Erro auth"); return }

        liberarCaptura()
        removerOverlay()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(projectionCallback, workerHandler)
        synchronized(capturaLock) {
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "RotaBotScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, workerHandler
            )
        }
        try {
            Rota.carregar(this, inicio, fim, maxOf(metrics.widthPixels, metrics.heightPixels))
        } catch (e: Exception) {
            liberarCaptura()
            EstadoBotController.definir(EstadoBot.ERRO)
            pararTudo("Falha ao carregar mapa: ${e.message ?: "erro desconhecido"}")
            return
        }
        rodando = false
        rotaIniciada = false
        pausaManual = false
        processando = false
        ultimoToque = ""
        ultimoFrameEm = 0L
        ultimaRecriacaoCapturaEm = 0L
        EstadoBotController.definir(EstadoBot.PRONTO)
        criarOverlay()
    }

    private fun registrarLogCaptura(mensagem: String) {
        val bmp = ultimoFrameLog ?: return
        if (bmp.isRecycled) return
        try {
            LogRota.registrarPasso(this, mensagem, bmp)
        } catch (_: Exception) {}
    }

    private fun recriarLeitorCaptura(m: DisplayMetrics) {
        synchronized(capturaLock) {
            if (encerramentoEmAndamento.get()) return
            val atual = imageReader
            val novo = try { ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2) }
                catch (e: Exception) { registrarStatus("Falha ao recriar captura: ${e.javaClass.simpleName}"); return }
            try {
                virtualDisplay?.resize(m.widthPixels, m.heightPixels, m.densityDpi)
                virtualDisplay?.surface = novo.surface
                imageReader = novo
                try { atual?.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                try { novo.close() } catch (_: Exception) {}
                registrarStatus("Falha ao trocar captura: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun ajustarRotacao() {
        val atual = imageReader ?: return
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        if (m.widthPixels == atual.width && m.heightPixels == atual.height) return
        recriarLeitorCaptura(m)
        registrarLogCaptura("TELA mudou para ${m.widthPixels}x${m.heightPixels}")
    }

    private fun executarCiclo() {
        if (processando) {
            if (pausaManual) return
            if (SystemClock.uptimeMillis() - toqueDesde < 8000L) return
            ultimoToque = "Gesto sem retorno após 8s; rota pausada. Aguardando Android liberar toque"
            // Trata a pausa de segurança como pausa manual para congelar os temporizadores da rota.
            Rota.iniciarPausaManual()
            pausaManual = true
            EstadoBotController.definir(EstadoBot.PAUSADO)
            Rota.cancelarAutoloot = true
            registrarStatus(ultimoToque)
            LogRota.registrarPasso(this, ultimoToque, ultimoFrameLog)
            return
        }
        ajustarRotacao()
        val agora = SystemClock.uptimeMillis()
        val frame = copiarImagemAtual()
        if (frame == null) {
            if (!pausaManual && ultimoFrameEm > 0L && agora - ultimoFrameEm > 3000L) {
                val segsSemImagem = (agora - ultimoFrameEm) / 1000L
                registrarStatus("SEM IMAGEM há ${segsSemImagem}s - recriando captura")
                if (agora - ultimaRecriacaoCapturaEm >= 5000L) {
                    ultimaRecriacaoCapturaEm = agora
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
                    recriarLeitorCaptura(m)
                    registrarLogCaptura("SEM IMAGEM há ${segsSemImagem}s - recriando captura (${m.widthPixels}x${m.heightPixels})")
                }
            }
            return
        }
        ultimoFrameEm = agora


        try {
            // Miniatura para logs; evita manter outra cópia integral de ~10 MB por ciclo.
            val copiaLog = Bitmap.createScaledBitmap(frame, (frame.width / 4).coerceAtLeast(1), (frame.height / 4).coerceAtLeast(1), true)
            ultimoFrameLog?.recycle()
            ultimoFrameLog = copiaLog
        } catch (_: Exception) {}

        try {
            val resultado = if (pausaManual) {
                pararAlarme()
                "Pausado"
            } else if (soAlarme) {
                Rota.passoSoAlarme(frame, this, uriSomAlerta, volumeAlerta)
            } else {
                Rota.passo(frame, this, usaLoot, usaAlerta, uriSomAlerta, volumeAlerta) { x, y -> tocarNaTela(x, y) }
            }
            if (!pausaManual) EstadoBotController.definir(EstadoBot.NAVEGANDO)
            registrarStatus(if (ultimoToque.isEmpty()) resultado else "$resultado | $ultimoToque")
        } finally {
            frame.recycle()
        }
    }

    private fun tocarNaTela(x: Float, y: Float) {
        if (soAlarme || pausaManual || encerramentoEmAndamento.get()) return
        if (processando) return
        processando = true
        toqueDesde = SystemClock.uptimeMillis()
        val aceito = ToqueService.clicar(x, y) { _, msg ->
            ultimoToque = msg
            processando = false
        }
        if (!aceito) { processando = false }
    }

    private fun extrairNumeroPino(texto: String): String {
        val match = Regex("pino=(\\d+)").find(texto)
        if (match != null) return match.groupValues[1]
        val matchPinoEspaco = Regex("\\bpino\\s+(\\d+)\\b", RegexOption.IGNORE_CASE).find(texto)
        if (matchPinoEspaco != null) return matchPinoEspaco.groupValues[1]
        return "?"
    }

    private fun statusCurto(texto: String): String {
        val limpa = texto.substringBefore(" | ").trim()
        if (limpa == "Pausado") return "Pausado"
        if (texto.contains("PAUSADO_POR_PINO_PERDIDO", ignoreCase = true)) return "Pino perdido - bot pausado"
        if (limpa.startsWith("RESYNC", ignoreCase = true)) return "Reajustando pino"
        if (limpa.startsWith("AUTOLOOT", ignoreCase = true)) return "Pegando loot"
        if (limpa.startsWith("ALARME", ignoreCase = true)) {
            val segMatch = Regex("há\\s+(\\d+s)", RegexOption.IGNORE_CASE).find(limpa)
            val segStr = segMatch?.groupValues?.get(1) ?: ""
            return if (segStr.isNotEmpty()) "ALARME $segStr" else "ALARME"
        }
        val lutaMatch = Regex("^LUTA\\s+(\\d+s)", RegexOption.IGNORE_CASE).find(limpa)
        if (lutaMatch != null) {
            return "Luta ${lutaMatch.groupValues[1]}"
        }
        if (limpa.contains("nao localizado", ignoreCase = true)) {
            val p = extrairNumeroPino(texto)
            return "Pino $p não achado"
        }
        if (limpa.startsWith("fase=CHEGOU", ignoreCase = true)) {
            val p = extrairNumeroPino(texto)
            return "Chegou ao pino $p"
        }
        if (limpa.startsWith("fase=CAMINHANDO", ignoreCase = true)) {
            val p = extrairNumeroPino(texto)
            return "Indo ao pino $p"
        }
        if (limpa.startsWith("fase=ESCOLHER", ignoreCase = true)) {
            val p = extrairNumeroPino(texto)
            return "Procurando pino $p"
        }
        if (limpa.contains("procurando", ignoreCase = true)) {
            val p = extrairNumeroPino(texto)
            return "Procurando pino $p"
        }
        return if (limpa.length > 40) limpa.take(40) else limpa
    }

    private fun atualizarContadoresOverlay() {
        val textoContadores = "Chegadas ${Rota.chegadasConfirmadas} | Pulos ${Rota.pulosPino} | Lutas ${Rota.lutas} | Loots ${Rota.autoloots}"
        handler.post { contadorView?.text = textoContadores }
    }

    private fun registrarStatus(msg: String) {
        atualizarContadoresOverlay()
        val mudou = statusAtual != msg
        if (mudou) statusAtual = msg
        val curto = statusCurto(msg)
        val pinoPerdido = Rota.estaPausadoPorPinoPerdido()
        handler.post {
            if (mudou) statusView?.text = "✥ $curto"
            controleRotaView?.text = when {
                pinoPerdido -> "Retomar"
                !rotaIniciada -> "Iniciar"
                pausaManual -> "Retomar"
                else -> "Pausar"
            }
        }
    }

    private fun criarOverlay() {
        handler.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val painel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 6, 10, 6)
                setBackgroundColor(Color.argb(230, 25, 25, 25))
            }
            overlay = painel

            statusView = TextView(this).apply {
                text = "✥ ${statusCurto(statusAtual)}"
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(4, 2, 4, 2)
            }
            painel.addView(statusView)

            contadorView = TextView(this).apply {
                text = "Chegadas ${Rota.chegadasConfirmadas} | Pulos ${Rota.pulosPino} | Lutas ${Rota.lutas} | Loots ${Rota.autoloots}"
                setTextColor(Color.LTGRAY)
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(4, 0, 4, 4)
            }
            painel.addView(contadorView)

            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val btnIniciarPausar = Button(this).apply {
                text = "Iniciar"
                setOnClickListener {
                    if (Rota.estaPausadoPorPinoPerdido()) {
                        Rota.compensarPausaManual()
                        Rota.retomarPausaPinoPerdido()
                        Rota.cancelarAutoloot = false
                        pausaManual = false
                        EstadoBotController.definir(EstadoBot.NAVEGANDO)
                        BotService.pararAlarme()
                        text = "Pausar"
                        atualizarContadoresOverlay()
                        return@setOnClickListener
                    }
                    if (!rotaIniciada) {
                        Rota.resetar()
                        Rota.cancelarAutoloot = false
                        val agoraInicio = SystemClock.uptimeMillis()
                        ultimoFrameEm = agoraInicio
                        ultimaRecriacaoCapturaEm = 0L
                        rotaIniciada = true
                        rodando = true
                        pausaManual = false
                        EstadoBotController.definir(EstadoBot.NAVEGANDO)
                        text = "Pausar"
                        atualizarContadoresOverlay()
                        workerHandler.post(ciclo)
                    } else {
                        val novaPausa = !pausaManual
                        if (novaPausa) {
                            Rota.iniciarPausaManual()
                            Rota.cancelarAutoloot = true
                            pausaManual = true
                            EstadoBotController.definir(EstadoBot.PAUSADO)
                        } else {
                            Rota.compensarPausaManual()
                            Rota.cancelarAutoloot = false
                            pausaManual = false
                            EstadoBotController.definir(EstadoBot.NAVEGANDO)
                            Rota.retomarPausaPinoPerdido()
                            BotService.pararAlarme()
                        }
                        text = if (pausaManual) "Retomar" else "Pausar"
                        atualizarContadoresOverlay()
                    }
                }
            }
            controleRotaView = btnIniciarPausar
            val btnParar = Button(this).apply {
                text = "Parar"
                setOnClickListener { pararTudo("Encerrado.") }
            }
            linha.addView(btnIniciarPausar)
            linha.addView(btnParar)
            painel.addView(linha)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 320
                y = 40
            }

            val dragListener = object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                wm.updateViewLayout(painel, params)
                            } catch (_: Exception) {}
                            return true
                        }
                    }
                    return false
                }
            }

            painel.setOnTouchListener(dragListener)
            statusView?.setOnTouchListener(dragListener)

            try { wm.addView(painel, params) } catch (_: Exception) {}
        }
    }

    private fun removerOverlay() {
        pararAlarme()
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
            overlay = null
        }
    }

    private fun liberarCaptura() {
        Rota.capturadorQuadroLoot = null
        synchronized(capturaLock) {
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
        }
        ultimoFrameLog?.recycle()
        ultimoFrameLog = null
    }

    private fun pararTudo(motivo: String) {
        if (!encerramentoEmAndamento.compareAndSet(false, true)) return
        EstadoBotController.definir(EstadoBot.ENCERRANDO)
        Rota.cancelarAutoloot = true
        rodando = false
        rotaIniciada = false
        pausaManual = true
        workerHandler.removeCallbacks(ciclo)
        liberarCaptura()
        removerOverlay()
        registrarStatus(motivo)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        EstadoBotController.resetar()
    }

    private fun iniciarNotificacao() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CANAL, "RotaBot", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("RotaBot Ativo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(NOTIFICACAO_ID, notification)
    }

    override fun onDestroy() {
        Rota.cancelarAutoloot = true
        pararAlarme()
        pararTudo("Destruído.")
        workerThread.quitSafely()
        super.onDestroy()
    }
}