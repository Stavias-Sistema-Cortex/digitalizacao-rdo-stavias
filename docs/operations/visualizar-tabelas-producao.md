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
  ferramenta gráfica. A navegação humana usa somente o role de consulta
  `cortex_readonly`, que é `SELECT`-only.
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
- `default_transaction_read_only = on` e `statement_timeout = 30s`
  (ajustáveis por `CORTEX_DB_VIEW_STATEMENT_TIMEOUT`), além de limite de
  conexões e desconexão de transações ociosas;
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
rede interna. O `start` valida a rede, sobe o container, conecta-o à rede
interna e testa o caminho até o PostgreSQL; se algo falhar, remove a ponte.

- `status` mostra o estado e refaz o teste de conectividade.
- `stop` remove a ponte.
- Após um reboot do servidor a ponte **não** volta sozinha (`--restart no`,
  proposital): suba-a de novo com `start` quando alguém precisar consultar.
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
`UPDATE`/`DELETE` acidental é recusado pelo servidor. Consultas com mais de
30 segundos são canceladas pelo `statement_timeout`; se um relatório
legítimo precisar de mais, o administrador ajusta o role, e não a consulta.

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

## Solução de problemas

- **`connection refused` em 127.0.0.1:15432 no computador** — o túnel SSH
  não está aberto (Parte 3), ou a ponte não está de pé no servidor
  (`… db-view-bridge.sh status`).
- **`connection refused` no servidor após reboot** — comportamento esperado;
  suba a ponte de novo com `start`.
- **`port is already allocated` no `start`** — outra coisa ocupa a 15432 no
  servidor; escolha outra porta com `CORTEX_DB_VIEW_PORT` e ajuste o túnel.
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
