# MCP Render para Claude Code

Este documento descreve a conexão do servidor MCP da Render ao Claude Code
neste repositório. É uma configuração de **ferramenta de desenvolvimento**: ela
não altera o runtime do Córtex, não adiciona dependência de deploy e não muda a
topologia de produção descrita em `docs/production-runbook.md`.

O Córtex não é hospedado na Render. Não há `render.yaml` nem serviço Render no
runtime deste projeto, e este documento não propõe migrar nada para lá. A
conexão serve apenas para consultar e operar recursos Render que já existam na
conta de quem usa o Claude Code.

## Configuração no repositório

`.mcp.json` na raiz declara o servidor com escopo de projeto:

```json
{
  "mcpServers": {
    "render": {
      "type": "http",
      "url": "https://mcp.render.com/mcp"
    }
  }
}
```

O endpoint é `https://mcp.render.com/mcp`. O host sem o caminho `/mcp`
responde `404`; o caminho correto responde `401` até a autenticação.

Servidores MCP com escopo de projeto exigem aprovação explícita. Ao abrir o
Claude Code no repositório pela primeira vez após esta mudança, aprove o
servidor `render` quando solicitado. Para revisar a decisão depois, use
`claude mcp reset-project-choices`.

## Autenticação

O endpoint aceita dois métodos. O arquivo versionado não contém credencial
nenhuma, e nenhuma das opções abaixo deve ser commitada.

### OAuth (padrão, recomendado para Claude Code)

É o caminho documentado pela Render para o Claude Code e o padrão desta
configuração. Cada pessoa autentica com a própria conta Render, e o acesso
segue as permissões dessa conta.

1. Abra o Claude Code no repositório.
2. Execute `/mcp`.
3. Selecione `render` e depois `Authenticate`.
4. Conclua o fluxo no navegador.

O token fica no perfil local do Claude Code, fora do repositório.

### Chave de API (ambientes headless)

Em ambientes sem navegador — CI ou containers remotos — use uma chave de API no
cabeçalho `Authorization`. Não edite o `.mcp.json` versionado para isso;
registre o servidor em escopo local, que grava fora do repositório:

```bash
claude mcp add --transport http render-local --scope local \
  https://mcp.render.com/mcp \
  --header "Authorization: Bearer ${RENDER_API_KEY}"
```

Exporte `RENDER_API_KEY` a partir do gerenciador de secrets da máquina ou do
runner. A chave gerada em *Account Settings* na Render é credencial de conta
inteira: ela não tem escopo por serviço, não deve ser compartilhada entre
pessoas e não pertence a `.env`, ao histórico do Git nem a logs de CI. Revogue
na Render qualquer chave exposta.

## Verificação

```bash
claude mcp list
```

`⏸ Pending approval` indica que falta aprovar o servidor de projeto.
`! Needs authentication` indica que falta o passo de OAuth ou a chave de API.
`✔ Connected` confirma a conexão.

Alterações em `.mcp.json` só valem para sessões novas do Claude Code; uma
sessão já aberta continua com a configuração que carregou ao iniciar.

## Escopo de acesso

Autenticado, o servidor expõe operações sobre a conta Render conectada:
workspaces, serviços, deploys, logs, métricas, Render Postgres e Render Key
Value. Isso inclui operações de escrita, como criar serviços e disparar
deploys. Trate a sessão autenticada com o mesmo cuidado de um terminal logado
na conta Render.
