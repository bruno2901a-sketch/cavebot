package com.rotabot

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object LogRota {
    private const val TAG = "LogRota"
    private const val NOME_LOG = "rotabot_log.txt"
    private const val MAX_BYTES_LOG = 25L * 1024L * 1024L
    private const val MAX_IMAGENS_FALHA = 30
    private const val MAX_IMAGENS_EXPORT = 10

    // Fila limitada para impedir acumulo ilimitado de bitmaps quando o disco fica lento.
    // CallerRunsPolicy aplica backpressure sem descartar a tarefa nem vazar a copia do quadro.
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64),
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var ultimaAssinatura: String? = null
    private var avisouErroGravacao = false

    fun init(context: Context) {
        appContext = context.applicationContext
        diretorioBase(appContext!!)
    }

    private fun diretorioBase(context: Context): File {
        val extBase = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(extBase, "RotaBot")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun diretorioFalhas(context: Context): File {
        val dir = File(diretorioBase(context), "falhas")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun arquivoLog(context: Context = appContext ?: throw IllegalStateException("LogRota nao iniciado")): File {
        return File(diretorioBase(context), NOME_LOG)
    }

    fun caminhoLog(context: Context): String = arquivoLog(context).absolutePath

    fun caminhoFalhas(context: Context): String = diretorioFalhas(context).absolutePath

    private fun notificarErroUmaVez(context: Context, detalhe: String, ex: Throwable) {
        Log.e(TAG, detalhe, ex)
        if (!avisouErroGravacao) {
            avisouErroGravacao = true
            mainHandler.post {
                Toast.makeText(context, "Erro ao gravar log: ${ex.message ?: detalhe}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val regexAcerto = Regex("acerto=\\d+[.,]\\d+")
    private val regexDist = Regex("dist=(\\d+[.,]\\d+px|N/A)")
    private val regexVerde = Regex("verde=-?\\d+")
    private val regexRgb = Regex("rgb\\(\\d+,\\d+,\\d+\\)")
    private val regexClique = Regex("CLIQUE \\d+,\\d+")
    private val regexDiffMini = Regex("diffMini=\\d+[.,]\\d+")
    private val regexSlug = Regex("[^a-zA-Z0-9]+")

    private fun normalizarAssinatura(msg: String): String {
        return msg
            .replace(regexAcerto, "acerto=#")
            .replace(regexDist, "dist=#")
            .replace(regexVerde, "verde=#")
            .replace(regexRgb, "rgb(#,#,#)")
            .replace(regexClique, "CLIQUE #,#")
            .replace(regexDiffMini, "diffMini=#")
    }

    private fun ehEventoObrigatorio(msg: String): Boolean {
        return msg.contains("CLIQUE") ||
            msg.contains("CHEGOU") ||
            msg.contains("PROXIMO_PINO") ||
            msg.contains("FALHA_PINO") ||
            msg.contains("RESUMO_CONTADORES") ||
            msg.contains("falhas=") ||
            msg.contains("sem progresso") ||
            msg.contains("verde sumiu") ||
            msg.contains("nao localizado") ||
            msg.contains("luta excedeu limite") ||
            msg.contains("BATALHA_INICIO") ||
            msg.contains("ALARME_INICIO") ||
            msg.contains("corpo ") ||
            msg.startsWith("AUTOLOOT")
    }

    private fun motivoFalha(msg: String): String? {
        val motivos = listOf(
            "sem progresso",
            "verde sumiu",
            "nao localizado",
            "luta excedeu limite",
            "FALHA_PINO"
        )
        return motivos.firstOrNull { msg.contains(it, ignoreCase = true) }
    }

    private fun limparArquivosAntigos(dir: File, maxArquivos: Int) {
        val arquivos = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        if (arquivos.size > maxArquivos) {
            val excedente = arquivos.size - maxArquivos
            for (i in 0 until excedente) {
                arquivos[i].delete()
            }
        }
    }

    private fun rotacionarLogSeNecessario(arquivo: File) {
        if (arquivo.exists() && arquivo.length() >= MAX_BYTES_LOG) {
            val backup = File(arquivo.parentFile, "rotabot_log_antigo.txt")
            if (backup.exists()) {
                backup.delete()
            }
            arquivo.renameTo(backup)
            arquivo.createNewFile()
        }
    }

    fun registrarPasso(context: Context, mensagem: String, tela: Bitmap?) {
        val ctx = context.applicationContext
        appContext = ctx
        val assinatura = normalizarAssinatura(mensagem)
        val obrigatorio = ehEventoObrigatorio(mensagem)
        if (!obrigatorio && assinatura == ultimaAssinatura) {
            return
        }
        ultimaAssinatura = assinatura

        val motivo = motivoFalha(mensagem)
        val copiaTela = if (motivo != null && tela != null && !tela.isRecycled) {
            try {
                tela.copy(tela.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (ex: Exception) {
                notificarErroUmaVez(ctx, "Falha ao copiar bitmap para falha", ex)
                null
            }
        } else {
            null
        }

        val timestampHora = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val timestampArquivo = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())

        executor.execute {
            try {
                val baseDir = diretorioBase(ctx)
                val arquivo = File(baseDir, NOME_LOG)
                rotacionarLogSeNecessario(arquivo)

                var nomeImagemSalva: String? = null
                if (motivo != null && copiaTela != null && !copiaTela.isRecycled) {
                    val pastaFalhas = diretorioFalhas(ctx)
                    val slugMotivo = motivo.replace(regexSlug, "_")
                    val nomeImg = "falha_${timestampArquivo}_${slugMotivo}.jpg"
                    val arqImg = File(pastaFalhas, nomeImg)
                    FileOutputStream(arqImg).use { out ->
                        copiaTela.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        out.flush()
                    }
                    nomeImagemSalva = nomeImg
                    limparArquivosAntigos(pastaFalhas, MAX_IMAGENS_FALHA)
                }

                val sufixoImg = if (nomeImagemSalva != null) " | img=$nomeImagemSalva" else ""
                val linha = "[$timestampHora] $mensagem$sufixoImg\n"
                FileOutputStream(arquivo, true).use { out ->
                    out.write(linha.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (ex: Exception) {
                notificarErroUmaVez(ctx, "Falha ao gravar log persistente", ex)
            } finally {
                copiaTela?.let {
                    if (!it.isRecycled) it.recycle()
                }
            }
        }
    }

    fun salvarQuadroDiagnostico(context: Context, tagEvento: String, tela: Bitmap?) {
        if (tela == null || tela.isRecycled) return
        val ctx = context.applicationContext
        appContext = ctx
        val copia = try {
            tela.copy(tela.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (ex: Exception) {
            notificarErroUmaVez(ctx, "Falha ao copiar bitmap de diagnostico", ex)
            null
        } ?: return

        val timestampHora = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val timestampArquivo = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())
        executor.execute {
            try {
                val pastaFalhas = diretorioFalhas(ctx)
                val slug = tagEvento.replace(regexSlug, "_")
                val nomeImg = "diag_${timestampArquivo}_${slug}.jpg"
                val arqImg = File(pastaFalhas, nomeImg)
                FileOutputStream(arqImg).use { out ->
                    copia.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    out.flush()
                }
                limparArquivosAntigos(pastaFalhas, MAX_IMAGENS_FALHA)
                val arquivo = File(diretorioBase(ctx), NOME_LOG)
                rotacionarLogSeNecessario(arquivo)
                val linha = "[$timestampHora] QUADRO_SALVO evento=$tagEvento | img=$nomeImg\n"
                FileOutputStream(arquivo, true).use { out ->
                    out.write(linha.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (ex: Exception) {
                notificarErroUmaVez(ctx, "Falha ao salvar quadro de diagnostico", ex)
            } finally {
                if (!copia.isRecycled) copia.recycle()
            }
        }
    }

    fun exportarLog(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        executor.execute {
            val arquivo = arquivoLog(ctx)
            val pastaFalhas = diretorioFalhas(ctx)
            val imagensRecentes = pastaFalhas.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.take(MAX_IMAGENS_EXPORT)
                ?: emptyList()

            if ((!arquivo.exists() || arquivo.length() == 0L) && imagensRecentes.isEmpty()) {
                mainHandler.post {
                    Toast.makeText(context, "Log vazio", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            try {
                val authority = "${ctx.packageName}.fileprovider"
                val uris = ArrayList<Uri>()
                if (arquivo.exists() && arquivo.length() > 0L) {
                    uris.add(FileProvider.getUriForFile(ctx, authority, arquivo))
                }
                for (img in imagensRecentes) {
                    uris.add(FileProvider.getUriForFile(ctx, authority, img))
                }

                val intent = if (uris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        val clip = ClipData.newUri(ctx.contentResolver, "RotaBot Export", uris.first())
                        for (i in 1 until uris.size) {
                            clip.addItem(ClipData.Item(uris[i]))
                        }
                        clipData = clip
                    }
                } else {
                    val singleUri = uris.first()
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, singleUri)
                        clipData = ClipData.newUri(ctx.contentResolver, "RotaBot Log", singleUri)
                    }
                }.apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(intent, "Exportar log RotaBot").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                mainHandler.post {
                    context.startActivity(chooser)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Erro ao exportar log e imagens", ex)
                mainHandler.post {
                    Toast.makeText(context, "Erro ao exportar log: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parsearHoraMs(linha: String): Long? {
        if (!linha.startsWith("[") || linha.length < 13) return null
        val fimIdx = linha.indexOf(']')
        if (fimIdx < 9) return null
        val valor = linha.substring(1, fimIdx).trim()
        val formatoCompleto = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        formatoCompleto.isLenient = false
        try {
            formatoCompleto.parse(valor)?.let { return it.time }
        } catch (_: Exception) {
            // Tenta abaixo o formato antigo sem data.
        }

        // Compatibilidade com linhas antigas que guardavam somente a hora.
        val dataHoje = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val formatoAntigo = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        formatoAntigo.isLenient = false
        return try { formatoAntigo.parse("$dataHoje $valor")?.time } catch (_: Exception) { null }
    }

    private fun dentroJanela15Min(msLinha: Long, msReferencia: Long): Boolean {
        val janelaMs = 15L * 60L * 1000L
        val diff = msReferencia - msLinha
        return diff in 0L..janelaMs
    }

    fun exportarUltimos15Min(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        executor.execute {
            val baseDir = diretorioBase(ctx)
            val arquivoAtual = File(baseDir, NOME_LOG)
            val arquivoAntigo = File(baseDir, "rotabot_log_antigo.txt")
            val pastaFalhas = diretorioFalhas(ctx)
            val imagensRecentes = pastaFalhas.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.take(MAX_IMAGENS_EXPORT)
                ?: emptyList()

            val arquivosLog = listOf(arquivoAntigo, arquivoAtual).filter { it.exists() && it.length() > 0L }
            if (arquivosLog.isEmpty()) {
                mainHandler.post {
                    Toast.makeText(context, "Log vazio", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            // A janela é calculada em relação ao relógio atual, não à última linha gravada.
            val msReferencia = System.currentTimeMillis()
            for (arq in arquivosLog) {
                try {
                    arq.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        seq.forEach { linha ->
                            val parsed = parsearHoraMs(linha)
                            if (parsed != null) {
                                msReferencia = parsed
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val refMs = msReferencia

            try {
                val arq15 = File(baseDir, "rotabot_log_ultimos15min.txt")
                var linhasEscritas = 0

                // Passada 2: ler novamente linha a linha e escrever direto via BufferedWriter
                arq15.bufferedWriter(Charsets.UTF_8).use { writer ->
                    var blocoAtualDentro = false
                    for (arq in arquivosLog) {
                        try {
                            arq.bufferedReader(Charsets.UTF_8).useLines { seq ->
                                seq.forEach { linha ->
                                    val msLinha = parsearHoraMs(linha)
                                    if (msLinha != null) {
                                        blocoAtualDentro = dentroJanela15Min(msLinha, refMs)
                                        if (blocoAtualDentro) {
                                            writer.write(linha)
                                            writer.newLine()
                                            linhasEscritas++
                                        }
                                    } else if (blocoAtualDentro) {
                                        writer.write(linha)
                                        writer.newLine()
                                        linhasEscritas++
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    writer.flush()
                }

                if (linhasEscritas == 0) {
                    if (arq15.exists()) {
                        arq15.delete()
                    }
                    mainHandler.post {
                        Toast.makeText(context, "Sem registros nos últimos 15 min", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val authority = "${ctx.packageName}.fileprovider"
                val uris = ArrayList<Uri>()
                uris.add(FileProvider.getUriForFile(ctx, authority, arq15))
                for (img in imagensRecentes) {
                    uris.add(FileProvider.getUriForFile(ctx, authority, img))
                }

                val intent = if (uris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        val clip = ClipData.newUri(ctx.contentResolver, "RotaBot Export 15min", uris.first())
                        for (i in 1 until uris.size) {
                            clip.addItem(ClipData.Item(uris[i]))
                        }
                        clipData = clip
                    }
                } else {
                    val singleUri = uris.first()
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, singleUri)
                        clipData = ClipData.newUri(ctx.contentResolver, "RotaBot Log 15min", singleUri)
                    }
                }.apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(intent, "Exportar últimos 15 min RotaBot").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                mainHandler.post {
                    context.startActivity(chooser)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Erro ao exportar últimos 15 min", ex)
                mainHandler.post {
                    Toast.makeText(context, "Erro ao exportar log: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun limparLog(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        ultimaAssinatura = null
        executor.execute {
            try {
                val arquivo = arquivoLog(ctx)
                if (arquivo.exists()) {
                    arquivo.writeText("")
                }
                val backup = File(arquivo.parentFile, "rotabot_log_antigo.txt")
                if (backup.exists()) {
                    backup.delete()
                }
                val pastaFalhas = diretorioFalhas(ctx)
                pastaFalhas.listFiles()?.forEach { arq ->
                    if (arq.isFile) {
                        arq.delete()
                    }
                }
                mainHandler.post {
                    Toast.makeText(context, "Log limpo", Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Erro ao limpar log", ex)
                mainHandler.post {
                    Toast.makeText(context, "Erro ao limpar log: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
