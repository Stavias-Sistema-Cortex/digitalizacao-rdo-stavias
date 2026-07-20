# Córtex Mensagens — Plano de Implementação

> **Execução:** seguir TDD, uma fatia vertical por vez, com commits pequenos.
> O domínio deve reutilizar autenticação, escopo por obra, `commit_seq`, memória
> operacional, sync e IndexedDB existentes.

**Objetivo:** entregar conversas e mensagens reais, autorizadas e local-first,
com anexos protegidos, idempotência, auditoria e uma experiência responsiva em
português.

**Arquitetura:** MySQL mantém o estado autoritativo; `CortexOperationalMemoryService`
publica objetos, relações e eventos para pull por `commit_seq`; o navegador
projeta conversas/mensagens no IndexedDB e usa a outbox existente para
`CRIAR_MENSAGEM`. Binários permanecem no IndexedDB até a mensagem ser
reconciliada e depois seguem por upload autenticado e idempotente. O backend
serve downloads somente após repetir a policy da conversa.

**Stack:** Spring Boot/JdbcTemplate/Flyway/MySQL, React/TypeScript/Vite,
IndexedDB via `idb`, Vitest e JUnit 5/Mockito/MockMvc.

---

## Tarefa 1 — Schema autoritativo e configuração de anexos

**Arquivos**

- Criar: `apps/api/src/main/resources/db/migration/V28__create_messaging.sql`
- Criar: `apps/api/src/test/java/com/projeto/cortex/mensagens/MessagingMigrationTest.java`
- Alterar: `apps/api/src/main/resources/application.yml`
- Alterar: `.env.example`

**RED:** testar que a migration cria `conversa`, `conversa_participante`,
`mensagem`, `mensagem_referencia`, `mensagem_anexo` e `mensagem_recibo`, com
FKs restritivas, participações temporais, IDs estáveis, versões, índices de
ordenação e unique `(remetente_id, client_message_id)`. O CHECK de operações
de sync deve incluir `CRIAR_MENSAGEM`.

**GREEN:** implementar a V28 sem dados seed/fictícios. Configurar
`CORTEX_ATTACHMENT_STORAGE_PATH`, limites por arquivo/mensagem e lista fechada
de MIME types. Não armazenar URL pública.

**Verificar:**

```bash
cd apps/api
./mvnw -q -Dtest=MessagingMigrationTest test
```

**Commit:** `feat(api): add messaging schema`

## Tarefa 2 — Policy de conversa e DTOs

**Arquivos**

- Criar: `apps/api/src/main/java/com/projeto/cortex/mensagens/ConversationAccessPolicy.java`
- Criar DTOs/records em `apps/api/src/main/java/com/projeto/cortex/mensagens/`
- Criar: `apps/api/src/test/java/com/projeto/cortex/mensagens/ConversationAccessPolicyTest.java`

**RED:** cobrir conversa de obra autorizada, equipe com interseção de obra,
participação direta ativa, conversa inexistente e IDOR. Beta não pode ampliar
participantes nem consultar conversa fora do seu escopo; Alfa mantém escopo
global, mas ainda precisa operar sobre uma conversa existente.

**GREEN:** centralizar a resolução da conversa e repetir a policy em cada
leitura/mutação. Nenhum controller decide acesso sozinho.

**Verificar:**

```bash
./mvnw -q -Dtest=ConversationAccessPolicyTest test
```

**Commit:** `feat(api): enforce conversation scope`

## Tarefa 3 — Conversas e participantes

**Arquivos**

- Criar: `ConversationService.java`, `ConversationController.java`
- Criar/alterar records de request/response/página
- Criar: `ConversationServiceTest.java`, `ConversationControllerMockMvcTest.java`

**RED:** testar criação idempotente por ID, criação da conversa da equipe,
participantes reais, busca/paginação, última mensagem, não lidas, entrada/saída
temporal, filtro Alfa/Beta e tentativa Beta de administrar.

**GREEN:** endpoints de listagem, detalhe, criação e manutenção de participantes.
Conversas de equipe validam equipe/obra ativa e membros; conversa de obra
valida vínculos. Ordenação usa `ultima_atividade_em DESC, id`.

**Commit:** `feat(api): implement scoped conversations`

## Tarefa 4 — Mensagens, referências e recibos

**Arquivos**

- Criar: `MessageService.java`, `MessageController.java`
- Criar: `MessageMemoryPublisher.java`
- Criar testes de serviço/controller/publisher

**RED:** testar envio idempotente, texto vazio sem anexo rejeitado, ordenação
determinística, cursor, remetente derivado da sessão, referência OBRA/EQUIPE/RDO/
PROGRAMACAO/EVENTO validada e escopada, leitura idempotente e acesso negado por
ID manipulado.

**GREEN:** persistir mensagem/refs/recibos e publicar objetos `CONVERSA`,
`MENSAGEM`, `MENSAGEM_ANEXO`; relações `PARTICIPA_DE`, `ENVIADA_EM`,
`REFERE_SE_A`, `VINCULADA_A`; eventos estruturados com ator, origem, estados e
obra. Não publicar conteúdo privado em campos globais de catálogo.

**Commit:** `feat(api): implement messages and receipts`

## Tarefa 5 — Armazenamento protegido e uploads

**Arquivos**

- Criar: `AttachmentStorage.java`, `FileSystemAttachmentStorage.java`
- Criar: `MessageAttachmentService.java`, `MessageAttachmentController.java`
- Criar: `MessageRateLimitService.java`
- Criar testes unitários e MockMvc de upload/download

**RED:** cobrir tamanho, quantidade, extensão, MIME e magic bytes; nome com path
traversal; hash divergente; upload repetido; mensagem/obra sem autorização;
download por ID alterado; limite por usuário; falha parcial recuperável.

**GREEN:** gravação atômica em diretório configurado com storage key aleatória,
SHA-256 e estados `PENDENTE`, `DISPONIVEL`, `FALHOU`. Download autenticado usa
`Content-Disposition` sanitizado e nunca expõe caminho/URL permanente.

**Commit:** `feat(api): protect message attachments`

## Tarefa 6 — Sync idempotente de mensagens

**Arquivos**

- Criar: `apps/api/src/main/java/com/projeto/cortex/sync/MessageSyncMutationHandler.java`
- Alterar: `SyncService.java` somente se o contrato genérico exigir
- Criar testes do handler e integração do push

**RED:** `CRIAR_MENSAGEM` deve exigir `entidadeTipo=MENSAGEM`, ID estável e
`clientMessageId`; retry retorna o mesmo registro; evento OFFLINE preserva
usuário/dispositivo/correlação; resposta traz versão/commit/payload canônicos.

**GREEN:** delegar ao `MessageService`, sem duplicar regra de autorização ou
persistência no sync.

**Commit:** `feat(sync): support offline messages`

## Tarefa 7 — Stores IndexedDB e pipeline local-first

**Arquivos**

- Alterar: `apps/web/src/lib/db/cortexDb.ts`, `db.types.ts`
- Criar repositories de conversas, participantes, mensagens, referências e anexos
- Alterar: `outboxRepository.ts`, `sync.types.ts`, `syncStorage.ts`
- Criar: `apps/web/src/features/mensagens/messageLocalService.ts`
- Criar testes Vitest de migração/reconciliação/retry

**RED:** mensagem offline aparece imediatamente com UUID estável e `PENDING`;
Blob é salvo antes de enfileirar; retry usa a mesma mutation; reconciliação não
duplica nem apaga; ordenação é estável; `SYNCING` interrompido retorna a
`PENDING`; falha de anexo não perde a mensagem.

**GREEN:** adicionar stores/indexes aditivamente e um reconciliador local por
operação. Preservar os fluxos RDO/Equipes existentes.

**Commit:** `feat(web): add offline message storage`

## Tarefa 8 — Cliente API e uploads automáticos

**Arquivos**

- Criar: `apps/web/src/features/mensagens/messageApi.ts`
- Criar: `messageAttachmentSync.ts`
- Alterar: `useAutomaticSync.ts`/`syncEngine.ts` apenas nos pontos extensíveis
- Criar testes de rede, backoff, autorização e retomada

**RED:** ao reconectar, enviar mensagem, reconciliar ID/versão, enviar cada Blob
autenticado e atualizar estado individual. 401/403 são permanentes; rede/5xx
retentam com backoff; upload já concluído é idempotente.

**GREEN:** integrar sem loops paralelos e sem remover o Blob antes da confirmação.

**Commit:** `feat(web): synchronize message attachments`

## Tarefa 9 — Experiência Mensagens responsiva

**Pré-requisito:** ler e aplicar `frontend-design` antes das edições visuais.

**Arquivos**

- Criar feature em `apps/web/src/features/mensagens/`
- Alterar: `apps/web/src/App.tsx`, `components/shell/CortexShell.tsx`
- Alterar: estilos globais/feature seguindo os tokens existentes
- Criar testes de componentes/estados

**RED:** testar lista/busca, não lidas, seleção, vazio, skeleton, separador por
dia, balões enviados/recebidos, estados Pendente/Enviando/Enviada/Falha, retry,
anexo, bloqueio sem permissão e navegação mobile.

**GREEN:** desktop em duas colunas; mobile lista ou conversa; compositor fixo
com safe area; avatar/iniciais e obra/equipe reais. Sem typing simulado e sem
conversas fictícias.

**Commit:** `feat(web): build responsive messaging experience`

## Tarefa 10 — Verificação do incremento

**Verificar:**

```bash
cd apps/api && ./mvnw -q test
cd ../web && npm test && npm run build
```

Subir MySQL descartável com `utf8mb4_unicode_ci`, aplicar V1–V28, testar Alfa/
Beta, envio duplicado, sync offline e upload/download autenticados. Executar
fluxo no navegador real em desktop e viewport mobile. Rodar `git diff --check`
e documentar quaisquer dependências externas reais.

**Commit:** somente se a verificação exigir correção ou documentação.
