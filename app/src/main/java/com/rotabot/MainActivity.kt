package com.rotabot

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val codigoCaptura = 42
    private var dadosCaptura: Intent? = null
    private var resultadoCaptura = 0
    private var uriSomSelecionado: String = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString() ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RelatorioErro.instalar(this)
        if (savedInstanceState != null) {
            resultadoCaptura = savedInstanceState.getInt("resultado", 0)
            dadosCaptura = savedInstanceState.getParcelable("dados")
            uriSomSelecionado = savedInstanceState.getString("uriSom", uriSomSelecionado)
        }

        val tela = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        val titulo = TextView(this).apply {
            text = "RotaBot\n\n1. Permita a sobreposição.\n2. Ative a acessibilidade.\n3. Autorize a captura da tela.\n4. Ajuste as opções e inicie."
            textSize = 14f
        }
        val inicio = EditText(this).apply {
            hint = "Marcador inicial (1 a 20)"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val fim = EditText(this).apply {
            hint = "Marcador final (1 a 20)"
            setText("5")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val checkLoot = CheckBox(this).apply {
            text = "Ativar Autoloot após matar monstro"
            isChecked = false
        }

        val checkAlerta = CheckBox(this).apply {
            text = "Ativar Alerta Sonoro (Batalha > 15s / Player)"
            isChecked = true
        }

        val checkSoAlarme = CheckBox(this).apply {
            text = "Modo somente alarme (pesca): não anda e não clica; toca o alarme se algo ficar 15s ou mais na Batalha"
            isChecked = false
        }

        val labelVolume = TextView(this).apply { text = "Volume do Alerta:" }
        val seekVolume = SeekBar(this).apply {
            max = 100
            progress = 80
        }

        val btnEscolherSom = Button(this).apply {
            text = "Escolher Toque do Alarme"
            setOnClickListener {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Selecione o som do alerta")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(uriSomSelecionado))
                }
                startActivityForResult(intent, 99)
            }
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
                if (a == null || b == null || a !in 1..20 || b !in 1..20 || a > b) {
                    Toast.makeText(this@MainActivity, "Escolha marcadores válidos (1 a 20).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Ative a permissão de sobreposição.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val modoSoAlarme = checkSoAlarme.isChecked
                if (!modoSoAlarme && ToqueService.instance == null) {
                    Toast.makeText(this@MainActivity, "Ative o RotaBot em Acessibilidade.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val dados = dadosCaptura
                if (dados == null) {
                    Toast.makeText(this@MainActivity, "Autorize a captura da tela primeiro.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val usaAlertaFinal = if (modoSoAlarme) true else checkAlerta.isChecked
                val serviceIntent = Intent(this@MainActivity, BotService::class.java)
                    .setAction(BotService.ACAO_INICIAR)
                    .putExtra(BotService.EXTRA_INICIO, a)
                    .putExtra(BotService.EXTRA_FIM, b)
                    .putExtra(BotService.EXTRA_RESULTADO, resultadoCaptura)
                    .putExtra(BotService.EXTRA_DADOS, dados)
                    .putExtra(BotService.EXTRA_USA_LOOT, checkLoot.isChecked)
                    .putExtra(BotService.EXTRA_USA_ALERTA, usaAlertaFinal)
                    .putExtra(BotService.EXTRA_SO_ALARME, modoSoAlarme)
                    .putExtra(BotService.EXTRA_VOLUME, seekVolume.progress)
                    .putExtra(BotService.EXTRA_URI_SOM, uriSomSelecionado)

                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                finish()
            }
        }

        tela.addView(titulo)
        tela.addView(inicio)
        tela.addView(fim)
        tela.addView(checkLoot)
        tela.addView(checkAlerta)
        tela.addView(checkSoAlarme)
        tela.addView(labelVolume)
        tela.addView(seekVolume)
        tela.addView(btnEscolherSom)
        tela.addView(sobreposicao)
        tela.addView(acessibilidade)
        tela.addView(captura)
        tela.addView(iniciar)

        val exportarLog = Button(this).apply {
            text = "Exportar log"
            setOnClickListener { LogRota.exportarLog(this@MainActivity) }
        }
        val limparLog = Button(this).apply {
            text = "Limpar log"
            setOnClickListener { LogRota.limparLog(this@MainActivity) }
        }
        tela.addView(exportarLog)
        tela.addView(limparLog)

        setContentView(tela)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("resultado", resultadoCaptura)
        outState.putParcelable("dados", dadosCaptura)
        outState.putString("uriSom", uriSomSelecionado)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == codigoCaptura) {
            if (resultCode == RESULT_OK && data != null) {
                resultadoCaptura = resultCode
                dadosCaptura = data
                Toast.makeText(this, "Captura autorizada. Toque em Iniciar.", Toast.LENGTH_SHORT).show()
            } else {
                dadosCaptura = null
                Toast.makeText(this, "A captura é necessária.", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == 99 && resultCode == RESULT_OK && data != null) {
            val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                uriSomSelecionado = uri.toString()
                Toast.makeText(this, "Som do alarme alterado!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
