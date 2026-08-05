# Autorização Alfa/Beta e vínculo explícito com a obra

Modelo de autorização do Córtex baseado em dois papéis iniciais — **Alfa**
(administrativo global) e **Beta** (operacional restrito) — e em um **vínculo
explícito** entre colaborador e obra. Extensível a novos papéis sem espalhar
condicionais pelo código.

## 1. Conceitos

| Papel | Escopo | Resumo |
|------|--------|--------|
| `ALFA` | Global | Enxerga e administra todas as obras, integrações, ontologia, PDOR e Stav.IA global. |
| `BETA` | Restrito | Opera apenas nas obras com **vínculo ativo**; Stav.IA e PDOR limitados a essas obras. |

O acesso de um usuário Beta a uma obra é concedido **exclusivamente** por um
vínculo explícito e auditável. Não há mais concessão por inferência (nem por
alocação operacional, nem por presença em RDO anterior).

## 2. Persistência

### `colaborador.papel_acesso` (`VARCHAR(20)`, obrigatório)
Papel explícito do colaborador. A V27 preserva todo `ALFA` já explícito e
normaliza somente valores ausentes/desconhecidos para `BETA`. Em runtime não há
inferência por perfil, grupo ou texto importado da Academy; papel inválido
nega acesso.

### `vinculo_colaborador_obra`
Vínculo explícito colaborador ↔ obra.

| Coluna | Tipo | Observação |
|--------|------|-----------|
| `id` | `CHAR(36)` | PK. |
| `obra_id` | `CHAR(36)` | FK → `obra(id)`. |
| `colaborador_id` | `CHAR(36)` | FK → `colaborador(id)`. |
| `status` | `VARCHAR(20)` | `ATIVO` \| `REVOGADO` (CHECK). |
| `papel_na_obra` | `VARCHAR(40)` | Papel operacional (default `OPERACIONAL`). |
| `atribuido_em` / `atribuido_por` | `DATETIME(6)` / `VARCHAR(120)` | Auditoria da atribuição. |
| `revogado_em` / `revogado_por` | `DATETIME(6)` / `VARCHAR(120)` | Auditoria da revogação. |
| `metadados_json` | `JSON` | Contexto adicional (ex.: origem do backfill). |
| `criado_em` / `atualizado_em` / `versao_linha` | — | Versionamento de linha. |

- **Unique** `(colaborador_id, obra_id)`: uma linha por par; o `status` alterna
  entre `ATIVO` e `REVOGADO` ao longo do tempo, preservando o histórico.
- **Índices** `(colaborador_id, status)` e `(obra_id, status)` para as duas
  direções de consulta.

Migração: `V26__papel_acesso_e_vinculo_obra.sql`. O backfill transforma as
alocações vigentes (`alocacao_colaborador` com `status <> 'CANCELADA'`) em
vínculos explícitos `ATIVO`, preservando o acesso atual sem manter a inferência
em tempo de execução.

### Histórico: a cerca saiu e voltou

Entre a V26 e a V70 houve um período em que o vínculo **não** decidia acesso.
`podeAcessarObra` passou a aceitar qualquer colaborador reconhecido e
`allowedObraIds` passou a enumerar todas as obras, com um motivo real: cercar
sem vínculo cadastrado deixava o apontador sem obra nenhuma, e quem trabalhava
numa frente não achava a obra ao lado.

A operação pediu a cerca de volta — quem aponta deve ver a sua obra, não a de
todos — e a `V70__vinculo_de_obra_a_partir_da_operacao.sql` reconstrói os
vínculos a partir da operação já registrada (alocação vigente e presença em
`rdo_mao_obra`) antes de a regra voltar a valer. `ON CONFLICT DO NOTHING`
preserva revogações explícitas: backfill não desfaz decisão de ninguém.

Duas consequências que valem estar escritas, porque parecem defeito:

- **Beta sem vínculo entra e não vê obra alguma.** É o desfecho correto. Quem
  investigar isso deve olhar `vinculo_colaborador_obra` antes do código.
- **A cerca tem custo de manutenção.** Colaborador novo, ou remanejado de
  frente, precisa de vínculo atribuído em Gestão de Obras. Foi a falta disso
  que motivou a remoção da primeira vez.

## 3. Aplicação da autorização (backend)

`CurrentUserService` é o **ponto central** de decisão:

| Método | Uso |
|--------|-----|
| `papelAcesso(userId)` | Resolve `PapelAcesso` (ou `null` para usuário inválido/inativo). |
| `isAlfa(userId)` / `isAdmin(userId)` | Papel global. `isAdmin` mantido por compatibilidade. |
| `podeAcessarObra(userId, obraId)` | Decisão booleana: Alfa sempre; Beta só com vínculo ativo. |
| `allowedObraIds(userId)` | `Optional.empty()` (Alfa, global) ou conjunto das obras vinculadas (Beta). |
| `requireAdmin()` / `requireAlfa()` | Exige Alfa (403). |
| `requireWorksiteAccess(obraId)` | Exige acesso à obra (403), inclusive contra IDOR por id. |
| `requireRdoAccess(rdoId)` | Resolve a obra do RDO e delega a `requireWorksiteAccess`. |

Controllers, serviços e consultas por obra passam por esse ponto **no backend** —
o frontend apenas oculta ações proibidas; a segurança real não depende dele.

### Capabilities financeiras

ALFA tem todas as capabilities de forma implícita. BETA precisa, ao mesmo
tempo, de vínculo ativo com a obra e de uma concessão `ATIVA` exata em
`permissao_financeira_colaborador` (V28):

- `FINANCEIRO_VISUALIZAR`: listas, detalhes, Home, relatórios e StavIA;
- `FINANCEIRO_OPERAR`: solicitações, compras, notas e lançamentos;
- `FINANCEIRO_APROVAR`: decisões das etapas persistidas de aprovação;
- `FINANCEIRO_ADMINISTRAR`: catálogos, regras, perfis e cobranças.

Não existe backfill de grants BETA. A mesma política é aplicada antes da query
em controllers, exportações, sync, hidratação da Home e fontes StavIA, evitando
vazamento por totais, options ou evidências.

Gestão de vínculos (exclusiva Alfa): `VinculoColaboradorObraController`
(`GET/POST /api/obras/{obraId}/vinculos`, `DELETE .../{colaboradorId}`).
Cada atribuição/revogação gera evento ontológico e mantém a relação
`COLABORADOR —VINCULADO_A→ OBRA` no grafo (ver §5).

## 4. Stav.IA permission-aware

`CortexStaviaAccessPolicy` (ativa em todos os perfis) deriva permissões e escopo
do mesmo `CurrentUserService`:

- `permissionsFor(userId)`: Alfa e Beta recebem `STAVIA_CONSULTAR`; usuário sem
  papel válido recebe conjunto vazio (consulta negada).
- `canAccessWorksite(userId, worksiteId)`: delega a `podeAcessarObra`.

A filtragem ocorre **antes** de qualquer dado chegar ao modelo de linguagem. O
resolvedor de contexto (`StaviaContextResolutionService`) também é escopado: um
usuário Beta não recebe nomes/opções de obras às quais não tem acesso, evitando
vazamento por desambiguação.

## 5. Ontologia (memória operacional)

Reutiliza `CortexOperationalMemoryService` (idempotente por `id` de evento):

- Evento `VINCULO_OBRA_ATRIBUIDO` / `VINCULO_OBRA_REVOGADO` com
  `beforeState`/`afterState`/`changedFields`, `actorId`, `obraId`,
  `colaboradorId`, versão da entidade e timestamps.
- Relação `VINCULADO_A` criada na atribuição e encerrada
  (`encerrarRelacaoAtiva`) na revogação, mantendo o grafo coerente sem apagar o
  histórico.

## 6. Offline-first e permissões

- O carregamento inicial de obras é filtrado pelo backend
  (`/api/obras/relacionadas` usa o vínculo ativo).
- A sincronização já valida `obraId` autorizado antes de aceitar mutações
  (ver `SyncService`), e continua válida com o modelo de vínculo.
- Eventos financeiros são filtrados também pela capability exigida. Mensagens
  são filtradas por obra/conversa e participação; estar na mesma obra não dá
  acesso automático a uma conversa privada.
- Revogação: o backend bloqueia o acesso online imediatamente; no próximo
  contato o cliente deixa de listar a obra. Operações offline pendentes não são
  apagadas silenciosamente — permanecem para tratamento explícito.

## 7. Independência de banco de dados

A decisão de acesso é **da aplicação**, testável sem banco:

**Portável (qualquer banco relacional):**
- Tabela `vinculo_colaborador_obra` e coluna `papel_acesso` (tipos padrão SQL).
- Consultas em `CurrentUserService` via `JdbcTemplate` (SQL padrão: `EXISTS`,
  `SELECT ... WHERE status = 'ATIVO'`).
- Regras de autorização em Java (repositories/serviços/políticas), cobertas por
  testes unitários com `JdbcTemplate` mockado.

**Específico do MySQL atual (isolável):**
- `metadados_json JSON` e `UUID()` no backfill → em outro banco, usar `TEXT`/tipo
  JSON equivalente e gerar UUID na aplicação.
- `DATETIME(6)`, `ENGINE=InnoDB`, `utf8mb4` → substituíveis por equivalentes.
- Não se usa Row-Level Security de fornecedor como camada única; se o banco de
  destino oferecer RLS, ela pode ser **defesa adicional**, nunca a única.

## 8. Matriz de permissões (resumo)

| Capacidade | Alfa | Beta |
|-----------|:----:|:----:|
| Ver todas as obras | ✅ | ❌ (só vinculadas) |
| Criar / editar / arquivar obra | ✅ | ❌ |
| Atribuir / remover vínculo | ✅ | ❌ |
| Ver/editar RDO da obra vinculada | ✅ | ✅ (só vinculada) |
| RDO/ontologia/anexos de outra obra | ✅ | ❌ (403/404) |
| Integrações Academy/Zeladoria | ✅ | ❌ |
| JSON bruto / endpoints administrativos | ✅ | ❌ |
| PDOR — calcular | ✅ | ❌ |
| PDOR — consultar (obra vinculada) | ✅ | ✅ |
| Stav.IA | ✅ global | ✅ só obras vinculadas |
| Dashboards/relatórios consolidados globais | ✅ | ❌ |
| Financeiro — visualizar | ✅ | ✅ com vínculo + capability |
| Financeiro — operar/aprovar/administrar | ✅ | ✅ com capability exata |
| Mensagem privada fora da conversa | ✅ administrativo | ❌ |

## 9. Gate de produção

O perfil `production` exige ao menos um ALFA ativo com identidade de
autenticação ATIVA e e-mail verificado. A API recusa a inicialização sem esse
estado, além de recusar cookies inseguros, origens não HTTPS, secrets inline,
SMTP fake/incompleto e storage efêmero. `/api/readiness` revalida banco e ALFA
durante a operação.
