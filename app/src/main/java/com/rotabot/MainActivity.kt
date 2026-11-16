package com.rotabot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val CODIGO_CAPTURA = 42
    private var dadosCaptura: Intent? = null
    private var resultadoCaptura = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RelatorioErro.instalar(this)

        val tela = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val erroAnterior = RelatorioErro.ler(this)
        if (erroAnterior != null) {
            tela.addView(TextView(this).apply {
                text = "ERRO DA ÚLTIMA VEZ:\n\n$erroAnterior"
                textSize = 11f
            })
            tela.addView(Button(this).apply {
                text = "Limpar erro"
                setOnClickListener {
                    RelatorioErro.limpar(this@MainActivity)
                    recreate()
                }
            })
        }
        val texto = TextView(this).apply {
            text = "RotaBot\n\nPrimeiro (uma vez):\n1) Permitir sobreposição\n2) Ativar acessibilidade\n3) Permitir captura\n\nDepois, para cada hunt:\nescolha o ícone inicial e o final (1 a 20), depois toque em Iniciar.\nNo jogo, use o botão flutuante para ligar e desligar."
            textSize = 15f
        }
        val campoInicio = EditText(this).apply {
            hint = "Ícone inicial (1 a 20)"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val campoFim = EditText(this).apply {
            hint = "Ícone final (1 a 20)"
            setText("5")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val btnSobreposicao = Button(this).apply {
            text = "1) Permitir sobreposição"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            }
        }
        val btnAcess = Button(this).apply {
            text = "2) Abrir acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnCaptura = Button(this).apply {
            text = "3) Permitir captura"
            setOnClickListener {
                val gerente = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(gerente.createScreenCaptureIntent(), CODIGO_CAPTURA)
            }
        }
        val btnIniciar = Button(this).apply {
            text = "Iniciar (com a hunt escolhida)"
            setOnClickListener { iniciarBot(campoInicio, campoFim) }
        }
        val btnParar = Button(this).apply {
            text = "Parar serviço"
            setOnClickListener {
                startService(Intent(this@MainActivity, BotService::class.java)
                    .setAction(BotService.ACAO_PARAR))
            }
        }

        tela.addView(texto)
        tela.addView(campoInicio)
        tela.addView(campoFim)
        tela.addView(btnSobreposicao)
        tela.addView(btnAcess)
        tela.addView(btnCaptura)
        tela.addView(btnIniciar)
        tela.addView(btnParar)
        setContentView(tela)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODIGO_CAPTURA && resultCode == RESULT_OK && data != null) {
            resultadoCaptura = resultCode
            dadosCaptura = data
        }
    }

    private fun iniciarBot(campoInicio: EditText, campoFim: EditText) {
        val inicio = campoInicio.text.toString().toIntOrNull() ?: 1
        val fim = campoFim.text.toString().toIntOrNull() ?: 5
        val dados = dadosCaptura
        val intent = Intent(this, BotService::class.java)
            .setAction(BotService.ACAO_INICIAR)
            .putExtra("inicio", inicio)
            .putExtra("fim", fim)
            .putExtra("resultado", resultadoCaptura)
            .putExtra("dados", dados)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        finish()
    }
}
