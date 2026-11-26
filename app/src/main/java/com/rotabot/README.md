# AVISO — VERSÃO ESTÁVEL DA ROTA E BATALHA

## Área protegida: não alterar sem necessidade e sem autorização explícita

A navegação entre pinos e a leitura/pausa de batalha estão em uma versão considerada estável pelo usuário.

O usuário informou que realizou **30 sessões reais**, e que todas funcionaram corretamente na parte de:
- localizar o pino atual;
- caminhar até o pino e reconhecer a chegada;
- avançar para o próximo pino;
- percorrer a sequência de ida e volta;
- pausar a navegação durante a batalha e retomá-la quando não houver mais Pokémon;
- usar o menu/leitura de batalha com o alarme configurado em 30 segundos e os checkpoints.

O menu de batalha apresenta um pequeno atraso de leitura, conhecido e aceito pelo usuário. Isso não deve ser tratado como defeito a corrigir: o usuário deixa o jogo seguir o target, e o atraso não prejudica o funcionamento observado.

**Regra para futuras atualizações**
1. Não refatorar, otimizar, reorganizar ou alterar a lógica de pinos/rota/batalha por iniciativa própria.
2. Não mudar tolerâncias, detecção visual, critérios de chegada, sequência ida/volta, temporizações, confirmação de batalha, pausa ou retomada sem autorização explícita.
3. Se uma tarefa futura exigir mudança nessa área, preservar o comportamento atual e limitar a alteração ao mínimo indispensável.
4. Antes de qualquer mudança autorizada, explicar exatamente o que será alterado e por quê. Depois, compilar e verificar o resultado.
5. Alterações em autoloot ou em outras funções não autorizam mudanças na navegação de pinos ou no menu de batalha.
6. Esta documentação registra uma versão estável validada pelo usuário; não é uma garantia de que toda a aplicação ou funções não testadas estejam livres de problemas.

Referência da versão validada: commit `a5d4111323981cc4e1b71a9ecde8e728c241a230` (26/11/2026).

## Arquivos desta pasta

A proteção acima se aplica especialmente a:
- `Rota.kt`: localização, reconhecimento e avanço entre pinos, ida/volta e integração da pausa durante batalha.
- `BatalhaEngine.kt`: leitura dos Pokémon, confirmação de início/fim da batalha e sinalização para pausa/retomada.
- `BotService.kt`: serviço que executa os ciclos e os controles de pausar/retomar.

Não modificar esses comportamentos sem autorização explícita. A alteração de outros recursos, como autoloot, deve permanecer isolada e não pode mudar a rota ou a batalha.
