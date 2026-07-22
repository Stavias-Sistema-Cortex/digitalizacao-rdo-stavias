# Córtex 2.1 — interface institucional, ontologia íntegra e offline total

## Status

Design aprovado em 17 de julho de 2026. A execução será incremental, preservará a arquitetura atual e não reescreverá o produto do zero.

## Objetivo

Transformar o Córtex em uma plataforma operacional mais séria, estabelecida e documental, enquanto todas as tabs passam a operar offline e a sincronizar automaticamente. Cada alteração deve ser rastreável do dispositivo ao servidor e produzir um evento ontológico consultável exclusivamente em `Home > Memória`.

## Decisões aprovadas

- Offline total, incluindo gestão administrativa e solicitações de integração.
- Sincronização automática; nenhum fluxo dependerá de um botão manual para terminar.
- Conciliação por campo: mudanças em campos diferentes são mescladas automaticamente.
- Conflitos no mesmo campo preservam as duas versões e vão para `Memória > Revisão necessária`.
- Implementação foundation-first, seguida de migração tab por tab.
- Financeiro permanece focado somente em receita.
- O login usa o lockup oficial `STAVIAS | CORTEX`.
- O efeito `ink-reveal` não faz parte deste trabalho.
- Não serão criados dados fictícios para preencher estados indisponíveis.

## Escopo funcional

O trabalho cobre:

- Home — Visão geral e Memória;
- RDO;
- Obras e Gestão de obras;
- Equipes;
- Mensagens;
- Tarefas;
- Financeiro;
- Integrações;
- Segurança do dispositivo e acesso offline;
- Login e shell global;
- IndexedDB, outbox, sincronização web/API e projeção ontológica;
- provas automatizadas e de navegador para o caminho offline real.

## Estratégia de execução

A implementação seguirá esta ordem:

1. estabelecer invariantes e contratos comuns;
2. validar migração não destrutiva do armazenamento local;
3. consolidar coordenador de mutações e sincronização automática;
4. fechar cobertura ontológica e política de conflitos;
5. migrar operações de cada tab para o contrato comum;
6. aplicar o sistema visual institucional e o tratamento específico das tabs;
7. executar prova integrada online → offline → reconexão → conciliação.

## Invariantes do sistema

1. Nenhuma mutação de domínio existe sem autoria, dispositivo, escopo e `clientMutationId`.
2. Nenhuma mutação local é considerada persistida antes de dado, outbox e evento operacional serem gravados atomicamente.
3. Nenhum evento ontológico pode apontar para uma entidade inexistente sem declarar explicitamente que se trata de criação pendente.
4. Reenvios com o mesmo `clientMutationId` são idempotentes.
5. O servidor revalida autorização, versão-base e integridade relacional antes de aplicar a mutação.
6. Rejeições e conflitos nunca apagam o payload local original.
7. A Memória é o único módulo que lista o histórico ontológico completo.
8. Outras tabs podem mostrar apenas origem, ID, estado atual e link para a Memória.
9. Toda operação aceita, rejeitada, conciliada ou descartada produz resultado auditável.
10. Backend e frontend usam definições ontológicas compatíveis e verificadas por teste de paridade.

## Fundação offline

### Coordenador de mutações

Todas as telas escreverão por um coordenador comum. Ele receberá uma intenção de domínio, validará o grant local e abrirá uma transação IndexedDB envolvendo:

- o registro canônico local;
- a mutação na outbox;
- o evento operacional imutável;
- anexos e dependências, quando existirem;
- o estado local da entidade.

O envelope mínimo da mutação será:

```text
clientMutationId
entityType
entityId
operation
baseVersion
fieldPatch
actorId
deviceId
authorizationScope
createdAt
dependencies[]
payloadHash
ontologyEventId
```

O `payloadHash` ajuda a detectar reuso incorreto de um identificador idempotente. `ontologyEventId` vincula o pedido local ao evento exibido posteriormente na Memória.

### Estados operacionais

Todas as tabs usarão o mesmo conjunto de estados:

- `LOCAL` — persistido somente no dispositivo;
- `PENDENTE` — pronto para envio;
- `SINCRONIZANDO` — em processamento;
- `SINCRONIZADO` — confirmado pelo servidor;
- `CONFLITO` — requer conciliação;
- `REJEITADO` — recusado por autorização ou regra de domínio.

Estados específicos legados poderão ser traduzidos na camada de visualização, mas não criarão semânticas paralelas.

### Disparos automáticos

A sincronização será solicitada:

- imediatamente depois de uma mutação local quando houver rede e sessão compatível;
- no evento `online`;
- na abertura do aplicativo;
- em `visibilitychange` quando o aplicativo voltar ao primeiro plano;
- em intervalo controlado enquanto houver fila;
- depois de desbloqueio offline com grant válido;
- depois de o usuário resolver um conflito.

Um lock por dispositivo impedirá duas execuções concorrentes. Falhas transitórias usarão backoff com jitter. O processamento será retomável e respeitará dependências topológicas; uma operação bloqueada não interromperá mutações independentes.

### Operações externas

Ações que dependem de terceiros, como testar uma integração, não serão simuladas offline. O pedido será persistido no estado `PENDENTE`, com o motivo `AGUARDANDO_REDE`, mostrado imediatamente e executado automaticamente após a reconexão. Resultado, horário, provedor e erro serão registrados na Memória.

## Segurança offline

- Operações offline usarão grant assinado, com identidade, dispositivo, escopos e validade.
- O grant permitirá validação local sem armazenar segredo reutilizável em texto aberto.
- O servidor sempre revalidará o escopo ao sincronizar.
- Uma permissão revogada durante o período offline fará a mutação mudar para `REJEITADO`; o conteúdo local será preservado para auditoria e possível correção.
- Alterações administrativas de maior risco carregarão versão-base e serão sempre submetidas a conciliação no servidor.
- O cache local permanecerá isolado por identidade e dispositivo.

## Política de conflitos

O servidor comparará a versão-base da mutação com a versão atual:

1. sem concorrência: aplica a mutação;
2. alterações concorrentes em campos distintos: mescla e emite evento `CONCILIADA_AUTOMATICAMENTE`;
3. alteração concorrente no mesmo campo: preserva versão local e remota, marca `CONFLITO` e cria item em `Memória > Revisão necessária`;
4. violação relacional ou de permissão: marca `REJEITADO` com motivo estruturado;
5. repetição idempotente: retorna o resultado anterior sem duplicar eventos.

A resolução manual criará nova mutação, nunca editará retroativamente o evento original.

## Integridade ontológica e Memória

### Evento operacional

Cada evento deverá conter:

- ID imutável;
- tipo de entidade e ID da entidade;
- ação canônica;
- autoria e dispositivo;
- obra ou escopo organizacional;
- data do cliente e data confirmada pelo servidor;
- versão anterior e nova versão;
- origem online ou offline;
- `clientMutationId` e cadeia de correlação;
- resultado da sincronização;
- campos alterados ou resumo seguro do patch;
- relação com evento anterior, quando aplicável.

### Cobertura

Um catálogo único relacionará operações aceitas, handlers de sincronização e publishers ontológicos. Testes deverão falhar se:

- uma operação registrada não tiver handler;
- um handler mutável não produzir evento;
- uma relação declarada não tiver publisher ou uso de runtime justificado;
- os JSONs de ontologia web/API divergirem;
- um evento referenciar ator, obra ou entidade fora do escopo autorizado.

### Memória

`Home > Memória` será um ledger técnico com:

- filtros por entidade, ação, pessoa, obra, dispositivo, origem e resultado;
- alternância entre visão consolidada e eventos somente do dispositivo;
- seção `Revisão necessária` para conflitos;
- detalhe com versões local e remota;
- ação de conciliação para usuários autorizados;
- exportação preservando IDs e cadeia de correlação.

Cards e páginas de domínio mostrarão somente uma referência curta e um link para o evento correspondente.

## Sistema visual institucional

### Paleta e tipografia

- Fonte: Poppins.
- Corpo e dados: pesos `400` e `500`.
- Títulos e decisões críticas: peso máximo `600`.
- Preto e verde estruturam navegação, cabeçalhos e hierarquia.
- Amarelo é semântico: seleção, atenção, ação primária e marca-texto; não será decoração generalizada.
- Valores e IDs usam numerais tabulares quando disponível.

### Geometria

- Molduras completas de `1px` ou `2px`.
- Raios entre `2px` e `4px` para controles e containers.
- Sem faixas grossas isoladas no topo.
- Sem glassmorphism, brilho, gradientes ornamentais ou sombras volumosas.
- Pills ficam restritas a estados que realmente exigem uma etiqueta compacta; o formato padrão será retangular.

### Estrutura de página

Cada módulo usará:

1. cabeçalho com contexto, título, escopo, última sincronização e ação principal;
2. barra de consulta e filtros;
3. área operacional principal em tabela, lista técnica, documento ou workspace;
4. painel contextual para detalhe e ações;
5. referência curta de rastreabilidade com link para a Memória.

O sistema privilegiará tabelas, registros, listas densas e definição de dados sobre mosaicos de cards.

### Movimento e estados

- Transições entre `140ms` e `180ms`.
- Sem bounce, parallax ou movimento ornamental.
- `prefers-reduced-motion` elimina animações não essenciais.
- Vazios, erros e bloqueios fornecem instrução operacional direta.
- O estado global de sincronização mostra conexão, pendências, conflitos e última confirmação.

## Tratamento por tab

### Home — Visão geral

Será um painel de comando, com situação das obras, exceções, pendências, receita prevista e estado da sincronização. O conteúdo virá da projeção local mais recente, identificará a idade dos dados e evitará cards redundantes.

### Home — Memória

Adotará ledger imutável, filtros técnicos e revisão de conflitos. Continuará sendo o único lugar que lista todas as modificações da ontologia.

### RDO

Terá aparência de documento operacional, índice lateral, completude por seção, validações e versão. Salvamento e anexos serão locais e atômicos. A sincronização nunca impedirá continuar o preenchimento.

### Obras

Usará workspace mestre–detalhe para cadastro, contrato, localização, geometria, PDOR e vínculos. Geometrias e mapas já consultados ficarão disponíveis offline. Tiles nunca armazenados terão estado explícito de indisponibilidade, sem mapa ou coordenada inventada.

### Equipes

Será um registro de pessoas, funções, obras vinculadas, vigência e histórico. Alterações offline serão aplicadas otimisticamente, identificadas como pendentes e revalidadas pelo servidor.

### Mensagens

Manterá o workspace de conversas com linguagem mais sóbria. Busca, histórico e anexos locais continuarão disponíveis. Mensagens e criação de conversa entrarão na fila automaticamente, com estado discreto por item.

### Tarefas

O padrão será registro operacional com responsável, prazo, criticidade, dependências e origem. Uma visão de quadro poderá existir como alternativa, mas não será a apresentação principal. Criação, edição, conclusão e reatribuição funcionarão offline.

### Financeiro

Permanecerá focado exclusivamente em receita. Exibirá contrato, previsto, realizado, desvio, PDOR e origem do valor. Compras, notas fiscais, pagamentos e cobranças não voltarão à navegação.

### Integrações

Mostrará provedor, escopo, última execução, cursor e falhas. Solicitações offline serão registradas como `PENDENTE`, com motivo `AGUARDANDO_REDE`, e executadas automaticamente ao reconectar.

### Gestão de obras

Usará tabelas administrativas para papéis, vínculos e vigências. Mudanças offline aparecerão imediatamente, porém identificadas como pendentes até revalidação.

### Segurança

Mostrará dispositivos autorizados, validade do grant, último uso e revogação. Operações críticas terão linguagem explícita e confirmação proporcional ao impacto.

### Login

Usará o lockup oficial `apps/web/src/assets/login/cortex-logo.png`, com texto alternativo `Stavias Córtex`, sem remontagem da marca em CSS. O desenho institucional aprovado será preservado.

## Erros e recuperação

- Falhas transitórias não alteram a mutação para erro terminal na primeira tentativa.
- Falhas permanentes exibem código, motivo seguro e ação possível.
- A fila não será apagada por logout, atualização do aplicativo ou migração de schema.
- Atualizações de IndexedDB serão testadas a partir das versões ainda encontradas em produção.
- Anexos serão verificados por hash antes e depois do envio.
- Cursores de pull e ack avançarão somente depois da aplicação local atômica.
- Uma falha na projeção visual não poderá marcar uma mutação como sincronizada.

## Migração e compatibilidade

- As stores e dados existentes serão preservados.
- Repositórios já usados por RDO, Mensagens, Equipes e Financeiro serão adaptados ao contrato comum em vez de substituídos de uma vez.
- Novas stores ou índices serão adicionados por migração versionada e idempotente.
- Registros legados serão normalizados somente quando houver evidência suficiente; IDs ou vínculos não serão inferidos para fazer a sincronização passar.
- O service worker continuará desativado no Vite de desenvolvimento e será provado em build/preview de produção.

## Estratégia de testes

### Web

- testes de contrato do envelope e catálogo de operações;
- testes de transação atômica em IndexedDB;
- testes de migração preservando bases anteriores;
- testes de ordenação por dependência, idempotência, backoff e retomada;
- testes de conciliação por campo;
- testes de repositório por tab;
- testes de paridade ontológica web/API;
- testes de UI para estados offline, pendente, conflito e rejeitado;
- lint, suíte Vitest e build de produção.

### API

- cobertura handler ↔ operação ↔ publisher;
- autorização e escopo por obra, usuário e dispositivo;
- idempotência e conflito usando banco real descartável;
- integridade relacional dos eventos;
- compatibilidade de migrações;
- testes de pull, push e ack após falhas parciais.

### Runtime

O caminho de aceite no navegador será:

1. abrir preview de produção online;
2. autenticar e sincronizar todas as tabs;
3. confirmar service worker e stores do IndexedDB;
4. cortar a rede;
5. recarregar e desbloquear offline;
6. navegar e executar ações em cada tab;
7. confirmar dados, outbox e eventos locais;
8. restaurar a rede;
9. observar sincronização automática sem botão manual;
10. confirmar estado final no servidor e na Memória;
11. provocar conflito concorrente e validar a revisão necessária.

## Critérios de aceite

- Todas as rotas protegidas abrem offline depois da preparação inicial do dispositivo.
- Toda ação de domínio autorizada pelo grant vigente pode ser registrada offline; ações externas aguardam rede sem serem simuladas.
- A reconexão inicia sincronização automaticamente.
- Reiniciar ou recarregar o navegador não perde fila, anexos ou conflitos.
- Operações independentes continuam quando outra falha.
- Nenhuma sobrescrita conflitante ocorre silenciosamente.
- Toda mutação tem evento ontológico correlacionado e verificável.
- A Memória é completa e exclusiva como histórico global.
- O Financeiro continua somente com receita.
- Nenhum dado fictício é exibido para esconder ausência ou falha.
- A interface segue os tokens institucionais em desktop e mobile.
- O login mostra o lockup oficial `STAVIAS | CORTEX`.
- Lint, testes, build, banco descartável e prova de navegador ficam verdes antes da conclusão.

## Fora de escopo

- Reescrever o produto do zero.
- Reintroduzir compras, notas, pagamentos ou cobranças na navegação Financeiro.
- Copiar conteúdo privado para serviços externos.
- Simular sucesso de integração quando não há rede.
- Adicionar o efeito visual `ink-reveal` ao login.
