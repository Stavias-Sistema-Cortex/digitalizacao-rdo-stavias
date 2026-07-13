# Autenticação, Mensagens e Financeiro — desenho aprovado

**Data:** 2026-07-13
**Status:** aprovado para implementação
**Escopo:** banco, API, PWA/offline, armazenamento de arquivos, ontologia, StavIA, testes e deploy

## Objetivo

Entregar Mensagens e Financeiro como extensões nativas do Córtex, mantendo o acesso ALFA existente e substituindo o login baseado apenas em CPF por autenticação verificável. A entrega deve funcionar com dados reais, preservar operações offline sem perda, autorizar cada leitura e escrita no backend e iniciar em produção apenas quando as configurações externas obrigatórias estiverem presentes.

“Pronto para deploy” significa neste trabalho:

- migrations Flyway aditivas e verificadas em MySQL;
- API e frontend empacotáveis em containers;
- configuração por variáveis ou arquivos secretos, sem credenciais no repositório;
- providers locais seguros e providers reais configuráveis;
- falha de readiness quando uma dependência obrigatória de produção estiver ausente;
- autorização, idempotência, auditoria e estados vazios cobertos por testes;
- nenhuma alegação de envio SMTP, storage S3 ou domínio corporativo real sem as credenciais correspondentes.

## Restrições globais

- Nenhum CPF, e-mail corporativo específico, UUID de usuário, segredo, token ou dado financeiro será hardcoded.
- CPF é somente identificador normalizado no backend. Nunca é senha, segundo fator, seed, log, resposta ou dado de ontologia.
- Todo valor exibido em Financeiro vem do backend. Sem dados, a UI mostra um estado vazio em português.
- Escritas offline usam o IndexedDB/outbox/sync já existente; não haverá uma segunda fila paralela.
- Arquivos binários não serão base64 nem BLOB/LONGBLOB no MySQL.
- ALFA conserva acesso integral. BETA exige vínculo ativo com a obra e, para Financeiro, concessão explícita por capability.
- Mensagens privadas exigem participação ativa na conversa; vínculo com a mesma obra, sozinho, não concede acesso.
- Eventos permanecem ordenados pelo `commit_seq` canônico.
- Migrations V1–V26 não serão editadas. Novas migrations são V27–V33.
- Backend e testes Maven usam JDK 21.
- Interfaces, erros, estados e documentação destinados ao usuário ficam em português.

## 1. Identidade, OTP e sessão

### Localização protegida do colaborador

Uma nova `auth_identity` separa identidade de login do espelho Academy. O lookup principal usa HMAC-SHA-256 com chave e `keyId` externos. O SHA-256 legado de `colaborador.cpf_hash` permanece temporariamente apenas como lookup de transição; mesmo nesse caminho, a autenticação exige o código recebido por e-mail.

Após uma verificação bem-sucedida, a identidade recebe o HMAC atual. A importação Academy poderá criar ou atualizar identidades pendentes quando houver e-mail, mas nunca sobrescreverá um e-mail já verificado nem publicará HMAC na memória operacional.

A V27 também remove cópias históricas de `cpf_hash` de evidências e snapshots ontológicos. Papéis ALFA explícitos permanecem intactos; papéis ausentes ou inválidos passam a BETA, e o fallback textual por “admin” deixa de decidir autorização em runtime.

### Provisionamento inicial sem hardcode

O banco atual não possui ALFA com e-mail de autenticação utilizável. O corte direto bloquearia os administradores. Por isso haverá um comando administrativo executado no mesmo artefato da API, em modo não web, que lê CPF e e-mail de **arquivos secretos montados**. Ele:

1. normaliza o CPF apenas em memória;
2. localiza o colaborador pelo hash legado;
3. grava HMAC e e-mail como `PENDENTE_VERIFICACAO`;
4. não altera `papel_acesso`;
5. não registra CPF, e-mail completo nem conteúdo dos arquivos;
6. encerra sem abrir uma rota pública de bootstrap.

O usuário então solicita OTP normalmente. A posse do e-mail confirma a identidade e marca o endereço como verificado. A readiness de autenticação fica indisponível enquanto não existir pelo menos um ALFA ativo, autenticável e verificado.

### Desafio por e-mail

`POST /api/auth/email/challenges` retorna sempre `202` e um contrato indistinguível para CPF inexistente, inativo, sem e-mail ou válido. O código é aleatório, armazenado somente como digest, expira, tem limite de tentativas e consumo único. Rate limiting é persistido no banco para funcionar com múltiplas instâncias.

`POST /api/auth/email/challenges/{id}/verify` cria uma sessão opaca revogável. O navegador recebe somente cookie `HttpOnly`, `Secure` em produção e `SameSite` configurado. Nenhum JWT, CPF ou OTP permanece em `localStorage`.

`GET /api/auth/session` reidrata apenas o perfil autorizado; `POST /api/auth/logout` revoga a sessão e limpa o cookie. O filtro verifica sessão, validade e colaborador ativo a cada requisição.

### EmailGateway

`EmailGateway` é o único ponto de envio. Existem:

- provider fake capturável em testes e perfil local, sem envio externo;
- provider SMTP via `JavaMailSender`, com timeouts explícitos;
- contrato capaz de receber futuramente um adaptador Microsoft Graph/OAuth.

Produção falha na validação de configuração se o provider for fake, se remetente autorizado estiver ausente ou se SMTP estiver incompleto. `from` vem somente da configuração. `reply-to` é permitido apenas para endereço do colaborador autenticado e domínio autorizado.

## 2. Passkey e cofre offline

Após OTP online, o usuário pode registrar uma passkey WebAuthn. RP ID, nome e origens vêm da configuração. Registro e autenticação online usam biblioteca WebAuthn auditada; challenge é de uso único e validado no servidor.

Para desbloqueio offline, o frontend tenta a extensão WebAuthn PRF para derivar uma chave que desembrulha o cofre local do usuário. O cofre guarda apenas os dados offline daquele proprietário. Quando PRF não estiver disponível, a aplicação informa a limitação e exige reconexão após a sessão local já aberta expirar. Não existe fallback para Bloom filter ou CPF.

O IndexedDB passa a ter proprietário obrigatório nas mutações e stores sensíveis, cursor por usuário e dedupe por `ownerId + commitSeq`. Dados legados só podem ser reivindicados quando o `sync_state.usuarioId` anterior coincide com a sessão autenticada; caso contrário ficam inacessíveis.

## 3. Autorização

### ALFA e BETA

ALFA tem escopo global e todas as capabilities. BETA continua limitado a `vinculo_colaborador_obra` ativo.

Financeiro adiciona concessões por obra:

- `FINANCEIRO_VISUALIZAR`
- `FINANCEIRO_OPERAR`
- `FINANCEIRO_APROVAR`
- `FINANCEIRO_ADMINISTRAR`

Um BETA precisa simultaneamente do vínculo com a obra e da capability correspondente. A regra vale para controllers, hidratação, pull, exportação e fontes da StavIA. O último ALFA ativo e autenticável não pode ser removido ou desativado pela gestão de papéis.

### Mensagens

- conversa de obra/equipe/grupo: vínculo com a obra + participação ativa;
- conversa individual: ambos os participantes precisam compartilhar ao menos uma obra autorizada, salvo ALFA;
- conversa global sem obra: somente ALFA;
- anexos herdam exatamente a audiência da conversa;
- um BETA da mesma obra, mas fora da conversa, não recebe conteúdo nem eventos.

## 4. Storage, outbox e sincronização

### Storage compartilhado

`ObjectStorage` recebe e devolve streams. O provider local grava em diretório persistente fora da árvore pública e faz rename atômico após validar tamanho, MIME detectado e SHA-256. O provider S3 usa bucket privado, endpoint/region/path-style configuráveis e chaves externas.

O MySQL guarda somente metadados, chave opaca, hash, tamanho, status e relacionamentos. O nome do usuário nunca participa do caminho físico.

### Um único outbox

O outbox ganha `ownerId`, dependências, transporte, próxima tentativa e classificação do erro. O backend ganha `SyncMutationHandler`, com o RDO atual extraído para `RdoSyncMutationHandler` e handlers de Mensagens/Financeiro registrados sem listas de operações espalhadas.

O sync executa mutações prontas em ordem topológica. Conversa, mensagem e upload mantêm UUID/clientMutationId estáveis. Ciclos, dependências órfãs, autorização e validação viram falhas explícitas em português; falha transitória usa backoff. Nenhum erro é removido silenciosamente.

Upload binário usa endpoint de stream, mas é coordenado pela mesma mutação `BINARY_UPLOAD` do outbox. Repetir o mesmo ID e hash retorna sucesso idempotente; mesmo ID com outro hash retorna conflito.

Estados visíveis:

- `LOCAL`
- `NA_FILA`
- `SINCRONIZANDO`
- `SINCRONIZADO`
- `FALHOU`

## 5. Mensagens

Entidades: equipe, membro, conversa, participante, mensagem, anexo e leitura. Não haverá backfill de equipe a partir de texto livre existente, pois isso inventaria identidade e permissão.

A UI permite conversa individual, por obra, equipe e grupo; busca; data/hora; autor; resposta; histórico; anexos; edição e remoção lógica. Mensagem e Blob são gravados em uma transação local antes de qualquer rede e aparecem imediatamente.

Leituras usam endpoints de hidratação paginada, necessários quando uma nova concessão ocorre depois que o cursor já avançou. Deltas continuam pelo pull `commit_seq` e são aplicados atomicamente com `processed_events`.

## 6. Financeiro

Entidades normalizadas:

- centro de custo;
- fornecedor;
- solicitação de compra;
- pedido/compra;
- regra e decisão de aprovação;
- nota fiscal;
- lançamento/pagamento/cobrança;
- template e cobrança por e-mail;
- arquivo relacionado.

Textos antigos em `rdo_material.fornecedor`, `rdo_material.nota_fiscal` e centros de custo livres não serão convertidos automaticamente em entidades confiáveis. Podem aparecer apenas como evidência histórica, nunca como fornecedor ou NF inventados.

Regras de aprovação são dados versionados por obra, centro de custo e faixa de valor. Status têm transições validadas no serviço. Exclusões são lógicas. Indicadores agregam diretamente as tabelas reais e respeitam os mesmos filtros e escopo das listagens.

Áreas da UI:

1. Visão geral
2. Compras
3. Notas fiscais
4. Pagamentos e cobranças
5. Centro de custos
6. Relatórios

Exportações usam o mesmo serviço de consulta e autorização. OCR fica apenas como capability futura; nenhuma extração é simulada.

## 7. Cobranças por e-mail

Cobrança manual, agendada ou automática cria registro persistente antes de enviar. A chave idempotente combina regra, entidade e ocorrência prevista. O scheduler busca itens pendentes com locking transacional, enfileira uma vez e registra cada tentativa.

Automação exige regra explicitamente ativada e template pré-visualizado. SMTP confirma `ENVIADA`; `ENTREGUE` só será usado por provider com webhook/receipt real. Falhas retêm motivo técnico sanitizado e permitem retry sem duplicar mensagem.

## 8. Ontologia e StavIA

`cortex_objeto`, `cortex_relacao` e `cortex_evento_operacional` continuam como memória canônica usada pelo sync. O modelo `ontology_*` existente recebe projeções por um único serviço após a transação de domínio; não haverá dual-write ad hoc em controllers.

Todo evento recebe contexto confiável do servidor:

- ator e dispositivo;
- origem;
- entidade principal e relacionadas;
- obra, conversa e domínio de autorização;
- `clientMutationId`/correlação;
- estado anterior e novo;
- resultado;
- data/hora.

As fontes StavIA de Mensagens e Financeiro chamam primeiro a política de acesso. Respostas carregam evidências verificáveis e nunca consultam um snapshot global para BETA. Perguntas sobre pendência local usam somente o cofre local do próprio usuário.

## 9. Direção de interface

### Sujeito e tarefa

O produto é uma central operacional para equipes de obras rodoviárias. Mensagens deve reduzir a distância entre campo e escritório; Financeiro deve mostrar compromisso, vencimento e responsabilidade por obra sem transformar o Córtex em um ERP genérico.

### Tokens

- asfalto: `#18231F`
- teal Stavias: `#124E4A`
- amarelo operacional: `#F2C800`
- canvas: `#F4F6F4`
- superfície: `#FFFFFF`
- borda: `#D8DFDA`
- Poppins permanece como display e corpo; números usam variantes tabulares.

### Layout

Desktop Mensagens:

```text
┌─────────────┬──────────────────────┬───────────────────────────┐
│ conversas   │ histórico da conversa│ participantes / arquivos │
│ busca/filtro│ e estados de sync    │ contexto da obra         │
└─────────────┴──────────────────────┴───────────────────────────┘
```

Mobile Mensagens:

```text
lista de conversas → conversa → detalhes
composer fixo, anexos e retry acessíveis por toque
```

Financeiro:

```text
obra + período + filtros persistentes
subnavegação das 6 áreas
resumo factual → alertas acionáveis → tabela/kanban → detalhe auditável
```

A assinatura visual é a **faixa de obra**: uma linha amarela fina liga o escopo selecionado aos estados e ao histórico, como uma marcação viária. É usada uma vez por tela; o restante permanece plano e disciplinado. As referências Brickup orientam somente hierarquia, filtros e densidade. Laranja, ativos, composição e estilo visual não serão copiados.

### Autocrítica

Cards de KPI, tabs e kanban podem parecer SaaS genérico. Para evitar isso, cada bloco precisa responder a uma ação real de obra, usar terminologia do Córtex e mostrar origem/período. Não haverá gradientes, glassmorphism, chips decorativos ou métricas sem decisão associada. Motion fica limitado a feedback funcional e respeita `prefers-reduced-motion`.

## 10. Migrations e ordem de entrega

- V27: identidade, OTP, sessão, passkey, hardening de papel e limpeza de PII.
- V28: concessões financeiras por colaborador/obra.
- V29: arquivo compartilhado, contexto de auditoria e extensão do sync.
- V30: equipes, conversas, mensagens, participantes e anexos.
- V31: centro de custo, fornecedor, solicitação, pedido e aprovação.
- V32: nota fiscal, lançamento, pagamento e vínculos de arquivo.
- V33: templates, cobranças, tentativas e extensões ontológicas.

## 11. Verificação obrigatória

1. ALFA existente permanece ALFA após V27 e consegue provisionar/verificar e-mail sem CPF no código.
2. CPF, OTP e sessão não aparecem em localStorage, logs, respostas ou ontologia.
3. BETA fora do escopo recebe 403 e não recebe evento de Mensagens/Financeiro no pull.
4. Mensagem e anexo offline persistem, aparecem imediatamente e sincronizam uma única vez.
5. Compra, NF, pagamento e cobrança podem ser criados, filtrados, editados e auditados.
6. Dashboards mudam somente com dados e filtros reais.
7. Provider fake captura OTP/cobrança em testes e produção rejeita fake.
8. StavIA responde com evidências escopadas.
9. Flyway sobe banco MySQL descartável do zero e atualiza snapshot V26.
10. Maven test, Vitest, lint, build, Docker e smoke de API/PWA passam.

## Referências técnicas externas

- WebAuthn Level 3: https://www.w3.org/TR/webauthn-3/
- Spring Boot e-mail: https://docs.spring.io/spring-boot/reference/io/email.html
- AWS SDK Java 2.x, endpoint S3: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html
