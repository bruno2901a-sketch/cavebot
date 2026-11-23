package com.rotabot

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private var dadosCaptura: Intent? = null
    private var resultadoCaptura = 0
    private var uriSomSelecionado: String = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString() ?: ""
    private var contadoresResumoView: TextView? = null

    private val capturaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        if (resultado.resultCode == RESULT_OK && resultado.data != null) {
            resultadoCaptura = resultado.resultCode
            dadosCaptura = resultado.data
            Toast.makeText(this, "Captura autorizada. Toque em Iniciar.", Toast.LENGTH_SHORT).show()
        } else {
            resultadoCaptura = 0
            dadosCaptura = null
            Toast.makeText(this, "A captura é necessária.", Toast.LENGTH_LONG).show()
        }
    }

    private val somLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        if (resultado.resultCode == RESULT_OK && resultado.data != null) {
            val uri = if (Build.VERSION.SDK_INT >= 33) resultado.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) else resultado.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                uriSomSelecionado = uri.toString()
                getSharedPreferences("rotabot_prefs", Context.MODE_PRIVATE).edit().putString("uri_som", uriSomSelecionado).apply()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RelatorioErro.instalar(this)
        val prefs = getSharedPreferences("rotabot_prefs", Context.MODE_PRIVATE)
        uriSomSelecionado = prefs.getString("uri_som", uriSomSelecionado) ?: uriSomSelecionado
        if (savedInstanceState != null) {
            // Tokens MediaProjection são de uso único; não restaura após recriação.
            uriSomSelecionado = savedInstanceState.getString("uriSom", uriSomSelecionado)
        }

        val tela = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        val titulo = TextView(this).apply {
            text = "RotaBot v1.9.4\n\n1. Permita a sobreposição.\n2. Ative a acessibilidade.\n3. Autorize a captura da tela.\n4. Ajuste as opções e inicie."
            textSize = 14f
        }
        val inicio = EditText(this).apply {
            hint = "Marcador inicial (1 a ${Cal.TOTAL_ICONES})"
            setText(prefs.getInt("marcador_inicio", 1).coerceIn(1, Cal.TOTAL_ICONES).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val fim = EditText(this).apply {
            hint = "Marcador final (1 a ${Cal.TOTAL_ICONES})"
            setText(prefs.getInt("marcador_fim", 5).coerceIn(1, Cal.TOTAL_ICONES).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val checkLoot = CheckBox(this).apply {
            text = "Ativar Autoloot após matar monstro"
            isChecked = prefs.getBoolean("usa_loot", false)
        }

        val checkAlerta = CheckBox(this).apply {
            text = "Ativar alerta sonoro de batalha prolongada"
            isChecked = prefs.getBoolean("usa_alerta", true)
        }

        val checkSoAlarme = CheckBox(this).apply {
            text = "Modo somente alarme (pesca): não anda e não clica; alerta após confirmar batalha"
            isChecked = prefs.getBoolean("so_alarme", false)
        }

        val labelVolume = TextView(this).apply { text = "Volume do Alerta:" }
        val seekVolume = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("volume_alerta", 80).coerceIn(0, 100)
        }

        val btnEscolherSom = Button(this).apply {
            text = "Escolher Toque do Alarme"
            setOnClickListener {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Selecione o som do alerta")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uriSomSelecionado.takeIf { it.isNotBlank() }?.let(Uri::parse))
                }
                somLauncher.launch(intent)
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
                capturaLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
        val alarmeSalvo = prefs.getInt("tempo_alarme_s", 30).coerceIn(5, 300)
        val lootIntervaloSalvo = prefs.getInt("intervalo_loot_ms", 100).coerceIn(30, 500)
        val lootMaxSalvo = prefs.getInt("tempo_max_autoloot_s", 40).coerceIn(5, 120)
        val pinoIntervaloSalvo = prefs.getInt("intervalo_pino_ms", 700).coerceIn(300, 2000)
        val pxPorCasaSalvo = prefs.getFloat("pixels_por_casa", Cal.PIXELS_POR_CASA_PADRAO).coerceIn(4f, 200f)
        val tamCasaPxSalvo = prefs.getFloat("tamanho_casa_px", Cal.TAMANHO_CASA_PX_PADRAO).coerceIn(10f, 300f)
        val limiarParadoSalvo = prefs.getFloat("limiar_minimapa_parado", Cal.LIMIAR_MINIMAPA_PARADO_PADRAO).coerceIn(0.1f, 100f)
        val refBarraXSalvo = prefs.getFloat("ref_barra_titulo_x", Cal.REF_BARRA_TITULO_X_PADRAO).coerceIn(0f, 1f)
        val refBarraYSalvo = prefs.getFloat("ref_barra_titulo_y", Cal.REF_BARRA_TITULO_Y_PADRAO).coerceIn(0f, 1f)

        val labelTempoAlarme = TextView(this).apply { text = "Tempo do alarme (s)" }
        val editTempoAlarme = EditText(this).apply {
            hint = "Tempo do alarme (s) [5-300]"
            setText(alarmeSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val labelIntervaloLoot = TextView(this).apply { text = "Intervalo entre cliques do loot (ms)" }
        val editIntervaloLoot = EditText(this).apply {
            hint = "Intervalo entre cliques do loot (ms) [30-500]"
            setText(lootIntervaloSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val labelMaxAutoloot = TextView(this).apply { text = "Tempo máximo do autoloot (s)" }
        val editMaxAutoloot = EditText(this).apply {
            hint = "Tempo máximo do autoloot (s) [5-120]"
            setText(lootMaxSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val labelPxPorCasa = TextView(this).apply { text = "Pixels por casa (minimapa)" }
        val editPxPorCasa = EditText(this).apply {
            hint = "Pixels por casa (ex: 16.0)"
            setText(pxPorCasaSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val labelTamCasaPx = TextView(this).apply { text = "Tamanho da casa em pixels (jogo)" }
        val editTamCasaPx = EditText(this).apply {
            hint = "Tamanho da casa em px (ex: 88.0)"
            setText(tamCasaPxSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val labelLimiarParado = TextView(this).apply { text = "Limiar de minimapa parado (padrão 3.2)" }
        val editLimiarParado = EditText(this).apply {
            hint = "Limiar minimapa parado (ex: 3.2)"
            setText(limiarParadoSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val labelRefBarraTitulo = TextView(this).apply { text = "Referência barra de título loot (X, Y fração)" }
        val editRefBarraX = EditText(this).apply {
            hint = "Ref barra título X (ex: 0.620)"
            setText(refBarraXSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val editRefBarraY = EditText(this).apply {
            hint = "Ref barra título Y (ex: 0.145)"
            setText(refBarraYSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        contadoresResumoView = TextView(this).apply {
            text = "Chegadas: ${Rota.chegadasConfirmadas} | Pulos: ${Rota.pulosPino}"
            textSize = 13f
        }

        val containerAvancado = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val labelIntervaloPino = TextView(this).apply { text = "Intervalo entre cliques do pino (ms)" }
        val editIntervaloPino = EditText(this).apply {
            hint = "Intervalo entre cliques do pino (ms) [300-2000]"
            setText(pinoIntervaloSalvo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        containerAvancado.addView(labelIntervaloPino)
        containerAvancado.addView(editIntervaloPino)

        val btnAvancado = Button(this).apply {
            text = "Avançado"
            setOnClickListener {
                containerAvancado.visibility = if (containerAvancado.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val iniciar = Button(this).apply {
            text = "Iniciar RotaBot"
            setOnClickListener {
                val a = inicio.text.toString().toIntOrNull()
                val b = fim.text.toString().toIntOrNull()
                if (a == null || b == null || a !in 1..Cal.TOTAL_ICONES || b !in 1..Cal.TOTAL_ICONES || a > b) {
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

                val alarmeS = (editTempoAlarme.text.toString().toIntOrNull() ?: 30).coerceIn(5, 300)
                val lootMs = (editIntervaloLoot.text.toString().toIntOrNull() ?: 100).coerceIn(30, 500)
                val lootMaxS = (editMaxAutoloot.text.toString().toIntOrNull() ?: 40).coerceIn(5, 120)
                val pinoMs = (editIntervaloPino.text.toString().toIntOrNull() ?: 700).coerceIn(300, 2000)
                val pxPorCasa = (editPxPorCasa.text.toString().replace(',', '.').toFloatOrNull() ?: Cal.PIXELS_POR_CASA_PADRAO).coerceIn(4f, 200f)
                val tamCasaPx = (editTamCasaPx.text.toString().replace(',', '.').toFloatOrNull() ?: Cal.TAMANHO_CASA_PX_PADRAO).coerceIn(10f, 300f)
                val limiarParado = (editLimiarParado.text.toString().replace(',', '.').toFloatOrNull() ?: Cal.LIMIAR_MINIMAPA_PARADO_PADRAO).coerceIn(0.1f, 100f)
                val refBarraX = (editRefBarraX.text.toString().replace(',', '.').toFloatOrNull() ?: Cal.REF_BARRA_TITULO_X_PADRAO).coerceIn(0f, 1f)
                val refBarraY = (editRefBarraY.text.toString().replace(',', '.').toFloatOrNull() ?: Cal.REF_BARRA_TITULO_Y_PADRAO).coerceIn(0f, 1f)

                prefs.edit()
                    .putInt("tempo_alarme_s", alarmeS)
                    .putInt("intervalo_loot_ms", lootMs)
                    .putInt("tempo_max_autoloot_s", lootMaxS)
                    .putInt("intervalo_pino_ms", pinoMs)
                    .putFloat("pixels_por_casa", pxPorCasa)
                    .putFloat("tamanho_casa_px", tamCasaPx)
                    .putFloat("limiar_minimapa_parado", limiarParado)
                    .putFloat("ref_barra_titulo_x", refBarraX)
                    .putFloat("ref_barra_titulo_y", refBarraY)
                    .apply()

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
                    .putExtra(BotService.EXTRA_ALARME_S, alarmeS)
                    .putExtra(BotService.EXTRA_LOOT_MS, lootMs)
                    .putExtra(BotService.EXTRA_LOOT_MAX_S, lootMaxS)
                    .putExtra(BotService.EXTRA_PINO_MS, pinoMs)
                    .putExtra(BotService.EXTRA_PIXELS_POR_CASA, pxPorCasa)
                    .putExtra(BotService.EXTRA_TAMANHO_CASA_PX, tamCasaPx)
                    .putExtra(BotService.EXTRA_LIMIAR_MINIMAPA_PARADO, limiarParado)
                    .putExtra(BotService.EXTRA_REF_BARRA_TITULO_X, refBarraX)
                    .putExtra(BotService.EXTRA_REF_BARRA_TITULO_Y, refBarraY)

                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                finish()
            }
        }

        tela.addView(titulo)
        tela.addView(contadoresResumoView)
        tela.addView(inicio)
        tela.addView(fim)
        tela.addView(checkLoot)
        tela.addView(checkAlerta)
        tela.addView(checkSoAlarme)
        tela.addView(labelTempoAlarme)
        tela.addView(editTempoAlarme)
        tela.addView(labelIntervaloLoot)
        tela.addView(editIntervaloLoot)
        tela.addView(labelMaxAutoloot)
        tela.addView(editMaxAutoloot)
        tela.addView(labelPxPorCasa)
        tela.addView(editPxPorCasa)
        tela.addView(labelTamCasaPx)
        tela.addView(editTamCasaPx)
        tela.addView(labelLimiarParado)
        tela.addView(editLimiarParado)
        tela.addView(labelRefBarraTitulo)
        tela.addView(editRefBarraX)
        tela.addView(editRefBarraY)
        tela.addView(btnAvancado)
        tela.addView(containerAvancado)
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
        val exportar15Min = Button(this).apply {
            text = "Exportar últimos 15 min"
            setOnClickListener { LogRota.exportarUltimos15Min(this@MainActivity) }
        }
        val limparLog = Button(this).apply {
            text = "Limpar log"
            setOnClickListener { LogRota.limparLog(this@MainActivity) }
        }
        tela.addView(exportarLog)
        tela.addView(exportar15Min)
        tela.addView(limparLog)

        val rolagem = ScrollView(this).apply {
            isFillViewport = true
            addView(tela)
        }
        setContentView(rolagem)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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