# Keep-warm da API do Córtex

Worker do Cloudflare com Cron Trigger que toca `/api/wake` a cada cinco minutos
para que a instância da API não hiberne.

## Por que existe

A instância da API hiberna após cerca de quinze minutos sem tráfego, e a subida
a frio é o que o apontador sente ao entrar de manhã.

Já havia um ping agendado em `.github/workflows/api-keepwarm.yml` pedindo
`*/10 * * * *`. O agendador do GitHub Actions não cumpre esse horário. Medido
sobre 27 execuções reais do próprio repositório:

| intervalo entre pings | |
|---|---|
| mínimo | 2 min |
| **mediana** | **71 min** |
| máximo | 217 min |

Com uma janela de hibernação de quinze minutos, um ping mediano de 71 minutos
não mantém nada aquecido. O workflow continua no lugar como rede de segurança,
mas quem sustenta a janela é este Worker.

## Implantar

Requer `CLOUDFLARE_API_TOKEN` com permissão de editar Workers e o
`CLOUDFLARE_ACCOUNT_ID` da conta que já hospeda o Pages.

```bash
cd deploy/keepwarm
npx wrangler deploy --var CORTEX_WAKE_ORIGIN:https://SUA-API.onrender.com
```

`CORTEX_WAKE_ORIGIN` é a origem direta da API. O endereço público do site
também funcionaria, porque o Pages encaminha `/api/*`, mas gastaria uma
invocação de Function a cada cinco minutos para chegar ao mesmo lugar.

Sem a variável o Worker registra o motivo e não envia nada — ele não adivinha
endereço.

## Verificar

```bash
npx wrangler tail cortex-keepwarm
```

Cada disparo registra uma linha `aquecimento: <status> em <ms>ms`. Depois do
primeiro toque do dia os tempos devem ficar na casa das centenas de
milissegundos; se voltarem à casa dos segundos, a janela está sendo perdida.

## Por que `/api/wake` e não `/api/readiness`

Os dois acordam o contêiner. A prontidão cobra junto um round trip completo no
object storage (PUT, GET e DELETE) e a evidência de release — repetido 288
vezes por dia, é escrita e leitura no bucket sem nenhum leitor. `/api/wake`
aquece exatamente o que entrar no sistema exige: o contêiner de pé, uma conexão
no pool e o banco fora da suspensão automática.
