# Cortex 2.1 — Memória e linguagem institucional

**Data:** 2026-07-16
**Status:** aprovado para implementação
**Escopo:** plataforma autenticada do Córtex Stavias

## Objetivo

Evoluir a interface autenticada para o Cortex 2.1 sem reconstruir a aplicação:
preservar a sidebar, a fonte Poppins, as rotas, os fluxos de domínio e o botão
flutuante da StavIA, enquanto as áreas de conteúdo passam a comunicar uma
plataforma histórica, séria e estabelecida.

A Home recebe as subtabs **Visão geral** e **Memória**. Memória será o único
local da interface em que modificações ontológicas são apresentadas como
auditoria: criação, atualização, conclusão, reabertura, arquivamento, exclusão,
associação e desassociação, sempre com o ator real, a entidade afetada, o
contexto autorizado e a ordem canônica de commit.

## Princípios obrigatórios

1. Não inventar ator, relação, estado anterior, estado novo ou evento.
2. O servidor continua sendo a fronteira de autorização; filtros no navegador
   nunca ampliam o escopo retornado pela API.
3. Alfa pode consultar o registro global. Beta recebe apenas eventos globais
   permitidos e eventos vinculados às obras às quais possui acesso atual.
4. A Memória usa eventos reais de `cortex_evento_operacional` e eventos locais
   reais ainda conhecidos apenas pelo dispositivo. Uma página limitada não
   pode ser descrita como “todo o histórico”.
5. Conteúdo conhecido apenas pelo dispositivo é identificado como local e
   nunca apresentado como uma consulta completa do servidor.
6. Ações e estados operacionais existentes continuam funcionais. A mudança
   visual não altera silenciosamente autenticação, sync, offline, PDOR,
   mensagens, tarefas, financeiro ou StavIA.
7. A sidebar e o launcher/painel da StavIA são preservados. Apenas tokens
   compartilhados de foco, contraste ou movimento podem afetá-los.

## Direção visual

### Conceito

O Córtex assume a linguagem de um **livro-razão operacional de infraestrutura**:
superfícies claras, preto estrutural, divisórias precisas, densidade controlada,
tipografia com menos peso e uma hierarquia baseada em registro, não em cards
decorativos.

A assinatura visual é o **trilho de registro**: uma linha preta vertical ou
horizontal que conecta commit, data e alteração. O recurso é reservado à
Memória e a pequenas marcações estruturais; não vira decoração repetida.

### Tokens de cor

- `--color-ink: #111312` — títulos, barras estruturais e ações de alta ênfase.
- `--color-graphite: #292d2b` — texto secundário forte e superfícies escuras.
- `--color-text: #252a27` — corpo principal.
- `--color-muted: #68706b` — metadados e instruções.
- `--color-canvas: #f1f3f0` — fundo da aplicação.
- `--color-surface: #ffffff` — superfície de trabalho.
- `--color-border: #cfd4d0` — divisórias e contornos.
- `--color-brand-teal: #124e4a` — continuidade da marca e estados de foco.
- `--color-brand-yellow: #f2c800` — ação principal, item ativo e assinatura
  Stavias; não substitui cores semânticas.

O preto não transforma a plataforma em dark mode. Ele cria moldura,
autoridade e contraste em cabeçalhos, divisórias, botões primários escuros e
trilhos de informação.

### Tipografia

- Poppins permanece auto-hospedada e é a única família da aplicação.
- Corpo e controles: peso 400 ou 500.
- Labels e metadados: peso 500; sem caixa alta rastreada como decoração.
- Títulos de página e seção: peso 600.
- Peso 700: somente métricas-chave, alertas ou identidade de marca.
- Peso 800 deixa de ser usado nas superfícies autenticadas, salvo logotipo em
  imagem fornecido pela marca.
- Números de commit, valores e datas usam algarismos tabulares.

### Forma e elevação

- `2px`: divisórias agrupadas e linhas de seleção.
- `4px`: botões, filtros, inputs, linhas clicáveis e painéis densos.
- `6px`: containers principais e diálogos.
- Pill completo: somente status compacto ou agrupamento que depende da forma.
- Cards estáticos não flutuam; borda e contraste separam regiões.
- Uma única sombra neutra é permitida para menus, modais e overlays.

### Movimento

- Transições entre 140 e 180 ms.
- Propriedades permitidas: cor, borda, background, opacidade e deslocamento de
  até 2px.
- Sem bounce, scale, glow, blur animado ou entrada em cascata.
- Troca de subtabs: fade curto e deslocamento vertical máximo de 2px.
- `prefers-reduced-motion: reduce` desliga transições não essenciais.

## Arquitetura da Home

### Navegação interna

A rota `/home` continua sendo o destino pós-login. A página passa a possuir
uma navegação interna acessível:

```text
[ Visão geral ] [ Memória ]
```

- A subtab ativa é representada na URL por `?tab=overview` ou `?tab=memory`.
- Abrir `/home` sem query mostra Visão geral.
- Back/forward do navegador restaura a subtab.
- `HomePage` mantém a responsabilidade de montar o shell e o launcher StavIA.
- `HomeOverview` recebe o dashboard atual.
- `MemoryLedger` isola carregamento, filtros, paginação e apresentação do
  registro ontológico.

### Visão geral

O comportamento atual de seleção de obra, filtros, previsões, mensagens,
financeiro e equipe é preservado. O card “Atualizações” deixa de listar eventos
ontológicos. Em seu lugar, um resumo da Memória informa a quantidade carregada,
o último commit visível e uma ação “Abrir Memória”. O resumo não apresenta a
lista de alterações.

## Memória operacional

### Escopo e autorização

- Alfa: pode consultar eventos globais e eventos de todas as obras.
- Beta: recebe eventos vinculados às obras com acesso atual e eventos globais
  explicitamente permitidos pelo backend.
- Consulta por `obraId` exige acesso à obra.
- Consulta por `rdoId` exige acesso ao RDO.
- IDs fornecidos pelo cliente não substituem a policy do servidor.
- Eventos de governança sensível continuam restritos conforme a policy já
  definida para o domínio.

### Contrato de leitura

`GET /api/ontology/memory` fornece a nova página por cursor. O endpoint atual
`GET /api/ontology/timeline` permanece compatível para históricos de domínio
que ainda o consomem.

Parâmetros:

- `beforeCommitSeq`: cursor exclusivo para a próxima página, ausente na
  primeira leitura.
- `limit`: padrão 50, mínimo 1 e máximo 100.
- `obraId`, `rdoId`, `entityType`, `entityId`, `actorId`, `eventType`,
  `origin`, `result`, `from`, `to`.

Resposta:

```json
{
  "events": [],
  "nextBeforeCommitSeq": 18400,
  "hasMore": true,
  "scope": "GLOBAL|AUTHORIZED_WORKSITES|WORKSITE|RDO",
  "serverTime": "2026-07-16T18:00:00Z"
}
```

Cada evento inclui:

- `id`, `commitSeq`, `type`, `source`;
- entidade principal e entidades relacionadas;
- `obraId`, `rdoId`, `colaboradorId`;
- `occurredAt`, `syncedAt`, `origin`, `syncStatus`, `schemaVersion`;
- `actorId` e `actorName` vindos de `usuario_id` e `colaborador` quando
  disponível;
- `deviceId`, `correlationId`, `causationId`;
- `previousState`, `newState`, `result`, `errorCategory`;
- `payload` sanitizado conforme o contrato atual.

Ator ausente é exibido como “Processo do sistema”. O frontend não deduz nome a
partir de payload, responsável de tarefa ou sessão atual.

### Ordenação e paginação

- Ordem canônica: `commit_seq DESC`.
- Empate impossível para commit; data é informação, não cursor.
- A próxima página usa `commit_seq < beforeCommitSeq`.
- Filtros são aplicados antes do limite.
- A UI concatena páginas sem reordenar nem remover eventos distintos.

### Fontes e cobertura

O ledger combina duas fontes sem confundi-las:

- **Servidor:** eventos com `commitSeq`, autoritativos e paginados pela API.
- **Dispositivo:** eventos de `operational_events` ainda locais, incluindo
  criação, conclusão, reabertura e exclusão de tarefas deste dispositivo.

Eventos locais são filtrados pelas obras autorizadas já presentes no banco
local e pelo usuário da sessão. Eles aparecem com “Commit pendente”, origem
“Este dispositivo” e seu estado real de sincronização. O frontend elimina
duplicidade somente quando `id` é idêntico; nomes, datas ou payloads parecidos
não são usados como heurística.

O cabeçalho informa separadamente a cobertura: último commit do servidor,
quantidade de eventos retornados pela consulta e quantidade de eventos apenas
locais. Assim, um evento local não é apresentado como parte confirmada do
histórico global.

### Filtros

A barra de filtros contém:

- busca textual local sobre a página carregada;
- obra;
- tipo de entidade;
- tipo de evento;
- ator;
- origem;
- resultado;
- intervalo de datas;
- ação “Limpar filtros”.

Filtros estruturais reiniciam o cursor e consultam novamente o servidor.
Busca textual é identificada como busca nos registros carregados, para não
prometer pesquisa global inexistente.

### Linha de registro

Cada linha mostra, na ordem:

1. commit;
2. data e hora;
3. ator;
4. verbo e entidade em linguagem direta;
5. obra/RDO relacionado;
6. resultado e sincronização.

O evento pode ser expandido por botão com `aria-expanded`. O detalhe contém:

- relações afetadas;
- estado anterior e novo em diff por campo;
- origem, fonte, dispositivo e correlação;
- payload técnico em `<details>` secundário.

Valores sensíveis não são reintroduzidos pelo frontend. O componente apresenta
somente os campos recebidos do endpoint autorizado ou gravados pelo evento
local real, sem reconstrução heurística.

### Linguagem dos eventos

Um catálogo puro no frontend traduz tipos conhecidos para ações:

- `RDO_CRIADO` → “Criou o RDO”.
- `RDO_EDITADO` → “Editou o RDO”.
- `TAREFA_CRIADA` → “Criou a tarefa”.
- `TAREFA_CONCLUIDA` → “Concluiu a tarefa”.
- `TAREFA_REABERTA` → “Reabriu a tarefa”.
- `TAREFA_EXCLUIDA` → “Excluiu a tarefa”.

Tipos desconhecidos permanecem consultáveis e são formatados sem inventar
semântica, por exemplo `PAPEL_ACESSO_ALTERADO` → “Papel acesso alterado”.

### Estados da interface

- Inicial: skeleton tabular discreto.
- Vazio real: “Nenhuma modificação ontológica corresponde aos filtros.”
- Erro: mensagem direta, status preservado e ação “Tentar novamente”.
- Offline: mostra apenas eventos reais armazenados no dispositivo e informa
  que o registro do servidor está indisponível.
- Offline sem eventos locais: explica que a consulta do servidor exige conexão.
- Página seguinte: botão “Carregar registros anteriores”; sem infinite scroll.

## Centralização dos históricos

“Somente na Memória” significa que listas de eventos de auditoria ontológica
deixam de ser renderizadas em outras tabs.

### Remover das outras tabs

- Home/Visão geral: lista `Atualizações`.
- RDO: `timeline-panel` e contagens cuja função exclusiva seja abrir a lista de
  eventos ontológicos.
- Obras: seção `Rastreabilidade Cortex`.
- Equipes: seção `Histórico ontológico`.

Essas telas podem conter um link “Abrir na Memória” com filtros na query string.
O link não mostra eventos fora da Memória.

### Preservar como estado de domínio

- ex-membros e vigências da equipe;
- parcelas, pagamentos, rateios e outros registros financeiros necessários à
  operação;
- histórico de mensagens;
- versões do RDO necessárias a conflito e sync;
- previsão financeira e PDOR.

Esses elementos descrevem o objeto operacional. A auditoria de quem modificou
o objeto permanece exclusivamente na Memória.

## Refinamento por tab

### Home

- Subtabs institucionais com linha ativa preta e marca amarela curta.
- Cabeçalho mais compacto e títulos em peso 600.
- Obra em foco continua sendo a maior superfície.
- Cards secundários passam a módulos alinhados por borda, sem raio grande.

### RDO

- Lista e detalhe assumem linguagem de registro técnico.
- Métricas deixam grandes blocos amarelos e usam números alinhados em branco.
- Ações primárias usam amarelo ou preto conforme hierarquia; botões têm 4px.
- Timeline ontológica sai da tab e é substituída por link filtrado.

### Obras

- Catálogo/detalhe permanece em duas colunas.
- Cabeçalho, fatos e PDOR usam divisórias, não cards flutuantes.
- `Rastreabilidade Cortex` é removida e substituída por link para Memória.
- O botão da StavIA preserva sua forma atual.

### Equipes

- Catálogo vira diretório institucional com linhas mais retas e pesos menores.
- Histórico de membros permanece.
- Histórico ontológico sai e é substituído por link filtrado para a equipe.

### Mensagens

- Três painéis permanecem.
- Divisórias ficam mais escuras, avatares mais quadrados e bolhas mais contidas.
- Metadados usam peso 400/500; ações usam 500/600.

### Tarefas

- Tabs de equipe deixam de usar bloco amarelo cheio como decoração.
- Prioridade usa sinal semântico compacto; cards viram linhas de trabalho.
- Conclusão e exclusão continuam gerando os eventos existentes.
- A lista de eventos não é repetida na tab.

### Financeiro

- Mantém a estrutura densa atual, alinhando cores, pesos, raios e transições.
- Históricos financeiros necessários ao controle permanecem como domínio.
- Auditoria ontológica é acessada na Memória.

### Integrações

- Integrações são apresentadas como registro de serviço: nome, fonte, último
  estado conhecido, última execução e ação.
- Estados sem dados permanecem explícitos.

## Responsividade e acessibilidade

- Desktop: ledger tabular com colunas estáveis.
- Abaixo de 900px: filtros quebram em duas linhas e detalhes ocupam a largura.
- Abaixo de 640px: cada evento vira bloco semântico, mantendo commit, ator,
  ação, data e estado visíveis.
- Nenhuma página cria scroll horizontal no viewport; tabelas internas podem ter
  wrapper identificado quando não houver alternativa.
- Navegação de subtabs usa `role="tablist"`, `role="tab"` e `role="tabpanel"`.
- Foco visível com contraste suficiente sobre preto, branco, verde e amarelo.
- Controles somente com ícone preservam nome acessível.
- Touch targets têm pelo menos 40px quando praticável.

## Offline e dados locais

Páginas do servidor não são persistidas em um novo cache nesta entrega, pois o
banco local atual não particiona um cache de auditoria por identidade. A
Memória offline consulta somente `operational_events`, que já contém eventos
operacionais criados pelo dispositivo.

Antes de renderizar um evento local, a UI restringe `obraId` ao conjunto de
obras locais da sessão. Eventos globais locais só aparecem quando pertencem ao
ator atual. Essa defesa não transforma IndexedDB em fronteira de autorização;
ela apenas evita misturar registros de outra sessão no modo offline.

Quando a rede retorna, a API é consultada novamente. A UI não afirma que um
evento local alcançou o servidor enquanto não existir confirmação real.

## Testes e verificação

### API

- Teste de mapeamento de ator, source, correlação, estados e resultado.
- Teste de paginação exclusiva por `beforeCommitSeq`.
- Teste de combinação de filtros.
- Testes de autorização Alfa, Beta por obra e negação fora de escopo.
- Teste de evento sem ator como processo do sistema.

### Web

- Testes puros para query da subtab, catálogo de ações, filtros e diff.
- Teste do cliente da Memória, merge com eventos locais e normalização sem
  campos inventados.
- Testes de preservação dos links filtrados nas tabs de domínio.
- Teste que as antigas listas ontológicas não são mais renderizadas fora da
  Memória.
- Testes existentes de Home, RDO, Obras, Equipes, Mensagens, Tarefas,
  Financeiro, sync e StavIA permanecem verdes.

### Verificação final

1. `npm test` em `apps/web`.
2. `npm run lint` em `apps/web`.
3. `npm run build` em `apps/web`.
4. Java 21: `./mvnw test` em `apps/api`.
5. Screenshots autenticados desktop e mobile de Home/Visão geral, Memória,
   RDO, Obras, Equipes, Mensagens, Tarefas, Financeiro e Integrações.
6. Exercício manual de subtabs, filtros, paginação, expansão, links filtrados,
   sidebar expandida/recolhida e launcher StavIA.

A baseline atual da API possui um erro preexistente:
`EmailConfigurationTest.applicationUsesAuthoritativeSmtpEnvironmentNames`
tenta ler o arquivo ausente
`docs/superpowers/plans/2026-07-13-auth-security-and-finance-permissions.md`.
Essa deficiência deve ser reportada separadamente; testes novos e escopo
afetado precisam ficar verdes, e o erro não pode ser atribuído ao Cortex 2.1.

## Critérios de aceite

- Preto participa da hierarquia sem transformar o produto em console escuro.
- Poppins permanece, com redução material de pesos 700/800.
- Containers e botões são mais quadrados e consistentes.
- Sidebar e StavIA permanecem reconhecíveis e funcionais.
- Todas as tabs autenticadas compartilham a linguagem institucional.
- Home contém Visão geral e Memória com URL restaurável.
- Memória é o único lugar que lista modificações ontológicas.
- Eventos apresentam ator real ou “Processo do sistema”, nunca uma inferência.
- Alfa e Beta recebem somente o escopo autorizado pelo servidor.
- O ledger pagina todo o histórico disponível sem limite silencioso de 500.
- Criação, edição, conclusão, reabertura e exclusão de RDO/tarefa são legíveis.
- Estados anterior/novo e relações são consultáveis quando registrados.
- Offline e eventos locais são honestos sobre cobertura e identidade.
- Navegação, responsividade, foco e reduced motion são verificados.
- Testes, lint, build e evidências visuais recentes sustentam a entrega.
