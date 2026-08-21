# Visualizar tabelas do Córtex em produção (somente leitura)

Este runbook descreve como um colaborador autorizado da Stavias inspeciona as
tabelas do banco canônico `StaviasCortex` com uma ferramenta gráfica
(pgAdmin 4 ou DBeaver), sem alterar dados e sem expor o PostgreSQL à rede.

O PostgreSQL do Córtex roda no container `cortex-postgres` sobre a rede
Compose `cortex_private`, que é `internal: true` e não publica porta alguma.
Nada escuta em `<ip-do-servidor>:5432` — por contrato:
`scripts/security/test-production-publication.sh` verifica que essa rede
permanece interna. Todo acesso de visualização passa por SSH e pelo loopback
do servidor:

```text
pgAdmin no computador do colaborador
        ↓ 127.0.0.1:15432
     túnel SSH
        ↓
servidor local (WebServerLinux)
        ↓ 127.0.0.1:15432 (ponte de visualização, loopback)
PostgreSQL Docker do Córtex (rede interna)
```

## Limites

- Nunca publique a porta 5432 (ou a ponte) em `0.0.0.0` ou no IP externo do
  servidor. A ponte versionada só aceita loopback.
- Nunca use `cortex_admin`, `cortex_migrator` ou `cortex_runtime` em uma
  ferramenta gráfica. A navegação humana usa `cortex_readonly`, que é
  `SELECT`-only, ou — quando a correção manual for autorizada — o role de
  edição `cortex_editor` descrito na Parte 7.
- Não use MySQL Workbench nem a porta 3306 para o Córtex: MySQL pertence às
  origens Academy/Zeladoria (fonte de leitura), não ao banco canônico.
- A senha do `cortex_readonly` vive em um arquivo `600` no servidor, como os
  demais secrets; não a copie para `.env`, chat, e-mail coletivo ou este
  documento.

## Parte 1 — preparação única pelo administrador (no servidor)

Crie o arquivo de senha do role de consulta e reconcilie o role:

```bash
sudo install -d -m 700 /srv/cortex/secrets
sudo sh -c 'umask 077; openssl rand -base64 24 > /srv/cortex/secrets/cortex-readonly-password'

cd /caminho/do/repositorio
sudo env \
  CORTEX_DOCKER_BIN=/usr/bin/docker \
  CORTEX_DB_VIEW_ROLE_PASSWORD_FILE=/srv/cortex/secrets/cortex-readonly-password \
  bash scripts/deploy/db-view-bridge.sh create-role
```

O subcomando `create-role` é idempotente: pode ser reexecutado para trocar a
senha ou reconciliar as permissões. Ele cria/ajusta o role `cortex_readonly`
com:

- `LOGIN` sem nenhum atributo administrativo (`NOSUPERUSER NOCREATEDB
  NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS`);
- `default_transaction_read_only = on` e `statement_timeout = 30s` (este
  último ajustável por `CORTEX_DB_VIEW_STATEMENT_TIMEOUT`), além de limite
  de conexões (`CORTEX_DB_VIEW_CONNECTION_LIMIT`, padrão 10) e desconexão
  de transações ociosas;
- `SELECT` nas tabelas existentes do schema `public`;
- `ALTER DEFAULT PRIVILEGES FOR ROLE cortex_migrator … GRANT SELECT`, para
  que tabelas criadas por migrações futuras já nasçam legíveis. As migrações
  Flyway rodam como `cortex_migrator` (dono do banco), então os privilégios
  padrão precisam ser declarados para esse role — não para `cortex_admin`,
  que nunca cria tabelas do Córtex.

A senha nunca aparece em argumentos de comando: ela viaja pelo ambiente do
`docker exec` e é lida pelo `psql` com `\getenv`.

Entregue a senha ao colaborador por um canal adequado (gerenciador de senhas
ou presencialmente), nunca por grupo aberto.

## Parte 2 — subir a ponte de visualização (no servidor)

```bash
sudo env CORTEX_DOCKER_BIN=/usr/bin/docker \
  bash scripts/deploy/db-view-bridge.sh start
```

A ponte é um container `socat` endurecido (`--read-only`, `--cap-drop ALL`,
`no-new-privileges`) chamado `cortex-db-view`, que escuta **somente** em
`127.0.0.1:15432` no servidor e encaminha para `cortex-postgres:5432` pela
rede interna. O `start` valida a rede, sobe o container em uma rede Docker
dedicada (`cortex-production_db_view_edge`), conecta-o à rede interna e
testa o caminho até o PostgreSQL; se algo falhar, remove a ponte e a rede.

A rede dedicada é parte da proteção: o `socat` aceita qualquer origem
dentro da própria rede, então deixá-lo na rede bridge padrão daria a
qualquer outro container do servidor um caminho direto até o banco, sem
SSH e sem passar pelo loopback.

- `status` mostra o estado, a porta realmente publicada e refaz o teste de
  conectividade.
- `stop` remove a ponte e a rede dedicada.
- Após um reboot do servidor a ponte **não** volta sozinha (`--restart no`,
  proposital): o container fica parado (`Exited`) e o próximo `start`
  remove esse resto e sobe uma ponte nova — basta rodar `start`.
- No primeiro `start` o Docker baixa a imagem `alpine/socat` fixada no
  script.

## Parte 3 — abrir o túnel no computador do colaborador

Pré-requisitos: uma conta SSH no servidor (ex.: `sistema@192.168.0.15`) e o
pgAdmin 4 (ou DBeaver) instalado. Não é preciso instalar PostgreSQL na
máquina do colaborador; se houver um instalado, ele não participa — é apenas
cliente visual.

Abra um terminal (PowerShell no Windows; Terminal no macOS/Linux) e execute:

```bash
ssh -N -L 15432:127.0.0.1:15432 sistema@192.168.0.15
```

Digite a senha do usuário SSH. O comando fica "parado" sem imprimir nada:
isso é o túnel aberto. Deixe a janela aberta enquanto consulta;
`Ctrl+C` encerra o túnel.

Teste em outro terminal:

```bash
nc -vz 127.0.0.1 15432        # macOS/Linux — deve responder "succeeded"
Test-NetConnection 127.0.0.1 -Port 15432   # Windows PowerShell — TcpTestSucceeded : True
```

### Variante avançada (macOS/Linux): túnel em segundo plano

```bash
ssh -M -S /tmp/cortex-db-ssh \
  -o ControlPersist=2h \
  -o ServerAliveInterval=30 \
  -L 127.0.0.1:15432:127.0.0.1:15432 \
  -Nf sistema@192.168.0.15
```

Para encerrar:

```bash
ssh -S /tmp/cortex-db-ssh -O exit sistema@192.168.0.15
```

### Variante sem ponte (avançada, opcional)

É possível encaminhar direto para o IP interno do container
(`ssh -L 15432:<ip-do-container>:5432 …`, descobrindo o IP com
`docker inspect`). Isso depende de a versão do Docker do servidor permitir
tráfego do host para a rede `internal`, e o IP muda quando o container é
recriado. A ponte versionada existe para dar um destino estável
(`127.0.0.1:15432`) que não exige `docker inspect` de cada colaborador.

## Parte 4 — configurar o pgAdmin

1. Abra o **pgAdmin 4**.
2. Clique com o botão direito em **Servers** → **Register → Server**.
3. Aba **General**:

   | Campo | Valor |
   |---|---|
   | Name | `Córtex produção — somente leitura` |

4. Aba **Connection**:

   | Campo | Valor |
   |---|---|
   | Host name/address | `127.0.0.1` |
   | Port | `15432` |
   | Maintenance database | `StaviasCortex` |
   | Username | `cortex_readonly` |
   | Password | a senha entregue pelo administrador |

   Em computador compartilhado, não marque **Save password**.

5. Clique em **Save**.

Não selecione um PostgreSQL local na porta `5432`: o Córtex está na porta
local `15432`, através do túnel. No DBeaver, os mesmos valores valem em
**Nova conexão → PostgreSQL** (o DBeaver também pode abrir o túnel sozinho
na aba **SSH**, dispensando a Parte 3).

## Parte 5 — consultar as tabelas

Em **Servers → Córtex produção — somente leitura → Databases →
StaviasCortex**, clique com o botão direito e escolha **Query Tool**.
O navegador de objetos lista as tabelas em
**Schemas → public → Tables** (equivalente ao `\dt` do psql).

Exemplos:

```sql
-- Os RDOs mais recentes, com a obra
SELECT r.numero_rdo,
       r.data_rdo,
       r.status,
       o.nome AS obra,
       r.atualizado_em
FROM rdo r
JOIN obra o ON o.id = r.obra_id
ORDER BY r.atualizado_em DESC
LIMIT 20;

-- Total de RDOs por status
SELECT status, COUNT(*) AS total
FROM rdo
GROUP BY status
ORDER BY total DESC;
```

O role já abre toda transação como somente leitura, então um
`UPDATE`/`DELETE` acidental é recusado. A garantia real contra escrita é a
ausência de qualquer permissão de escrita: mesmo desligando o modo somente
leitura da sessão, um `INSERT` continua recusado por falta de privilégio.

Já `default_transaction_read_only`, `statement_timeout` (30s) e
`idle_in_transaction_session_timeout` são **padrões de sessão**, não limites
impostos pelo servidor: a própria sessão pode sobrescrevê-los com `SET`.
Eles protegem contra acidentes, não contra uso deliberado — o único limite
rígido é o `CONNECTION LIMIT`. Não use `SET` para esticar esses limites: se
um relatório legítimo precisar de mais de 30 segundos, peça ao
administrador para ajustar o role (`CORTEX_DB_VIEW_STATEMENT_TIMEOUT` e
reexecutar o `create-role`).

Consulta rápida sem interface gráfica, direto no servidor:

```bash
sudo docker exec -it cortex-production-cortex-postgres-1 \
  psql -U cortex_readonly -d StaviasCortex
```

(`\dt` lista tabelas, `\d+ rdo` mostra a estrutura, `\q` sai.)

## Parte 6 — encerrar com segurança

1. Desconecte o servidor no pgAdmin (botão direito → **Disconnect from
   server**) ou feche o pgAdmin.
2. Encerre o túnel: `Ctrl+C` na janela do SSH (ou `ssh -S /tmp/cortex-db-ssh
   -O exit sistema@192.168.0.15` na variante avançada).
3. Quando ninguém mais for consultar, o administrador remove a ponte no
   servidor:

   ```bash
   sudo env CORTEX_DOCKER_BIN=/usr/bin/docker \
     bash scripts/deploy/db-view-bridge.sh stop
   ```

O Córtex continua funcionando normalmente durante e depois de tudo isso; a
ponte e o túnel só existem para leitura humana.

## Parte 7 — acesso de edição (`cortex_editor`)

Quando um operador autorizado precisa **corrigir dados** e não apenas
consultá-los, existe um segundo role, provisionado por
`scripts/deploy/db-editor-role.sh`. Ele usa a mesma ponte e o mesmo túnel; o
que muda são as permissões.

### O que ele pode e o que não pode

- **Pode**: `SELECT`, `INSERT`, `UPDATE` e `DELETE` em todas as tabelas do
  schema `public`, além de usar as sequências. Tabelas criadas por migrações
  futuras já nascem acessíveis, pelo mesmo mecanismo de privilégios padrão do
  runtime.
- **Não pode**: criar, alterar ou remover tabela, índice ou coluna; ser dono
  de objeto; virar superusuário; criar outros roles. Mudança de schema
  continua exclusivamente com o Flyway, para que o banco nunca divirja do
  histórico de migrações.

### O que o role não protege

Estas são as razões pelas quais ele é entregue sob autorização explícita, e
não como acesso padrão:

- **Regra de negócio é da aplicação, não do banco.** Um `UPDATE` manual não
  passa pelas validações da API, então consegue produzir estados que o
  sistema nunca criaria — um RDO aprovado sem execução, por exemplo.
- **Rastreio e sincronização.** O Córtex mantém trilha canônica de mutação e
  controle de versão de linha (`versao_linha`) para o sincronismo offline.
  Escrita direta não gera os eventos correspondentes e pode confundir a
  reconciliação de um dispositivo que estava offline.
- **Não há desfazer.** Não existe rollback de um `UPDATE` já confirmado; a
  recuperação é restaurar backup, o que descarta tudo que veio depois.

### Prática obrigatória ao editar

1. Faça um backup antes (`scripts/deploy/backup-local-production.sh`).
2. Trabalhe dentro de transação e confira antes de confirmar:

   ```sql
   BEGIN;
   UPDATE rdo SET status = 'RASCUNHO' WHERE id = '...';
   SELECT id, numero_rdo, status FROM rdo WHERE id = '...';
   -- confira o resultado; então COMMIT; ou ROLLBACK;
   ```

3. Sempre com `WHERE` por chave primária. Nunca `UPDATE`/`DELETE` sem `WHERE`.
4. Prefira corrigir pela aplicação quando houver caminho por ela.

### Provisionar

```bash
sudo sh -c 'umask 077; openssl rand -base64 24 \
  > /srv/cortex/secrets/cortex-editor-password'

sudo env CORTEX_DOCKER_BIN=/usr/bin/docker \
  CORTEX_DB_EDITOR_ROLE_PASSWORD_FILE=/srv/cortex/secrets/cortex-editor-password \
  bash scripts/deploy/db-editor-role.sh create-role
```

O comando é idempotente: reexecutá-lo com um novo arquivo troca a senha e
reconcilia as permissões. Ele recusa apontar para um role já existente que
tenha privilégios, heranças ou objetos próprios, e recusa reutilizar
`cortex_admin`, `cortex_migrator`, `cortex_runtime` ou `cortex_readonly`.

Ajustes opcionais: `CORTEX_DB_EDITOR_ROLE` (nome, padrão `cortex_editor`),
`CORTEX_DB_EDITOR_STATEMENT_TIMEOUT` (padrão `60s`),
`CORTEX_DB_EDITOR_CONNECTION_LIMIT` (padrão `4`).

### Retirar o acesso

```bash
sudo env CORTEX_DOCKER_BIN=/usr/bin/docker \
  bash scripts/deploy/db-editor-role.sh revoke
```

Tira o login, remove o `CONNECT` e encerra as sessões abertas, sem apagar o
role. Use ao fim de uma janela de manutenção ou quando a pessoa deixar a
função; para devolver o acesso, rode `create-role` de novo com uma senha
nova.

### No pgAdmin

Mesma conexão da Parte 4, trocando o usuário para `cortex_editor` e a senha.
Vale cadastrar como um **servidor separado**, com nome explícito (por
exemplo `Córtex — EDIÇÃO`), em vez de editar a conexão de leitura: assim
ninguém escreve por engano achando que está na sessão somente leitura.

## Solução de problemas

- **`connection refused` em 127.0.0.1:15432 no computador** — o túnel SSH
  não está aberto (Parte 3), ou a ponte não está de pé no servidor
  (`… db-view-bridge.sh status`).
- **`connection refused` no servidor após reboot** — comportamento esperado;
  rode `start` de novo (ele mesmo limpa o container parado que sobrou).
- **`is already running; run stop first`** — já existe uma ponte de pé;
  confira com `status` e use `stop` antes de subir outra.
- **`port is already allocated` no `start`** — outra coisa ocupa a 15432 no
  servidor; escolha outra porta com `CORTEX_DB_VIEW_PORT` e ajuste o túnel.
  Ao trocar a porta, exporte a mesma variável nas próximas chamadas ou use
  o `status`, que lê a porta publicada direto do container.
- **`The Compose network 'cortex-production_cortex_private' does not
  exist`** — o stack de produção não está de pé, ou roda com outro nome de
  projeto; confira com `docker network ls` e ajuste
  `CORTEX_COMPOSE_PROJECT_NAME`.
- **Senha recusada no pgAdmin** — confirme usuário `cortex_readonly` e porta
  `15432`; o administrador pode redefinir a senha reexecutando o
  `create-role` com um novo arquivo de senha.
- **Sessão derrubada no meio de uma transação parada** — proteção
  intencional (`idle_in_transaction_session_timeout = 5min`); reconecte.
- **Verificação de contrato** — `bash scripts/deploy/test-db-view-bridge.sh`
  valida o script da ponte sem tocar em Docker ou banco reais.
