package com.rotabot

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast

class MainActivity : Activity() {
    private val codigoCaptura = 42
    private var dadosCaptura: Intent? = null
    private var resultadoCaptura = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RelatorioErro.instalar(this)

        val tela = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        val titulo = TextView(this).apply {
            text = "RotaBot\n\n1. Permita a sobreposição.\n2. Ative o serviço de acessibilidade.\n3. Autorize a captura da tela.\n4. Escolha os marcadores inicial e final (1 a 5) e toque em Iniciar.\n\nDurante a rota, use os controles flutuantes para pausar ou encerrar."
            textSize = 16f
        }
        val inicio = EditText(this).apply {
            hint = "Marcador inicial (1 a 5)"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val fim = EditText(this).apply {
            hint = "Marcador final (1 a 5)"
            setText("5")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val sobreposicao = Button(this).apply {
            text = "1) Permitir sobreposição"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        val acessibilidade = Button(this).apply {
            text = "2) Abrir acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val captura = Button(this).apply {
            text = "3) Autorizar captura da tela"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), codigoCaptura)
            }
        }
        val iniciar = Button(this).apply {
            text = "Iniciar RotaBot"
            setOnClickListener {
                val a = inicio.text.toString().toIntOrNull()
                val b = fim.text.toString().toIntOrNull()
                if (a == null || b == null || a !in 1..5 || b !in 1..5 || a > b) {
                    Toast.makeText(this@MainActivity, "Escolha marcadores entre 1 e 5. O inicial deve ser menor ou igual ao final.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Ative a permissão de sobreposição.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (ToqueService.instance == null) {
                    Toast.makeText(this@MainActivity, "Ative o RotaBot em Acessibilidade e volte.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val dados = dadosCaptura
                if (dados == null) {
                    Toast.makeText(this@MainActivity, "Autorize a captura da tela primeiro.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val serviceIntent = Intent(this@MainActivity, BotService::class.java)
                    .setAction(BotService.ACAO_INICIAR)
                    .putExtra(BotService.EXTRA_INICIO, a)
                    .putExtra(BotService.EXTRA_FIM, b)
                    .putExtra(BotService.EXTRA_RESULTADO, resultadoCaptura)
                    .putExtra(BotService.EXTRA_DADOS, dados)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                finish()
            }
        }
        tela.addView(titulo)
        tela.addView(inicio)
        tela.addView(fim)
        tela.addView(sobreposicao)
        tela.addView(acessibilidade)
        tela.addView(captura)
        tela.addView(iniciar)
        val relatorio = Button(this).apply {
            text = "Ver relatório de erro"
            setOnClickListener {
                val texto = RelatorioErro.ler(this@MainActivity)
                if (texto.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Ainda não há erros registrados.", Toast.LENGTH_SHORT).show()
                } else {
                    val campo = EditText(this@MainActivity).apply {
                        setText(texto)
                        setTextIsSelectable(true)
                        isSingleLine = false
                        setHorizontallyScrolling(false)
                        textSize = 12f
                        setPadding(24, 16, 24, 16)
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Relatório — selecione e copie")
                        .setView(campo)
                        .setPositiveButton("Copiar tudo") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Relatório RotaBot", campo.text.toString()))
                            Toast.makeText(this@MainActivity, "Relatório copiado. Cole no chat.", Toast.LENGTH_LONG).show()
                        }
                        .setNeutralButton("Limpar relatório") { _, _ -> RelatorioErro.limpar(this@MainActivity) }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
            }
        }
        tela.addView(relatorio)
        setContentView(tela)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == codigoCaptura) {
            if (resultCode == RESULT_OK && data != null) {
                resultadoCaptura = resultCode
                dadosCaptura = data
                Toast.makeText(this, "Captura autorizada. Agora toque em Iniciar.", Toast.LENGTH_SHORT).show()
            } else {
                dadosCaptura = null
                Toast.makeText(this, "A captura é necessária para usar a rota.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
