package com.rotabot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button

class BotService : Service() {

    private var projecao: MediaProjection? = null
    private var leitor: ImageReader? = null
    private var virtual: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rodando = false
    private var estado = "parado"

    private var janela: WindowManager? = null
    private var botao: Button? = null

    private val ciclo = object : Runnable {
        override fun run() {
            if (!rodando) return
            try {
                lerTela()
            } catch (e: Exception) {
                estado = "erro: ${e.javaClass.simpleName}"
            }
            atualizarNotificacao()
            handler.postDelayed(this, INTERVALO_LEITURA_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RelatorioErro.instalar(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_PARAR -> { parar(); stopSelf() }
            ACAO_INICIAR -> {
                criarCanal()
                startForeground(1, notificacao("pronto: toque no botão flutuante"), tipoMidia())
                Rota.configurar(intent.getIntExtra("inicio", 1), intent.getIntExtra("fim", 5))
                val resultado = intent.getIntExtra("resultado", 0)
                val dados = intent.getParcelableExtra<Intent>("dados")
                if (resultado != 0 && dados != null) prepararCaptura(resultado, dados)
                criarBotao()
            }
        }
        return START_NOT_STICKY
    }

    private fun tipoMidia(): Int =
        if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    private fun prepararCaptura(resultado: Int, dados: Intent) {
        val gerente = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projecao = gerente.getMediaProjection(resultado, dados)
        projecao?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { estado = "captura encerrada" }
        }, handler)

        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        leitor = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtual = projecao?.createVirtualDisplay(
            "rotabot", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            leitor?.surface, null, null
        )
        Rota.carregar(this, w)
    }

    /** Botão flutuante sobre o jogo: toque para ligar/desligar, arraste para mover. */
    private fun criarBotao() {
        if (botao != null) return
        janela = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "▶ RotaBot"
            setOnClickListener { alternar() }
        }
        val tipo = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        var dx = 0f
        var dy = 0f
        var moveu = false
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX - params.x
                    dy = e.rawY - params.y
                    moveu = false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - dx).toInt()
                    params.y = (e.rawY - dy).toInt()
                    janela?.updateViewLayout(b, params)
                    moveu = true
                }
                MotionEvent.ACTION_UP -> if (!moveu) v.performClick()
            }
            true
        }
        janela?.addView(b, params)
        botao = b
    }

    private fun alternar() {
        if (rodando) {
            rodando = false
            handler.removeCallbacks(ciclo)
            estado = "pausado"
        } else {
            if (leitor == null) { estado = "sem permissão de captura"; return }
            Rota.resetar()
            rodando = true
            handler.post(ciclo)
        }
        botao?.text = if (rodando) "■ Parar" else "▶ RotaBot"
        atualizarNotificacao()
    }

    private fun lerTela() {
        val imagem = leitor?.acquireLatestImage() ?: return
        try {
            val plano = imagem.planes[0]
            val bmp = Bitmap.createBitmap(
                imagem.width + (plano.rowStride - plano.pixelStride * imagem.width) / plano.pixelStride,
                imagem.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plano.buffer)
            val toque = ToqueService.instancia
            estado = if (toque == null) {
                "ative a acessibilidade do RotaBot"
            } else {
                Rota.passo(bmp) { x, y -> toque.tocar(x, y) }
            }
        } finally {
            imagem.close()
        }
    }

    private fun parar() {
        rodando = false
        handler.removeCallbacks(ciclo)
        botao?.let { janela?.removeView(it) }
        botao = null
        virtual?.release()
        projecao?.stop()
        virtual = null
        projecao = null
        estado = "parado"
    }

    override fun onDestroy() { parar(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel("rotabot", "RotaBot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun notificacao(texto: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "rotabot")
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("RotaBot")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun atualizarNotificacao() {
        getSystemService(NotificationManager::class.java).notify(1, notificacao(estado))
    }

    companion object {
        const val ACAO_INICIAR = "iniciar"
        const val ACAO_PARAR = "parar"
        const val INTERVALO_LEITURA_MS = 1000L
    }
}
