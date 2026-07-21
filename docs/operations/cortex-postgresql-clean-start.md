# Córtex PostgreSQL: clean start operacional

Este runbook descreve a fundação PostgreSQL limpa do Córtex. Ele não é uma
conversão automática do runtime MySQL atual, não importa dados legados e não
autoriza o início do shell operacional.

Leia este documento antes de executar qualquer comando de transição. Os
comandos abaixo são para um operador autorizado; esta documentação não executa
nem recomenda transições no banco local de outra pessoa.

## Limites de dados e responsabilidade

- `StaviasCortex` em PostgreSQL é o único banco canônico para novos dados
  operacionais do Córtex. O baseline V44 começa sem RDOs, obras, sessões,
  eventos, mensagens, anexos, registros offline ou dados financeiros legados.
- MySQL é **source-only** para Academy e Zeladoria. Esses adaptadores usam
  contas com `SELECT` no nível do banco e nunca recebem uma escrita de Córtex.
  Academy e Zeladoria não são o banco primário, não fornecem senhas ao Córtex e
  não são um provedor de identidade do runtime.
- Arquivos não entram no PostgreSQL. O PostgreSQL guarda o `storage_key`, hash,
  tamanho, tipo e permissões; os bytes pertencem somente ao storage de objetos
  aprovado (local persistente ou bucket S3 compatível privado). Isso também se
  aplica a importações, contexto StavIA, fotos e anexos.
- Não adicione uma camada de banco gerenciada de terceiros, importação MySQL,
  dual write, reset destrutivo do Flyway ou baseline automático nesta fundação.

## Materiais e segredos

Parta de [`.env.postgresql.example`](../../.env.postgresql.example) para os
nomes e valores não secretos. O arquivo local derivado não deve ser versionado.

Forneça senhas, chaves e a identificação do proprietário por mecanismos de
segredo do ambiente ou arquivos protegidos. Os comandos recebem caminhos de
arquivo e nomes de variáveis; não coloque valores secretos, CPF, senha de
Academy/Zeladoria, chaves HMAC ou tokens em argumentos, `.env` versionado,
histórico de terminal ou logs.

Em desenvolvimento local com uma política PostgreSQL explicitamente confiável,
o arquivo de senha PostgreSQL pode ficar ausente e o servidor decide se aceita
a conexão sem senha. Em produção, forneça obrigatoriamente
`CORTEX_POSTGRES_PASSWORD_FILE`; valores em `CORTEX_POSTGRES_PASSWORD` ou
`PGPASSWORD` são recusados pelos scripts.

Antes da ativação, confirme que os seguintes grupos estão configurados fora do
repositório:

- conexão PostgreSQL para o banco chamado exatamente `StaviasCortex`;
- caminhos de arquivo para as chaves HMAC de CPF e OTP;
- `CORTEX_BOOTSTRAP_ADMIN_CPF_FILE` apontando para um arquivo `0600` que será
  removido após o bootstrap;
- conexões Academy e Zeladoria separadas, somente leitura;
- SMTP autenticado para entrega de OTP;
- storage de objetos persistente e privado;
- build web com `VITE_CORTEX_AUTH_MODE=postgresql` e a origem `/api` correta.

Nunca copie um segredo real para este documento. O CPF do primeiro ALFA não é
um argumento de linha de comando, nem aparece em retorno de erro, Memória ou
evidência operacional.

## Estados e transições permitidos

Há cinco estados e quatro transições deliberadas. Cada transição é separada e
requer confirmação do operador; não encadeie tudo num único comando.

```text
PostgreSQL provisionado e vazio
  -- postgresql-migrate --> V44 instalado
  -- postgresql-bootstrap --> ALFA inicial criado
  -- postgresql-activation --> servidor restrito de ativação
  -- preflight postgresql --> runtime normal reservado (permanece bloqueado)
```

| Transição | Perfil público | Launcher permitido | Resultado esperado |
| --- | --- | --- | --- |
| 1. Migrar | `postgresql-migrate` | `PostgresqlMigrationApplication` | Flyway instala somente V44 em `StaviasCortex`. |
| 2. Bootstrap | `postgresql-bootstrap` | `PostgresqlBootstrapApplication` | Cria uma única identidade ALFA a partir da consulta Academy somente leitura. |
| 3. Ativar | `postgresql-activation` | `PostgresqlActivationApplication` | Expõe somente health, readiness e os dois endpoints de OTP por e-mail. |
| 4. Preflight normal | `postgresql` | verificador de release, sem launcher web | Recusa o release enquanto não existir um slice operacional PostgreSQL-safe. |

O preflight não é uma transição de serviço normal. Ele existe para demonstrar
que a configuração ainda falha fechada. Não inicie `CortexApplication` nem um
controller operacional como parte deste runbook.

## 0. Provisionar o banco vazio

Provisione o banco exato `StaviasCortex` com a política de backup, acesso e
retenção aprovada pela organização. Não aponte o processo para o banco MySQL
de produção, e não execute um importador legado.

O auxiliar abaixo somente cria/verifica o banco; ele não migra, não faz
bootstrap e não copia dados:

```bash
./scripts/dev/init-postgres-cortex.sh
```

Faça um checkpoint: a base precisa estar vazia de dados de produto antes da
próxima etapa. A única linha estrutural permitida após a migração será a
sequência de commit de eventos do Córtex.

## 1. Instalar V44 uma única vez

Após conferir a URL não secreta do PostgreSQL e os caminhos de segredo no
ambiente do operador, execute somente o migrador isolado:

```bash
./scripts/dev/migrate-postgres-cortex.sh
```

O comando deve iniciar `PostgresqlMigrationApplication` com
`postgresql-migrate`. Ele não deve iniciar a API web, baixar dados do MySQL,
criar um ALFA ou habilitar o runtime. Interrompa se a saída indicar um banco
com outro nome, uma migração anterior divergente ou qualquer tentativa de
importação.

## 2. Criar o ALFA inicial

Somente depois de V44 concluído, o operador fornece o caminho do arquivo do
segredo de bootstrap e habilita o bootstrap de forma explícita no processo. O
script dedicado deve chamar apenas `PostgresqlBootstrapApplication` com
`postgresql-bootstrap`:

```bash
CORTEX_BOOTSTRAP_ADMIN_CPF_FILE=/caminho/protegido/owner-id \
  ./scripts/dev/bootstrap-postgres-alfa.sh
```

O processo consulta Academy com parâmetro protegido, valida que existe uma
única conta ativa com e-mail elegível e grava em PostgreSQL o colaborador, a
identidade, o papel ALFA, a capacidade administrativa, o recibo idempotente e
um evento de Memória redigido. Ele não lê senha da Academy, não escreve em
MySQL e não inclui CPF/e-mail completo no evento.

Repita o comando somente para verificar a idempotência esperada
`ALREADY_APPLIED`; uma fonte diferente ou conflitante deve falhar fechada.
Após sucesso, remova o arquivo de segredo e registre a remoção pelo processo
operacional aprovado.

## 3. Servir somente a ativação inicial

Inicie o launcher restrito, não o launcher normal:

```bash
./scripts/dev/start-postgres-activation.sh
```

O perfil `postgresql-activation` aceita apenas `OPTIONS`, health, readiness,
solicitação de desafio OTP de e-mail e verificação do desafio. Todo outro
`/api/**`, inclusive login CPF, passkey, Obras, RDO, Memória, sincronização,
upload, mapa, financeiro, mensagens e administração, deve responder o código
estável `CORTEX_ACTIVATION_ONLY` com `503` e `Cache-Control: no-store`.

O frontend identifica esse código antes de importar a PWA, shell, IndexedDB,
cofre offline ou sincronização. Ele mostra somente a tela de ativação por
e-mail; após verificar o OTP, confirma o estado terminal e não navega para
uma rota operacional.

## 4. Preflight do runtime normal: bloqueado nesta entrega

O valor inicial é literalmente:

```text
CORTEX_POSTGRES_RUNTIME_READY=false
```

Mesmo que um operador mude a variável para `true`, o release normal ainda deve
ser recusado nesta entrega porque o registro de superfícies PostgreSQL-safe
está vazio. O preflight abaixo só inspeciona essa condição; ele não inicia a
aplicação normal:

```bash
CORTEX_POSTGRES_RUNTIME_READY=true \
  ./scripts/dev/check-postgres-runtime-release.sh
```

O resultado correto hoje é recusa. As superfícies ainda intencionalmente não
portadas/verificadas incluem Obras, RDO, Memória, sincronização e outbox,
financeiro, mensagens, anexos/upload, mapas, rotas administrativas e demais
controllers do shell. Uma futura entrega precisa registrar e testar um slice
vertical PostgreSQL-safe antes de mudar esse estado; `runtime-ready=true` por
si só nunca é autorização de release.

## Verificação segura e evidência

Use a verificação automatizada em containers descartáveis e fixtures sintéticas:

```bash
./scripts/dev/verify-postgres-cortex-clean-start.sh
```

Ela não deve apagar, migrar, importar nem consultar a base local do
proprietário por padrão. A evidência mínima de um ensaio descartável é:

1. V44 aplicado exatamente uma vez numa instância PostgreSQL 18 vazia;
2. nenhuma linha de negócio copiada, exceto o controle estrutural de sequência;
3. bootstrap sintético `CREATED`, seguido de `ALREADY_APPLIED` sem duplicatas;
4. evento de Memória redigido, sem CPF, e-mail completo ou credenciais;
5. rota de ativação limitada aos endpoints permitidos;
6. OTP sintético cria sessão ALFA, mas uma rota do shell continua negada;
7. runtime normal recusa tanto com o gate `false` quanto com `true` enquanto
   não houver slice PostgreSQL-safe registrado.

Para uma transição real em `StaviasCortex`, trate backup, rollback, SMTP,
storage, fontes MySQL somente leitura e aprovação do proprietário como uma
operação separada. Este runbook não substitui essa decisão.

## Handoff para uma futura release operacional

Antes de liberar qualquer módulo, registre no handoff:

- versão V44 e resultado de validação Flyway;
- perfil executado e launcher correspondente;
- estado do bootstrap sem dados de identidade;
- status de health/readiness da ativação;
- resultado recusado do preflight normal;
- configuração por arquivo/secret manager de PostgreSQL, Academy/Zeladoria,
  SMTP, object storage e APIs de mapa;
- o slice PostgreSQL-safe, os controllers, queries e testes que serão
  explicitamente adicionados na próxima aprovação.

Não faça stage, commit, deploy ou transição local como parte da documentação.
