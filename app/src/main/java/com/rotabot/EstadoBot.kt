package com.rotabot

/**
 * Estado operacional único do serviço. Mantém a interface desacoplada
 * das mensagens de status exibidas no overlay.
 */
enum class EstadoBot {
    PARADO,
    PREPARANDO,
    PRONTO,
    NAVEGANDO,
    PAUSADO,
    ERRO,
    ENCERRANDO
}

object EstadoBotController {
    @Volatile
    var atual: EstadoBot = EstadoBot.PARADO
        private set

    @Synchronized
    fun definir(novo: EstadoBot) {
        atual = novo
    }

    @Synchronized
    fun resetar() {
        atual = EstadoBot.PARADO
    }
}
