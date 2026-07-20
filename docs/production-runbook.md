# Runbook de produção — Córtex

## Topologia suportada

A PWA e a API devem ser publicadas na mesma origem HTTPS. O container web serve
os assets e encaminha `/api/*` para `cortex-api:8080`; isso mantém cookies de
sessão e CSRF host-only. O banco é MySQL externo. Objetos ficam em bucket S3
privado ou no volume persistente `cortex_object_data`.

`compose.production.example.yml` é um exemplo de topologia, não contém secrets
nem cria um banco de produção. Copie os valores de `.env.example` para o secret
manager do ambiente e aponte as cinco entradas `*_SECRET_FILE` para arquivos
locais de implantação com permissões restritas.

## Preparação de chaves

- CPF e OTP: materiais aleatórios independentes, pelo menos 32 bytes.
- Offline grant: chave privada RSA PKCS#8 e chave pública SubjectPublicKeyInfo
  em PEM. Monte ambas na API.
- Calcule o SHA-256 base64url sem padding do DER da chave pública e forneça em
  `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` no build web.
- Rotacione CPF HMAC usando `previous-key-*` durante a janela de transição; não
  reutilize a chave OTP, SMTP ou offline.

## Cutover

1. Restaure uma cópia do banco e ensaie a atualização Flyway.
2. Confirme um ALFA ativo com identidade ATIVA e e-mail já verificado.
3. Configure SMTP real, storage persistente, origens HTTPS exatas e secrets por
   arquivo.
4. Valide `docker compose ... config` e construa imagens imutáveis.
5. Inicie a API com o scheduler de cobrança desativado.
6. Aguarde `/api/readiness`, faça login OTP/passkey e valide uma obra QA.
7. Inicie a PWA e execute `scripts/smoke-deploy.sh`.
8. Habilite o scheduler somente depois do preview de uma regra QA e de um envio
   único confirmado no provider.

## Observabilidade operacional

Alertar para:

- readiness diferente de `READY`;
- aumento de 401/403 fora de um deploy ou revogação esperada;
- mutações de sync em erro/retry por mais de uma janela;
- cobranças `FALHA` ou leases vencidos;
- falhas de storage e diferença entre metadata e objeto;
- SMTP timeout/resultado ambíguo (não reenviar manualmente sem verificar o
  provider e a chave idempotente);
- ausência de evidência/frescor em respostas StavIA.

Logs devem carregar correlation ID, entidade e resultado, nunca CPF, OTP,
cookie, segredo, corpo de mensagem ou anexo.

## Incidentes

### Banco indisponível

Retire a instância do balanceador quando readiness falhar. Não desative Flyway
nem o gate de ALFA. Recupere o banco e confirme a fila idempotente antes de
recolocar tráfego.

### SMTP indisponível ou ambíguo

Pause `CORTEX_FINANCE_EMAIL_SCHEDULER_ENABLED`. Consulte o provider pelo ID de
tentativa antes de liberar retry; resultado ambíguo deve falhar fechado para não
duplicar cobrança.

### Storage indisponível

Bloqueie novos uploads, preserve a metadata e restaure o backend/volume. Nunca
marque objeto como concluído sem confirmar conteúdo e SHA-256.

### Revogação de acesso

Revogue a sessão/vínculo/grant no servidor. O backend bloqueia online de forma
imediata; pendências locais permanecem visíveis para resolução e não são
apagadas silenciosamente.
