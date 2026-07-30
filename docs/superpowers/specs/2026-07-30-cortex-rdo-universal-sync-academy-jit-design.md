# Córtex: RDO universal, sincronização automática e acesso Academy JIT

Data: 30/07/2026

Status: desenho aprovado em conversa, aguardando revisão da especificação

## Objetivo

Permitir que qualquer usuário autenticado registre e sincronize RDOs
automaticamente, esteja ou não vinculado à obra, sem transformar mão de obra
operacional em autorização de acesso e sem perder autoria, proveniência ou
histórico.

O mesmo desenho deve:

- criar colaboradores operacionais globais a partir do RDO;
- permitir que todos os colaboradores ativos da Stavias presentes na Academy
  entrem no Córtex sem importação prévia;
- impedir o acesso de registros Academy inativos ou desligados com a mensagem
  exata `Seu acesso está desligado/inativo.`;
- manter o polling contínuo da Academy desligado;
- tornar a ontologia a trilha autoritativa de quem alterou cada entidade;
- completar as provas offline e R2 no runtime oficial;
- acrescentar a ação de reativar obra inativa e autoria à timeline recolhida;
- reduzir o excesso de textos explicativos e de peso tipográfico sem redesenhar
  o produto.

## Decisões aprovadas

1. A sincronização de RDO é universal dentro do domínio RDO: sessão e
   dispositivo válidos bastam para transportar e aplicar mutações de RDO.
2. O autor do RDO não precisa estar vinculado à obra.
3. Trabalhadores e apontadores informados no RDO não precisam estar ativos ou
   vinculados à obra para que o RDO seja aceito.
4. Presença operacional e autorização são conceitos distintos.
5. Uma pessoa digitada no RDO recebe um cadastro global com UUID estável.
6. A ativação de login usa fluxo online protegido por CPF e OTP; CPF e OTP
   nunca entram no draft ou outbox do RDO.
7. O login consulta a Academy sob demanda. Polling contínuo permanece
   desligado.
8. Registro Academy ativo entra como Beta. Registro inativo ou desligado não
   recebe sessão.
9. Toda criação, edição, remoção, replay, restauração ou reativação registra
   autoria na ontologia.
10. Financeiro, administração Alfa e os demais domínios mantêm suas permissões
    próprias.

## Não objetivos

- Não abrir o Financeiro, a administração de usuários ou o ciclo de vida de
  Obras para todo usuário autenticado.
- Não reativar o polling periódico Academy.
- Não mesclar pessoas pelo nome.
- Não armazenar CPF bruto, OTP, cookie, token ou segredo em IndexedDB, outbox,
  evento ontológico ou log.
- Não substituir conflitos de integridade por `last write wins` silencioso.
- Não fazer uma nova identidade visual do Córtex.
- Não tornar a presença de uma pessoa em um RDO equivalente a acesso à obra.

## Fronteiras de domínio

| Superfície | Regra depois desta mudança |
| --- | --- |
| Login Córtex | Academy JIT ativo ou identidade válida já confirmada |
| RDO: leitura, criação, edição e replay | Qualquer sessão autenticada |
| Mão de obra do RDO | Sem exigência de vínculo ou estado de login |
| Anexos de RDO | Qualquer sessão autenticada no domínio RDO |
| Ciclo de vida de Obras | Alfa |
| Financeiro | Permissões financeiras existentes |
| Mensagens e anexos de conversa | Participação/autorização existente |
| Administração de identidade e papel | Alfa |

As regras universais são deliberadamente restritas ao domínio RDO. Um usuário
Beta sem vínculo pode preencher e sincronizar um RDO, mas isso não lhe concede
grants financeiros, administração Alfa ou participação em conversas.

## Arquitetura-alvo

```text
RDO local
  |
  | transação IndexedDB
  |-- draft
  |-- evento local
  `-- outbox por usuário + dispositivo
          |
          | automático: salvar / online / foreground / backoff
          v
      /api/sync/push
          |
          | sessão + dispositivo, sem filtro de obra para RDO
          v
   RdoSyncOperationHandler
          |
          | transação PostgreSQL
          |-- colaborador operacional global
          |-- RDO e mão de obra
          |-- projeção atual
          `-- evento ontológico com autoria
```

O transporte decide se o envelope chegou de uma sessão e dispositivo válidos.
O handler decide se a operação é tecnicamente íntegra. O vínculo com a obra
deixa de ser uma condição de elegibilidade para RDO.

## Sincronização automática

### Persistência local

Salvar ou alterar um RDO deve gravar atomicamente:

1. o draft atualizado;
2. o evento operacional local;
3. a mutação idempotente no outbox;
4. os blobs locais pendentes associados, quando existirem.

Falha em qualquer etapa reverte a transação local. A interface nunca anuncia
`Sincronizado` antes dessa gravação e da confirmação remota correspondentes.

### Namespace de transporte

O outbox e os blobs pendentes serão identificados por:

- identidade proprietária;
- dispositivo;
- identificador estável da mutação.

A lista corrente de `obraIds` não participa da identidade do outbox. Mudança de
escopo, atualização de sessão ou ausência de vínculo não pode abandonar uma
mutação em outro banco IndexedDB.

Caches de leitura sensíveis de outros domínios podem continuar segmentados por
escopo. O transporte pendente não.

### Disparos automáticos

O motor tenta enviar:

- imediatamente depois de uma gravação local;
- no evento `online`;
- ao retomar ou colocar a aplicação em primeiro plano;
- em janela periódica enquanto houver pendências;
- após falha transitória, com backoff limitado e jitter.

`Sincronizar agora` permanece como reforço manual, não como requisito para
consistência.

### Contrato do servidor

`/api/sync/push`, `/api/sync/pull` e `/api/sync/ack` continuam exigindo sessão
e dispositivo válidos. Para operações RDO:

- `RdoSyncOperationHandler` não chama `requireWorksiteAccess`;
- criação e atualização aceitam qualquer obra existente;
- colaborador canônico não é rejeitado por estar inativo ou sem vínculo;
- uma pessoa nominal continua válida;
- idempotência, hash, dependências causais, tipo de operação e integridade do
  payload continuam obrigatórios.

O servidor responde por mutação:

- `APLICADA`: projeção e evento autoritativo confirmados;
- `CONFLITO`: mutação preservada, mas requer resolução estrutural;
- `REJEITADA`: payload ou operação tecnicamente inválidos.

Ausência de vínculo ou estado de login do trabalhador nunca produz
`REJEITADA`.

Falha HTTP global fica reservada a transporte, sessão, dispositivo ou
indisponibilidade do serviço. Um item em conflito não derruba o ciclo inteiro.

### Estado de interface

O banner passa a distinguir:

- `Sincronizando`;
- `Sincronizado`;
- `Sem conexão — alterações salvas`;
- `Sincronizado — N itens precisam de revisão`.

O texto `Falha na sincronização` não é usado para uma rejeição ou conflito
isolado. Os dados locais permanecem visíveis até confirmação ou resolução.

## Cadastro operacional global pelo RDO

### Identidade estável

Ao adicionar uma pessoa nova, o cliente gera um UUID estável para o cadastro
operacional. A mutação do RDO inclui um comando de criação embutido, permitindo
que colaborador e RDO sejam aplicados na mesma transação PostgreSQL.

Repetir a mesma mutação:

- reutiliza o mesmo UUID;
- não duplica a pessoa;
- não duplica a linha de mão de obra;
- retorna o mesmo resultado autoritativo.

### Dados mínimos

O cadastro originado no RDO contém apenas dados realmente informados:

- UUID;
- nome;
- origem `CORTEX/RDO`;
- RDO de origem;
- ator criador;
- horários de criação e atualização;
- estado de cadastro operacional.

CPF, e-mail, código Academy, grupo e perfil não são inventados.

### Associação operacional

Será criada uma associação operacional não autorizadora entre pessoa e obra,
com proveniência do primeiro e último RDO em que apareceu. Essa associação
serve para:

- pesquisa;
- sugestão;
- herança da equipe;
- desambiguação por obra;
- histórico.

Ela não é consultada por `CurrentUserService` e não substitui
`vinculo_colaborador_obra`.

### Reconciliação

Nomes iguais permanecem pessoas distintas. Um cadastro provisório só pode ser
reconciliado com uma identidade Academy após CPF verificado.

Se o CPF já estiver associado a outro cadastro, a operação cria uma pendência
de reconciliação explícita. Não ocorre merge silencioso.

Remover uma pessoa de um RDO não apaga o cadastro global nem sua história.

## Login Academy sob demanda

### Mudança de contrato

Este desenho substitui a decisão anterior de nunca consultar a Academy durante
login público. O polling continua desligado, mas uma tentativa online consulta
a fonte Academy por CPF.

### Fluxo

1. O usuário informa CPF canônico.
2. O backend consulta a Academy por conexão `SELECT`-only com TLS verificado.
3. O resultado é validado quanto a unicidade e estado.
4. Se ativo, o Córtex cria ou atualiza de forma idempotente:
   - colaborador global;
   - CPF mascarado e HMAC versionado;
   - e-mail de autenticação;
   - identidade local;
   - papel Beta quando ainda não houver papel mais privilegiado.
5. O fluxo envia e valida OTP.
6. Após OTP válido, uma sessão opaca normal é emitida.

Não há etapa Alfa de importação ou ativação manual.

### Registro inativo ou desligado

Registro Academy inativo ou desligado:

- não recebe OTP real;
- não recebe sessão;
- não é reativado localmente;
- exibe exatamente `Seu acesso está desligado/inativo.`.

A resposta não contém nome, e-mail, obra, cargo ou outra informação Academy.
Rate limit por CPF, origem e dispositivo continua obrigatório.

### Academy indisponível

Falha de conexão, TLS, timeout ou resposta ambígua não é tratada como
inatividade. A interface informa indisponibilidade temporária e não retorna
erro 500 genérico.

Sessões já emitidas continuam válidas até sua expiração. Uma nova sessão online
falha fechada enquanto não for possível confirmar o estado Academy. O
desbloqueio offline continua limitado a grant assinado e mecanismo de
verificação já provisionado.

### Polling

`CORTEX_SYNC_ACADEMY_ENABLED` permanece `false` no contrato de produção. O
scheduler não participa do login JIT e não é requisito de readiness.

Credenciais Academy continuam:

- somente leitura;
- fornecidas por secret;
- sem log;
- com TLS e timeouts explícitos;
- cobertas por QA de privilégios e mapeamento.

## Ontologia e autoria

### Evento autoritativo

Toda mutação de domínio gera um evento imutável com:

- usuário autor;
- colaborador autor;
- nome do autor em snapshot;
- dispositivo;
- canal (`ONLINE`, `OFFLINE_REPLAY`, `ACADEMY_JIT`, `SYSTEM`);
- obra, RDO e entidade;
- operação;
- `clientMutationId`;
- versão-base e versão resultante;
- horário declarado no dispositivo;
- horário de recebimento;
- horário de aplicação;
- resumo não sensível;
- hash do payload;
- correlação e causalidade.

O ator vem da sessão ou do grant offline assinado. Campos de autoria recebidos
no payload nunca substituem a identidade validada pelo servidor.

### Replay

Replay idempotente retorna o evento autoritativo já existente. Não cria um
segundo evento e não troca o ator original pelo usuário que apenas disparou uma
retentativa posterior.

### Concorrência

Mutações válidas concorrentes são preservadas como eventos. A projeção atual
usa:

- IDs estáveis para itens de coleção;
- tombstones para remoções;
- ordem autoritativa de aplicação;
- versão-base para identificar incompatibilidade estrutural.

Um conflito não apaga nenhum dos lados. A interface apresenta o item para
revisão, e a resolução também gera evento.

### Superfícies

A timeline de Obras expõe `actorId` e `actorName`. A resolução segue:

1. snapshot do ator no evento;
2. nome atual resolvido pelo ID;
3. ID do ator;
4. `Sistema` apenas para job realmente automático.

O colaborador objeto da alteração não pode ser confundido com o ator.

CPF, OTP, cookies, tokens, segredos e payloads sensíveis não entram na
ontologia.

## Ciclo de vida de Obras

O ciclo de vida fica explícito:

| Estado atual | Ação | Resultado |
| --- | --- | --- |
| `ATIVA` | Desativar | `INATIVA` |
| `INATIVA` e não arquivada | Reativar | `ATIVA` |
| Não arquivada | Excluir/arquivar | Arquivada, preservando estado anterior |
| Arquivada | Restaurar | Estado anterior ao arquivamento |

Reativar é uma ação nova e distinta de Restaurar. O endpoint e a mutação
offline exigem Alfa, versão-base e idempotência.

Cada transição gera evento com ator. A interface só mostra ações compatíveis
com o estado atual.

## Polimento visual e editorial

Esta etapa é polimento, com prioridade para reduzir excesso de write-up.

### Tipografia

- corpo: peso `400`;
- navegação, controles e labels: `500`;
- títulos e alertas relevantes: `600`;
- remover regra global que força `strong` e `b` a `600` depois de classificar
  os usos semânticos.

### Conteúdo

Remover:

- instruções que apenas repetem o nome do controle;
- parágrafos introdutórios sem decisão ou consequência;
- textos duplicados entre título, subtítulo e card;
- adornos e microcopy com aparência promocional ou “AI-generated”.

Preservar:

- erro e recuperação;
- estado offline;
- consequência destrutiva;
- permissão;
- proveniência;
- conflito;
- privacidade.

### Obras

O badge de estado usa:

- altura visual de 22–24 px;
- padding aproximado de `2px 7px`;
- borda de 1 px;
- texto compacto em peso `500`;
- `nowrap`;
- contraste compatível com cada estado.

Sidebar, Poppins, paleta institucional, rotas e comportamento permanecem.

As superfícies revisadas são Home, Obras, RDO, Equipes, Mensagens, Financeiro,
PDOR, autenticação e Memória.

## Offline e R2

### Prova offline obrigatória

No site oficial:

1. entrar com colaborador Academy ativo;
2. selecionar qualquer obra, sem depender de vínculo;
3. ficar offline;
4. criar um RDO;
5. incluir pessoa nova e trabalhador sem vínculo;
6. salvar sem usar o botão manual de sync;
7. reconectar;
8. observar envio automático;
9. confirmar a projeção no PostgreSQL;
10. confirmar autor, dispositivo e horários na ontologia.

O teste também troca o escopo da sessão antes do replay para comprovar que o
outbox não ficou preso em outro namespace.

### Anexo R2

A prova autenticada usa um arquivo real:

1. upload pela API do domínio;
2. persistência no prefixo de produção do R2;
3. download autenticado;
4. comparação de tamanho e SHA-256;
5. remoção controlada;
6. confirmação de ausência depois da remoção.

O endpoint genérico de objetos delega autorização ao domínio proprietário:

- RDO: sessão autenticada;
- Mensagens: participação na conversa;
- demais domínios: política existente.

Uma rota genérica não pode contornar autorização específica. Download deve
validar o hash persistido antes de responder ou sinalizar corrupção.

## Contratos de dados

### Colaborador operacional

O modelo precisa distinguir:

- cadastro operacional;
- identidade de autenticação;
- estado Academy;
- autorização de obra;
- participação histórica em RDO.

Uma tabela ou projeção equivalente a
`colaborador_obra_operacional` contém, no mínimo:

- `colaborador_id`;
- `obra_id`;
- `primeiro_rdo_id`;
- `ultimo_rdo_id`;
- `criado_por_usuario_id`;
- `primeira_ocorrencia_em`;
- `ultima_ocorrencia_em`;
- versão.

Ela não concede acesso.

### Autoria

Eventos novos persistem snapshot do ator e metadados de dispositivo/tempo.
Eventos históricos sem snapshot continuam legíveis por fallback de ID.

### Obra

O arquivamento preserva o estado anterior para que Restaurar não seja usado
como sinônimo de Reativar.

Migrações são forward-only e devem tolerar dados já existentes.

## Erros e recuperação

| Condição | Resultado |
| --- | --- |
| Sem rede | Alteração local salva; retentativa automática |
| API temporariamente indisponível | Backoff; sem descartar outbox |
| Academy indisponível | Mensagem de indisponibilidade; sem sessão nova |
| Academy inativo/desligado | `Seu acesso está desligado/inativo.` |
| Trabalhador sem vínculo | RDO aplicado normalmente |
| Autor sem vínculo | RDO aplicado normalmente |
| Mutação repetida | Mesmo resultado e evento |
| Conflito estrutural | Item para revisão; restante sincronizado |
| Payload inválido | Item rejeitado com motivo estável e acionável |
| Hash R2 divergente | Download bloqueado e corrupção registrada |

Mensagens técnicas internas não são usadas como contrato de recuperação do
frontend.

## Segurança e privacidade

- Sessão e dispositivo continuam obrigatórios para sync.
- CSRF, cookie opaco, expiração, revogação e rate limits permanecem.
- O acesso universal vale apenas para RDO.
- Academy usa consulta parametrizada, TLS, timeout e credencial `SELECT`-only.
- CPF é normalizado em memória e persistido apenas como máscara/HMAC conforme o
  contrato existente.
- OTP é de uso único, expira e nunca é logado.
- Autor de evento não é confiado ao payload.
- Hash, dependências causais e idempotência continuam validados.
- Anexos usam autorização específica de domínio.
- Logs, testes, screenshots e widgets não contêm PII ou secrets.

## Compatibilidade e migração

- RDOs nominais existentes continuam válidos.
- IDs `null` históricos não são reescritos automaticamente.
- A reconciliação para colaborador global é explícita e auditada.
- Clientes antigos continuam podendo enviar pessoa nominal.
- A API passa a aceitar, de forma aditiva, o comando de colaborador global.
- A regra antiga de vínculo é removida somente dos handlers e consultas RDO.
- `vinculo_colaborador_obra` continua sendo autorização para outros domínios.
- O polling Academy permanece desligado no Blueprint e nos contratos de
  publicação.
- A timeline adiciona campos de ator sem remover os campos atuais.

## Estratégia de validação

### Backend

- usuário sem vínculo cria, atualiza e sincroniza RDO;
- trabalhador ativo, inativo, nominal ou sem vínculo não bloqueia RDO;
- mesma mutação não duplica colaborador, RDO ou evento;
- CPF verificado reconcilia cadastro provisório;
- nomes iguais não são mesclados;
- Academy ativo provisiona identidade e inicia OTP;
- Academy inativo retorna a mensagem aprovada e não emite sessão;
- Academy indisponível não vira inatividade nem erro 500;
- permissões de Financeiro, Mensagens e Alfa permanecem;
- Reativar aceita apenas obra inativa não arquivada;
- timeline resolve o ator correto;
- objeto R2 respeita o domínio e valida SHA-256.

### Frontend

- gravação local cria outbox automaticamente;
- `online`, foreground e backoff disparam sync;
- mudança de escopo não abandona outbox;
- banner diferencia transporte, offline e revisão por item;
- cadastro RDO usa UUID estável;
- CPF/OTP nunca aparece no draft/outbox;
- login mostra a mensagem exata de inativo;
- timeline exibe autor;
- ação Reativar só aparece para estado compatível;
- testes editoriais impedem retorno dos textos removidos;
- testes geométricos protegem badge, sobreposição e responsividade.

### Integração e runtime oficial

- PostgreSQL/Flyway completos;
- API completa;
- PWA test, lint e build;
- publicação e secret scan;
- login JIT Academy real com conta ativa e conta inativa de QA;
- fluxo `online → offline → criar RDO → reconectar → replay`;
- confirmação PostgreSQL e ontológica;
- upload/download autenticado R2 com hash;
- workflow verde e runtime no SHA publicado.

## Critérios de aceite

1. A mensagem do screenshot não volta a ocorrer por vínculo ou estado de mão de
   obra.
2. Qualquer usuário autenticado consegue sincronizar RDO automaticamente em
   qualquer obra existente.
3. Uma pessoa nova torna-se colaborador operacional global e herdável sem
   ganhar autorização implícita.
4. Todo colaborador Academy ativo consegue iniciar o acesso sem importação
   prévia.
5. Academy inativo ou desligado vê
   `Seu acesso está desligado/inativo.` e não recebe sessão.
6. Polling Academy continua desligado.
7. Toda alteração de RDO e Obra mostra quem a realizou.
8. Uma obra inativa pode ser reativada por Alfa sem passar por Restaurar.
9. O sync automático funciona depois de offline e mudança de escopo.
10. Um anexo real é comprovado no R2 com autenticação e SHA-256.
11. O excesso de write-up é reduzido sem apagar mensagens operacionais
    necessárias.
12. Nenhum domínio fora de RDO ganha acesso adicional por regressão.

## Riscos e mitigação

### RDO universal amplia visibilidade

Mitigação: limitar a abertura aos endpoints e objetos do domínio RDO, manter
testes negativos em Financeiro, Mensagens e Alfa e registrar toda leitura ou
escrita sensível conforme o contrato existente.

### Login JIT depende da Academy

Mitigação: timeouts curtos, pool limitado, circuit breaker, resposta explícita,
sessões existentes preservadas e nenhum fallback que trate falha como usuário
ativo.

### Duplicação de pessoas

Mitigação: UUID idempotente no RDO, CPF HMAC para identidade verificada e
reconciliação explícita.

### Crescimento da ontologia

Mitigação: eventos compactos, sem payload sensível integral, índices por obra,
RDO, ator e correlação e projeções próprias para leitura.

### Conflitos offline

Mitigação: IDs estáveis, versões-base, tombstones, eventos imutáveis e revisão
por item sem bloquear o restante do outbox.

### Texto excessivo retornar

Mitigação: contratos editoriais e snapshots geométricos focados nas superfícies
revisadas.

## Superfícies prováveis de alteração

Frontend:

- `apps/web/src/lib/sync/syncEngine.ts`;
- `apps/web/src/lib/db/localRdoService.ts`;
- `apps/web/src/lib/auth/authSession.ts`;
- `apps/web/src/lib/db/cortexDb.ts`;
- `apps/web/src/components/sync/SyncStatusBanner.tsx`;
- `apps/web/src/features/rdos/RdoWorkforceEditor.tsx`;
- fluxos de login CPF/OTP;
- timeline e lifecycle de Obras;
- estilos globais e das nove superfícies aprovadas.

Backend:

- `SyncService` e handlers RDO;
- serviços de criação e atualização de RDO;
- persistência de colaboradores e associação operacional;
- adapter Academy e fluxo OTP;
- memória operacional e projeção ontológica;
- lifecycle e sync de Obras;
- autorização de objetos/R2;
- migrations PostgreSQL.

Infraestrutura e validação:

- configuração Academy JIT com polling desligado;
- contratos Render/Cloudflare/Neon/R2;
- testes de publicação;
- runbook de prova offline e R2.
