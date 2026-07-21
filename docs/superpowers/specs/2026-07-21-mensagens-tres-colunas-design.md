# Mensagens — layout de três colunas — Design

**Data:** 2026-07-21
**Status:** aprovado em brainstorm (referência visual validada pelo usuário)
**Escopo:** apenas `apps/web` — nenhuma mudança de API, schema ou sync

## Contexto

A aba Mensagens hoje está em duas colunas (lista + thread) com o contexto da
conversa em drawer deslizante, no estilo WhatsApp. Essa versão está no working
tree, ainda não commitada.

O usuário trouxe como referência o padrão "Chat App Messenger" (Minimal UI):
três colunas permanentes, carimbo de tempo relativo **fora** da bolha, avatar ao
lado das mensagens recebidas e painel direito com seções recolhíveis
(`INFORMATION ⌄` / `ATTACHMENT ›`).

Esta spec adota a **estrutura** da referência mantendo a linguagem visual do
Cortex: teal de marca, amarelo `#fed203` como cor de ação. Não é uma cópia de
cor — o azul da referência quebraria o padrão usado em Home, Tarefas e sidebar.

## Decisões de produto

| Tema | Decisão |
|---|---|
| Direção visual | Estrutura da referência + cores Cortex. Bolhas próprias em tint teal `#d9ece6` com texto escuro; recebidas em `#f4f6f5` com fio de borda; sem rabicho |
| Terceira coluna | Permanente quando há espaço real, drawer quando não há. Chevron no header da thread recolhe e a escolha é lembrada |
| Presença "online" | Não existe presença no Cortex. O lugar da linha de status recebe o que o app realmente sabe: escopo + nº de participantes + estado de sincronização |
| Carimbo de tempo | Sai de dentro da bolha e vira legenda acima de cada **run** de mensagens, em tempo relativo ("há 5 min") |
| Ticks de sync | Permanecem por mensagem, no canto inferior direito da bolha. São o único retorno de entrega do app — remover custaria informação real |
| Ponto no avatar da lista | Substitui o ponto de presença: âmbar quando a conversa tem mensagem não enviada (`PENDENTE`/`FALHOU`) |
| Busca | Campo arredondado com lupa dentro e limpar `×`, no lugar do label + botão "Buscar" separados |
| Composer | Barra única arredondada: textarea + `🖼` (`accept="image/*"`) + `📎` + botão enviar amarelo, todos dentro da barra |
| Tema escuro | Fora de escopo. Nenhuma outra tela do Cortex tem dark mode |

## Layout

```
main.mensagens-page
├─ header   "Mensagens" · N conversas            [Nova conversa]   ← pill amarelo, inalterado
├─ alert    (erro)
└─ .mensagens-frame                ← container-type: inline-size
   └─ .mensagens-workspace         grid: 340px | minmax(0,1fr) | 320px
      ├─ aside.mensagens-conversations    busca + linhas de conversa
      ├─ section.mensagens-thread         header + runs + composer
      └─ aside.mensagens-context          coluna em fluxo OU drawer
```

### Por que container query e não media query

A sidebar do shell é redimensionável pelo usuário (`sidebarWidth.ts`, ~200–420px
persistidos em `localStorage`). A mesma tela de 1440px pode deixar o workspace
entre ~1000px e ~1240px, então largura de viewport não responde à pergunta
"cabe uma terceira coluna?".

`.mensagens-frame` declara `container-type: inline-size` e as colunas respondem
a `@container (min-width: 1040px)`. Um elemento não pode consultar o próprio
container — daí o wrapper existir separado do grid.

Comportamento por faixa de largura **do frame**:

| Largura do frame | Lista | Thread | Contexto |
|---|---|---|---|
| ≥ 1040px | coluna | coluna | coluna em fluxo (recolhível) |
| 640–1039px | coluna | coluna | drawer sobreposto |
| < 640px | painel único alternado por `mobilePane` (`list` / `thread` / `context`) | | |

640px de frame equivale ao breakpoint de 900px de viewport usado hoje, com a
sidebar na largura padrão — a troca para painel único não muda de momento para
quem já usa o app.

Com 1040px de frame a thread fica com ~380px — mais estreita que hoje. O chevron
de recolher é a saída para quem preferir bolhas largas.

## Runs de mensagem

Estrutura visual por run:

```
                                              há 19 min      ← minha: legenda à direita
                                    ┌─────────────────────┐
                                    │ tudo certo por aqui │
                                    └─────────────────────┘ ✓✓
 (JS)  João Souza · há 2 min                                 ← recebida: avatar + legenda
       ┌──────────────┐
       │ beleza       │      ← bolhas seguintes alinham com a legenda
       └──────────────┘
```

- `buildMessageTimeline` já emite `showAuthor` quando o autor muda. Vira
  `startsRun`, que passa a quebrar também por **intervalo maior que 15 minutos**
  entre mensagens do mesmo autor.
- Legenda só no início do run. Bolhas seguintes recebem apenas o tick.
- Avatar (32px, iniciais) apenas em runs recebidos; runs próprios não têm avatar.
- Em conversa direta a legenda recebida mostra só o tempo; em grupo/obra mostra
  `Nome · tempo`.

### Tempo relativo

Nova função pura `formatRelativeTime(iso, now)` em `mensagensFormat.ts`:

| Diferença | Saída |
|---|---|
| < 60s | `agora` |
| < 60min | `há 5 min` |
| < 24h | `há 3 h` |
| dia anterior | `ontem 14:32` |
| demais | `12/07 14:32` |

Usa `Intl.RelativeTimeFormat("pt-BR", { numeric: "auto", style: "narrow" })` até
24h. `now` é parâmetro para o teste ser determinístico. A página mantém um
`useEffect` com `setInterval` de 60s que atualiza um estado `now`, senão as
legendas congelam com a aba aberta.

## Lista de conversas

Linha: avatar (com ponto âmbar quando há pendência) · nome · `Você: última
mensagem` · tempo relativo à direita. Linha ativa com preenchimento teal suave.

`ConversationPreview` ganha o campo `authorId`. O prefixo "Você:" é calculado na
camada de view comparando com `session.colaboradorId` — comparar por
`authorName` erraria com homônimos.

## Painel de contexto

```
[›]                                    ← recolher (só quando em fluxo)
        ( avatar 84px )
          João Souza
        Conversa da obra

INFORMAÇÃO                          ⌄
  Obra Vila Nova · CT-2024-18
  4 participantes
  Sincronizado há 3 min  /  Offline


PESSOAS (4)                         ⌄
  (JS) João Souza — Administrador
  …

ANEXOS (3)                          ›
  (fechada por padrão; linhas de arquivo, clique baixa)
```

Cada seção é um `<button aria-expanded>` com o conteúdo em região controlada.
Estado local do componente; `INFORMAÇÃO` e `PESSOAS` abertas por padrão,
`ANEXOS` fechada.

A linha de sincronização vem de `useSyncStatus()`
(`lib/sync/useSyncStatus.ts`), que já expõe `lastSyncCompletedAt` e é o que
alimenta o `SyncStatusBanner`. Sem `navigator.onLine` a linha mostra `Offline`;
com `lastSyncCompletedAt` nulo, `Nunca sincronizado`. Nenhum estado de sync
novo é criado para esta tela.

## Composer

Barra única arredondada (raio 24) contendo textarea sem borda, botão de imagem
(`accept="image/*"`), botão de anexo genérico e o círculo amarelo de enviar.
Ambos os inputs de arquivo alimentam o mesmo estado `files` — nenhuma mudança em
`queueMessage` nem em `objectUploadSync`. Dica de offline abaixo da barra.

## Estrutura de código

`MensagensPage.tsx` tem 1247 linhas e concentra estado da página, quatro painéis,
o diálogo de nova conversa, dez ícones e seis formatadores. O redesign toca todos
eles, então a divisão entra junto:

```
MensagensPage.tsx                     orquestração e estado (~350 linhas)
components/ConversationsPane.tsx      busca, lista, resultados de busca
components/MessageThread.tsx          header, runs, bolhas
components/MessageComposer.tsx        barra de composição
components/ConversationInfoPane.tsx   painel direito com seções
components/CreateConversationDialog.tsx
components/icons.tsx
mensagensFormat.ts                    tempo relativo, tamanho de arquivo, iniciais
MensagensPage.css                     permanece arquivo único, re-seccionado
```

O CSS fica em um arquivo só. Espalhá-lo por componente troca um arquivo grande
mas seccionado por risco de ordem de import, sem ganho para este redesign.

Fronteiras: os componentes de painel recebem dados e callbacks por props e não
falam com o repositório. Todo acesso a IndexedDB, sync e API continua em
`MensagensPage.tsx` e nos módulos existentes (`mensagensRepository`,
`mensagensHydration`, `mensagensApi`).

## O que não muda

`mensagensRepository.ts`, `mensagensQueue.ts`, `mensagensApi.ts`,
`mensagensHydration.ts`, `objectUploadSync.ts` e o schema do IndexedDB ficam
como estão. A única mudança fora da camada visual é o campo `authorId` em
`ConversationPreview`.

## Testes

| Alvo | Teste |
|---|---|
| Quebra de run por intervalo > 15 min | `mensagensView.test.ts` (novo caso) |
| `authorId` no preview / prefixo "Você:" | `mensagensView.test.ts` (novo caso) |
| `formatRelativeTime` nas cinco faixas | `mensagensFormat.test.ts` (novo arquivo, `now` fixo) |
| `formatFileSize`, iniciais | `mensagensFormat.test.ts` |
| Regressão | suíte existente de `apps/web` verde |

Verificação visual: Playwright contra o Vite dev server, injetando
`cortex.auth.sessao`, capturando 1440px, 1100px e 390px de viewport — e uma
captura com a sidebar do shell arrastada ao máximo, que é o caso em que a
terceira coluna deve recuar para drawer.

## Riscos

| Risco | Mitigação |
|---|---|
| Thread estreita com três colunas | Chevron de recolher; largura mínima de bolha e `overflow-wrap: anywhere` já presentes |
| `@container` sem suporte | Baseline desde 2023; o app já exige IndexedDB e Service Worker. Sem fallback |
| Divisão em componentes junto do redesign aumenta o diff | Divisão primeiro, sem mudar comportamento, com a suíte verde; redesign visual depois |
| Legendas relativas congeladas | Tick de 60s no estado `now` |
