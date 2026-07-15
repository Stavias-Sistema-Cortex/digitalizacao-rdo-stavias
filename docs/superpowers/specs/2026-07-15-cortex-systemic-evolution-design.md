# Evolução Sistêmica do Córtex — Design Integrado

**Data:** 2026-07-15  
**Status:** direção aprovada pelo briefing de execução  
**Escopo:** Mensagens, Equipes, Mapas, memória operacional, PDOR e StavIA

## Objetivo

Transformar Mensagens, Equipes e Mapas em domínios reais do Córtex e ampliar
PDOR/StavIA sem criar subsistemas paralelos. Todas as novas mutações devem
compartilhar autenticação, escopo por obra, outbox, cursor `commit_seq`, memória
operacional, evidências, timeline e mecanismos de observabilidade já existentes.

O briefing usa o nome histórico PDOC. A fonte de verdade atual do produto é
PDOR: previsão probabilística de receita final da obra. Os requisitos de
rastreabilidade, explicabilidade, versionamento e integração serão aplicados ao
PDOR existente, sem reintroduzir semântica de custo.

## Estado inicial confirmado

- Backend Spring Boot 3.3.5/Java 21, MySQL 8.4 e Flyway com migrations V1–V26.
- Frontend React 19/TypeScript 6/Vite 8, PWA, IndexedDB `cortex-web` v9 e
  sincronização automática.
- Autorização Alfa/Beta centralizada em `CurrentUserService`, com Beta limitado
  a vínculos ativos em `vinculo_colaborador_obra`.
- Sync idempotente por `(dispositivo_id, client_mutation_id)`, pull ordenado por
  `cortex_evento_operacional.commit_seq` e aplicação local atômica.
- O sync push e o reconciliador web ainda são acoplados a RDO.
- Equipes e Mensagens aparecem na navegação, mas não possuem rotas/domínios
  funcionais. Equipes em Tarefas são nomes derivados de RDOs e estado manual.
- Coordenadas de obra existem, porém não há renderizador de mapas, geometria
  versionada ou provider configurável.
- Anexos de RDO mantêm o `Blob` apenas no IndexedDB; o backend persiste
  metadados com `storage_ref=indexeddb:*`, não o arquivo.
- `cortex_*` é a memória operacional autoritativa usada por sync, timeline e
  StavIA principal. `ontology_*` é um read model secundário, hoje atualizado sob
  demanda pela rota legada de raciocínio.
- PDOR já possui snapshots imutáveis, idempotência, versões, origens, warnings,
  drivers, confiança, calibração explícita, histórico e evidência StavIA.
- Linha de base: web com 141 testes e build PWA aprovados; API com 399 testes
  aprovados, 6 pulados e build aprovado no JDK 21.

## Estratégias consideradas

### 1. Big-bang sistêmico

Alterar todos os schemas, serviços e telas antes de integrar. O estado final
seria uniforme, mas a janela sem software utilizável seria longa e os riscos de
regressão em sync/autorização seriam difíceis de isolar.

### 2. Camadas horizontais

Finalizar primeiro todo o banco, depois todo o backend e por último o frontend.
Facilita revisão por camada, mas deixa contratos não exercitados por fluxos
reais durante grande parte da execução.

### 3. Fatias verticais sobre uma fundação compartilhada — escolhida

Generalizar primeiro os pontos estritamente necessários de sync, memória e
arquivos. Em seguida entregar Equipes, Mensagens e Mapas como fatias completas,
cada uma com migration, policy, serviço, API, IndexedDB quando aplicável,
ontologia, StavIA, UI e testes. O fechamento fortalece PDOR e consolida o read
model ontológico. Cada etapa deixa comportamento utilizável e verificável.

## Princípios arquiteturais

1. `CurrentUserService` continua sendo a decisão central de identidade,
   Alfa/Beta e acesso à obra.
2. O serviço de domínio valida autorização antes de ler ou alterar dados; o
   controller e o frontend adicionam defesa e UX, não substituem a policy.
3. `cortex_objeto`, `cortex_relacao`, `cortex_evento_operacional` e
   `cortex_estado_entidade` são a memória autoritativa.
4. `ontology_*` passa a ser projeção consultável da memória autoritativa e dos
   registros de domínio, nunca uma origem concorrente de verdade.
5. Toda operação offline usa um UUID estável criado no cliente, uma
   `clientMutationId` única e processamento idempotente no servidor.
6. Eventos são estruturados: estados anterior/posterior, campos alterados,
   ator, origem, dispositivo, correlação, causa, versão, relações e escopo.
7. Arquivos binários não trafegam dentro do JSON do sync. Metadados e conteúdo
   têm pipelines separados, ambos autenticados e idempotentes.
8. Dados ausentes permanecem ausentes. Nenhum mapa, equipe, mensagem, previsão,
   permissão ou evidência é fabricado para preencher a interface.
9. Toda copy visível permanece em português.
10. Novos módulos de frontend são carregados por rota para não agravar o bundle
    principal, que já excede o limiar de tamanho do Vite.

## Decomposição da entrega

### Incremento 1 — Fundação de mutações e Equipes

Generaliza o sync por handlers de operação e entrega Equipes de ponta a ponta.
A expansão do dispatcher ocorre junto de um consumidor real, evitando uma
abstração sem uso.

### Incremento 2 — Mensagens e armazenamento protegido

Entrega conversas, mensagens, referências ontológicas, recibos, blobs locais,
upload autenticado, retry e UX responsiva.

### Incremento 3 — Mapas e ontologia geoespacial

Entrega MapTiler Satellite, contrato de provider, adapter Mapbox preparado,
geometrias reais e timeline geográfica.

### Incremento 4 — Consolidação da memória, PDOR e StavIA

Completa a projeção ontológica, adiciona evidências de Equipes/Mensagens/Geo ao
snapshot e knowledge sources, e fecha lacunas de rastreabilidade do PDOR.

### Incremento 5 — Segurança, UX, E2E e documentação

Valida toda a matriz Alfa/Beta, offline/reconciliação, uploads, mapas, PDOR e
StavIA em MySQL descartável e navegador real; atualiza runbooks e relatório.

## Fundação de sync extensível

### Backend

`SyncService` deixa de converter toda mutação em `RdoResponse`. Um contrato
`SyncMutationHandler` define:

- operação e tipo de entidade suportados;
- validação de payload;
- resolução do escopo de obra antes da leitura;
- requisito de `baseVersao`;
- aplicação transacional;
- tipo/id/versão/commit resultantes;
- payload de reconciliação sanitizado.

Handlers iniciais preservam as três operações RDO atuais. Handlers de Equipes e
Mensagens são adicionados nos seus incrementos. O registro de mutação pendente e
o bookkeeping terminal continuam em transações separadas para não perder
`ERRO`/`DESCARTADA` quando o trabalho de domínio sofre rollback.

O CHECK de `sync_mutacao_cliente.operacao` é ampliado por migration aditiva.
O cursor canônico permanece `commit_seq`; nenhuma sequência paralela será
criada.

### Frontend

O `OutboxMutationRecord` torna `entidadeTipo` e `operacao` uniões extensíveis.
O reconciliador usa handlers locais por operação para atualizar o store correto.
O loop de push mantém ordenação por `criadaNoClienteEm`, exclusão mútua e
recuperação de `SYNCING` interrompido.

Retry automático usa backoff exponencial persistido por item, com jitter curto
e teto. Erros permanentes de validação/autorização ficam em `ERROR` até ação do
usuário; indisponibilidade/rede retorna a `PENDING`. Conflitos de entidades
versionadas ficam em `CONFLICT` e nunca são sobrescritos silenciosamente.

## Domínio Equipes

### Persistência

Novas tabelas:

- `equipe`: id, obra principal, nome, descrição, status, líder opcional,
  validade, autoria, timestamps, arquivamento e `versao_linha`.
- `funcao_operacional`: catálogo configurável com código, nome, descrição,
  ativo, ordenação e auditoria. Não usa enum rígido para cargos de operação.
- `equipe_membro`: participação temporal entre equipe e colaborador, função,
  responsável/líder, início, fim, status, motivo, autoria e versão.
- `equipe_obra`: vínculo temporal para permitir realocação sem apagar o
  histórico; a obra principal é uma conveniência do estado atual.

Constraints impedem dois vínculos ativos idênticos do mesmo membro/equipe e
duas ligações atuais duplicadas da mesma equipe/obra. Encerrar participação
preenche `fim_em`/status; nunca remove a linha histórica.

`vinculo_colaborador_obra` continua sendo concessão de acesso. Participação em
equipe não concede acesso implicitamente. Ao adicionar um Beta, a operação Alfa
pode criar/reativar explicitamente o vínculo de acesso na mesma transação apenas
quando o request confirmar essa intenção.

### API

- `GET /api/equipes` com paginação e filtros `obraId`, texto, função, status e
  período; Alfa pode consultar todas, Beta precisa informar/estar no escopo.
- `GET /api/equipes/{id}` e subrecursos de membros, obras e histórico.
- `POST /api/equipes`, `PUT /api/equipes/{id}` e
  `POST /api/equipes/{id}/arquivar`, exclusivos Alfa.
- `POST /api/equipes/{id}/membros`,
  `PUT /api/equipes/{id}/membros/{participacaoId}` e endpoint de encerramento,
  exclusivos Alfa e com controle otimista.
- `GET /api/funcoes-operacionais`; manutenção do catálogo exclusiva Alfa.
- Endpoint explícito para alterar `papel_acesso`, com confirmação, motivo,
  prevenção de autorrebaixamento acidental e auditoria rigorosa.

Listas retornam DTOs paginados, não entidades JPA. Todas as buscas por ID
resolvem a obra antes de retornar dados, impedindo IDOR.

### Offline

Leitura de equipes autorizadas é projetada no IndexedDB. Operações
administrativas offline ficam habilitadas apenas para sessão Alfa previamente
autenticada e carregada, usam `baseVersao` e exibem conflito explícito. Ações de
papel Alfa/Beta permanecem online-only por serem sensíveis e exigirem validação
atual do servidor.

### Memória operacional

Objetos `EQUIPE`, `PARTICIPACAO_EQUIPE` e `FUNCAO_OPERACIONAL`; relações
`ATUA_EM`, `MEMBRO_DE`, `EXERCE_FUNCAO`, `LIDERA`, `POSSUI_ACESSO`; eventos
estruturados de criação, alteração, arquivamento, entrada, saída, função,
responsabilidade, acesso, conflito e resolução.

## Domínio Mensagens

### Persistência

Novas tabelas:

- `conversa`: id, tipo, título opcional, obra/equipe opcional, criador, status,
  última atividade, versão e timestamps.
- `conversa_participante`: usuário, papel na conversa, entrada/saída, estado,
  última leitura e autoria.
- `mensagem`: id UUID estável, conversa, remetente, texto, estado do servidor,
  `client_message_id`, timestamps do cliente/servidor, edição/remoção e versão.
- `mensagem_referencia`: ligação tipada com OBRA, EQUIPE, USUARIO, EVENTO, RDO,
  PROGRAMACAO, OCORRENCIA ou outro tipo ontológico validado.
- `mensagem_anexo`: metadados, storage key opaca, hash SHA-256, tamanho, MIME,
  estado de upload, autoria e remoção.
- `mensagem_recibo`: mensagem/participante com entregue/lida e timestamps.

Unique `(remetente_id, client_message_id)` e o UUID do cliente tornam o envio
idempotente. Ordenação usa `enviada_cliente_em`, depois `criada_em_servidor`,
depois `id`, de modo determinístico.

### Acesso

Uma conversa vinculada a obra exige `requireWorksiteAccess`. Conversas de equipe
resolvem as obras ativas da equipe e exigem interseção autorizada. Conversa
direta exige participação ativa. Alfa administra conforme o escopo global, mas
ações são auditadas. Cada listagem, detalhe, mensagem e download repete a policy
no serviço.

### API

- Listagem paginada de conversas com busca, não lidas e última mensagem.
- Criação e atualização de conversa/participantes com autorização.
- Histórico paginado por cursor de mensagem.
- Envio idempotente e retry pelo sync handler `CRIAR_MENSAGEM`.
- Marcação de entrega/leitura idempotente.
- Preparação e conclusão de upload autenticado.
- Download por endpoint autenticado e `Content-Disposition` sanitizado; nenhuma
  URL pública permanente.

### Armazenamento de arquivos

`AttachmentStorage` abstrai persistência binária. A implementação inicial usa
diretório configurado por `CORTEX_ATTACHMENT_STORAGE_PATH`, nomes internos
aleatórios e gravação atômica. O contrato permite substituir por object storage
sem mudar controller/domínio.

Uploads aceitam lista fechada de MIME/extensões configurável, limite por arquivo
e por mensagem, nome higienizado, verificação de magic bytes para formatos
suportados, hash e rejeição de discrepância. A API nunca confia apenas no header
do navegador.

### Offline e reconciliação

Stores IndexedDB: `conversations`, `conversation_participants`, `messages`,
`message_references` e `message_attachments`. O Blob é salvo antes de mostrar a
mensagem como pronta. A mensagem aparece imediatamente como `PENDENTE`.

Ao voltar a conexão:

1. cria/reconcilia a mensagem idempotente;
2. obtém autorização de upload para cada anexo pendente;
3. envia o Blob autenticado com hash e idempotency key;
4. conclui o anexo e atualiza o estado local;
5. puxa eventos e reconcilia versão/recibos sem duplicar.

Falha de um anexo não apaga nem duplica a mensagem. O usuário vê qual arquivo
falhou e pode tentar apenas esse item novamente.

### UX

Desktop usa lista de conversas e painel de mensagens lado a lado. Tablet reduz
a largura da lista. Celular/PWA navega entre lista e conversa, com cabeçalho de
retorno e compositor fixo respeitando safe areas. Balões, separadores por dia,
avatar/iniciais, obra/equipe, não lidas, estados, anexos, skeletons, vazio e
falhas seguem o sistema visual de contenção operacional existente, sem copiar
marcas externas.

Typing indicator só será exposto se houver canal realtime real; não será
simulado. No primeiro ciclo, polling/sync e eventos de visibilidade mantêm o
histórico atualizado.

## Mapas e geoespacial

### Contrato de provider

O frontend define `MapProviderAdapter` com capacidades, inicialização, câmera,
markers, layers, polygons, resize e destroy. A seleção vem de
`VITE_CORTEX_MAP_PROVIDER`.

- MapTiler é ativo quando `VITE_MAPTILER_API_KEY` existe.
- Sem chave, a tela mostra configuração ausente e mantém os demais detalhes da
  obra utilizáveis.
- Mapbox usa adapter próprio e carregamento dinâmico quando
  `VITE_MAPBOX_ACCESS_TOKEN` existir.
- O seletor 2D/3D só aparece para provider configurado com capability 3D.
- Ausência/falha do provider nunca quebra a página de Obras.

As bibliotecas de mapa ficam em chunks de rota/adapters separados. A chave
frontend é tratada como token público restrito por origem; nenhum segredo de
backend é exposto.

### Persistência geoespacial

Novas tabelas `geo_feature` e `geo_feature_version` representam ponto, linha e
polígono como GeoJSON validado, com tipo ontológico, objeto relacionado, obra,
validade, versão, autor, origem e timestamps. Coordenadas simples da tabela
`obra` continuam suportadas e viram o ponto principal da obra.

Não são criados perímetros, trechos, equipamentos ou ocorrências fictícios. A
API retorna coleção vazia quando não há features reais.

### Memória operacional

Cada criação/alteração/encerramento de feature registra objeto `GEO_FEATURE`,
relação tipada com a entidade operacional e evento com before/after, bbox,
versão, ator e origem. A StavIA recebe somente features da obra autorizada.

## Consolidação da ontologia

`CortexOperationalMemoryService` ganha um envelope de evento tipado para reduzir
maps divergentes, incluindo:

- `eventId`, `eventType`, `entity`, `relatedEntities`;
- `actorId`, `deviceId`, `sessionId`;
- `occurredAt`, `recordedAt`, `origin`, `syncStatus`;
- `correlationId`, `causationId`, `entityVersion`;
- `beforeState`, `afterState`, `changedFields`, `reason`;
- relações criadas/encerradas e evidências.

Os métodos atuais continuam como adapters durante a migração. Publishers dos
novos domínios usam o envelope desde o início.

O read model `ontology_*` é atualizado por projetor idempotente a partir de
`commit_seq`, não por sincronização ad hoc disparada por consulta. A rota
principal `/api/stavia/consultas` e as knowledge sources continuam a fonte de
resposta. As rotas legadas `/api/stavia/query` e `/api/stavia/reprogramming`
serão mantidas somente enquanto consumidores reais existirem; nenhuma nova
feature dependerá delas.

## Fortalecimento do PDOR

O snapshot atual será ampliado aditivamente com:

- versão dos dados e janela temporal analisada;
- usuário/processo iniciador;
- conjunto explícito de IDs de evidência;
- dados ausentes/inconsistentes em estrutura consultável;
- limitações e status de calibração visíveis na API/UI;
- comparação com execução anterior e deltas dos principais drivers;
- alertas e recomendações vinculados a evidências, sem ações automáticas;
- capacidade de equipe disponível quando houver participação real;
- relações ontológicas `GERADO_DE`, `USA_EVIDENCIA`, `COMPARA_COM` e
  `RECOMENDA`.

O motor continua determinístico para os mesmos inputs. Sem calibração histórica
real, a UI usa texto explícito de “não calibrado” e não apresenta confiança como
garantia estatística.

## Fortalecimento da StavIA

Novas knowledge sources leem Equipes, Mensagens autorizadas e Geo. Todas recebem
`worksiteId` já autorizado e repetem escopo no SQL. Mensagens privadas só são
recuperadas quando o usuário é participante ou Alfa autorizado; anexos entram
como metadados/evidências, não como conteúdo arbitrário sem processamento.

Respostas passam a expor de forma consistente:

- IDs e links internos;
- fonte e data;
- eventos/relações relevantes;
- confiança e limitações;
- estado de sincronização;
- indicação explícita de falta de evidência ou acesso.

O snapshot local incorpora equipes, conversas/metadados autorizados, geometrias
resumidas e PDOR. Conteúdo sensível não é pré-carregado para obra não autorizada.
Sessão offline preserva apenas o último escopo já sincronizado e deixa claro que
o dado pode estar desatualizado.

Ações futuras usam `StaviaAction` com policy, validação, confirmação,
idempotency key e evento. Este ciclo prepara o contrato, mas não adiciona botões
ou resultados fictícios de ação.

## Tratamento de erros e observabilidade

Um handler global padroniza erros com `code`, mensagem em português,
`correlationId`, timestamp e detalhes de validação seguros. Logs não incluem
texto integral de mensagem privada, CPF, JWT, chaves de mapa ou conteúdo de
arquivo.

Eventos sensíveis registram sucesso e negação com ator/escopo/ação. Métricas
operacionais cobrem quantidade de mutações, retries, conflitos, uploads,
latência e falhas por código, sem dados pessoais.

## Testes e evidências de aceite

### Backend

- Testes unitários de policies e serviços por domínio.
- MockMvc para matriz Alfa/Beta e IDOR por endpoint.
- MySQL 8.4 descartável para migrations, constraints, concorrência,
  idempotência, timeline e projector ontológico.
- Testes reais de upload/download, MIME, tamanho, path traversal e acesso.
- Testes determinísticos e comparativos do PDOR.
- Testes das knowledge sources provando filtragem antes da geração.

### Frontend

- Vitest com ambiente DOM e IndexedDB falso para repositories, outbox,
  reconciliação, retry, anexos e permissões.
- Testes de componentes para layouts, estados, teclado e copy em português.
- Testes dos adapters de mapa para sem chave, MapTiler configurado e Mapbox
  preparado.

### E2E

Playwright/Edge contra MySQL/API descartáveis valida o fluxo Alfa → equipe →
Beta → conversa → envio offline → retorno online → upload sem duplicação →
StavIA/PDOR/mapa. A rede será efetivamente desligada pelo navegador; alterar
apenas uma flag de UI não conta como prova offline.

### Gate final

- `npm test`, `npm run lint` e `npm run build` em `apps/web`.
- `./mvnw test` e package no JDK 21 em `apps/api`.
- Flyway desde banco vazio e upgrade V26 → versão final.
- smoke de sync/ACL/upload em MySQL 8.4.
- screenshots desktop/tablet/mobile e PWA offline.
- scan de segredos e revisão de segurança baseada no diff final.
- relatório requisito por requisito com evidência atual, limitações e variáveis
  de ambiente.

## Variáveis de ambiente previstas

- `VITE_CORTEX_MAP_PROVIDER=maptiler|mapbox`
- `VITE_MAPTILER_API_KEY`
- `VITE_MAPBOX_ACCESS_TOKEN`
- `CORTEX_ATTACHMENT_STORAGE_PATH`
- `CORTEX_ATTACHMENT_MAX_BYTES`
- `CORTEX_ATTACHMENT_ALLOWED_MIME_TYPES`

As variáveis existentes de banco, JWT, integrações e LLM permanecem. `.env.example`
documentará nomes sem valores reais.

## Riscos controlados

- O volume do escopo exige incrementos pequenos; nenhum incremento redefine a
  conclusão do programa.
- Migrations antigas permanecem imutáveis; todas as mudanças são aditivas.
- A generalização do sync preserva testes RDO antes de aceitar novas operações.
- O armazenamento local inicial exige volume persistente no deploy; isso será
  documentado e montado no Compose.
- Tokens públicos de mapas precisam de restrição de domínio no provedor.
- WebSocket/realtime não será afirmado nem simulado sem infraestrutura real.
- Dados históricos em `ontology_*` serão reconciliados pelo projetor antes de
  remover qualquer caminho legado.

## Critério de conclusão

O programa só termina quando todos os critérios do briefing estiverem cobertos
por código real e evidência atual: Mensagens/arquivos offline sem duplicação,
MapTiler configurável e Mapbox preparado, Equipes com Alfa/Beta backend,
eventos/relações/histórico, PDOR explicável, StavIA permissionada, migrations,
testes/builds/E2E, ausência de segredos e relatório final honesto.
