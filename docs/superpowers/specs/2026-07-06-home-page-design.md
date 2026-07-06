# Home do Cortex — Design

**Data:** 2026-07-06
**Status:** aprovado em brainstorm (mockup validado pelo usuário)
**Sub-projeto:** 1 de 3 da iniciativa "Home completa"

## Contexto

O encarregado hoje cai direto no workspace de RDOs ao logar. Esta spec define a
nova página **Home**, destino padrão pós-login, inspirada no mockup
"Página 4 - HOME PAGE" (Figma): última obra acessada em destaque, gráfico com
três índices de progressão e filtros, mais cards de apoio.

A iniciativa completa foi decomposta em três sub-projetos, cada um com sua
spec e plano:

1. **Home** (esta spec) — página, navegação, sync de obras e métricas.
2. **Mensagens** — módulo novo na API + página + card "Últimas Mensagens".
3. **Tarefas** — módulo novo na API + página + item na sidebar.

Mensagens e Tarefas aparecem na Home/sidebar desde já com estado "em breve";
os sub-projetos 2 e 3 plugam conteúdo real sem mudar o layout.

## Decisões de produto

| Tema | Decisão |
|---|---|
| Três índices do gráfico | Avanço físico, custo consumido e PDOR — todos normalizados em % (eixo único) |
| PDOR | Receita prevista final da Previsão Financeira ÷ valor contratual da obra. O módulo `pdoc` (custo) fica fora desta iniciativa |
| Filtros do gráfico | Período (3m / 6m / 12m / obra inteira) + legenda clicável liga/desliga séries + seletor de obra no card |
| Chips "Obras Relacionadas" | Em Execução / Concluídas / A Começar / Desativadas + "Filtrar por:" (UF e rodovia) refinam **o seletor de obra** — a Home foca uma obra por vez |
| Última obra acessada | Qualquer interação conta (abrir/editar RDO da obra ou selecioná-la na Home). Persistida localmente (IndexedDB) por colaborador; troca de aparelho começa vazia |
| Offline | Sync completo: métricas e obras entram no pipeline formal de pull; a Home lê exclusivamente do banco local |
| Sidebar | Fusão: Home, RDO, Obras, Equipes, Mensagens (em breve), Tarefas (em breve), Relatórios + rodapé atual (Integrações, Atualizar dados) |
| Topbar | Avatar com menu (nome, CPF mascarado, Sair) substitui o botão flutuante "Sair"; chip de sync permanece |
| Card Atualizações | Eventos operacionais/sync da obra em foco (~5 mais recentes) |
| Card Seu time | Mão de obra do último RDO da obra em foco (função + quantidade) |
| Card Mais Stavias | Links externos para outras plataformas Stavias (nova aba), lista em constante configurável |
| Visual | **Minimalista, no estilo atual do app**: tokens liquid glass existentes, amarelo `#fed203` como cor de ação, sidebar teal. Nenhum paradigma visual novo |

## Arquitetura

### Navegação (react-router + Shell)

- Nova dependência: `react-router-dom`.
- Rotas: `/` → redirect `/home` · `/home` → `HomePage` · `/rdos` → workspace
  de RDOs atual · `/integracoes` → página atual. Sem sessão, qualquer rota
  renderiza `LoginPage` (guard no router, comportamento atual preservado).
- **`CortexShell`** (novo, `apps/web/src/components/shell/`): extrai a sidebar
  de `RdoLocalList.tsx` (recolher, redimensionar e persistência de largura
  preservados) e envolve todas as rotas. Item ativo segue a rota. Mensagens e
  Tarefas clicáveis com badge "em breve"; Obras, Equipes e Relatórios seguem
  placeholders como hoje.
- `RdoLocalList` emagrece: perde a sidebar e vira só o conteúdo da página de
  RDOs. Fluxos de rascunho/formulário não mudam.
- Controles flutuantes (sync chip + avatar) e o botão STAV.IA passam a viver
  no Shell; na Home, o StaviaPanel recebe o contexto da obra em foco.

### Home (`apps/web/src/features/home/`)

Componentes principais:

- **`HomePage`** — orquestra obra em foco, filtros e cards.
- **`ObraFocusCard`** — cabeçalho (nome, código do contrato, cliente, status,
  seletor "Trocar obra"), infos (cidade/UF, rodovia, lat/long quando houver,
  observações, contagem de eventos operacionais da obra nos últimos 30 dias,
  último RDO, progresso geral = último valor mensal do avanço físico) e o
  gráfico.
  Carimbo "dados atualizados em \<data\>" sempre visível.
- **`ProgressChart`** — SVG próprio, zero dependência. Três séries mensais em
  %, linha de referência tracejada em 100%, tooltip com os três valores do
  mês, legenda clicável, pills de período. Séries:
  - Avanço físico = `producaoRealizada ÷ producaoPlanejada`
  - Custo consumido = `custoRealizado ÷ custoPrevistoFinal`
  - PDOR = `receitaPrevistaFinal ÷ valorContratual`
- **Cards inferiores** — `MensagensCard` (estado "em breve"),
  `AtualizacoesCard`, `TimeCard`, `MaisStaviasCard`. Todos reagem à obra em
  foco.

Trocar a obra no seletor atualiza card, gráfico e cards inferiores, e grava a
obra como nova última acessada.

### Dados & sync

**API:**

1. Novos tipos de evento em `cortex_evento_operacional` (canal de pull
   existente; protocolo inalterado):
   - `OBRA_ATUALIZADA` — emitido em criar/alterar/arquivar obra; payload com
     os campos que a Home usa (nome, código do contrato, cliente, cidade/UF,
     rodovia, status, lat/long, valor contratual).
   - `PREVISAO_FINANCEIRA_CALCULADA` — emitido ao calcular snapshot; payload
     apenas com os campos do gráfico (produções, custos, receita prevista
     final, data de referência).
2. Migration: colunas `latitude` e `longitude` (nullable) em `obra`, expostas
   no `ObraResponse`. Preenchimento (import/edição) fica fora desta spec; sem
   valor, o card mostra "—".
3. **Valor contratual** = soma dos itens contratuais da obra, incluído no
   payload do evento e no `ObraResponse`.

**Web:**

4. Novas tabelas IndexedDB: `obras` (espelho local) e `previsaoSnapshots`
   (histórico por obra + data de referência), mais chave
   `ultimaObraAcessada` por colaborador.
5. `pullEvents` ganha handlers para os dois novos tipos (upsert local).
6. **Hidratação inicial**: na primeira carga online, semear via REST
   (`GET /api/obras` + `GET /api/obras/{id}/previsao-financeira/historico`);
   depois, eventos mantêm tudo fresco.
7. **Granularidade mensal**: o gráfico usa o último snapshot de cada mês.

## Erros e estados vazios

- Primeiro login sem obra acessada → card convida a escolher obra no seletor.
- Offline sem dados semeados → estado vazio orientando conectar uma vez.
- Falha na hidratação REST → mantém dados locais com aviso discreto; nunca
  tela branca.
- Obra sem histórico de previsão → mensagem "sem histórico ainda" no lugar do
  gráfico; resto do card funciona.
- Lat/long ausentes → "—".

## Testes

- **Web (Vitest):** normalização das três séries; agregação mensal (último
  snapshot do mês); filtro de período; efeito dos chips sobre o seletor;
  persistência da última obra por colaborador; handlers dos novos eventos de
  pull (upsert em `obras` e `previsaoSnapshots`).
- **API (JUnit, JDK 21):** emissão dos eventos ao salvar obra e ao calcular
  previsão; formato dos payloads; soma do valor contratual a partir dos
  itens contratuais.

## Fora de escopo (desta spec)

- Backends e páginas de **Mensagens** e **Tarefas** (sub-projetos 2 e 3).
- Busca global, engrenagem de configurações, Suporte, Baixar Dados e toggle
  de tema escuro do mockup.
- Páginas reais de Obras, Equipes e Relatórios (continuam placeholders).
- Módulo `pdoc` (previsão de custo) — não alimenta o gráfico da Home.
- Preenchimento de latitude/longitude das obras.
