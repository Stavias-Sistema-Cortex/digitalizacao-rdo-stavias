# CPF + OTP primário — evidência de entrega

Data: 2026-07-26
Escopo: `feat/cortex-pdf-gate-execution`, depois da migração Flyway V60.

## Contrato de entrada

- No runtime normal `postgresql`, o CPF identifica a conta canônica em
  PostgreSQL por HMAC; ele nunca cria sessão por si só.
- O Córtex responde de modo não enumerável à solicitação e envia um código de
  uso único somente para `auth_identity.email_autenticacao` da identidade
  elegível. A sessão opaca e o CSRF só são emitidos depois da confirmação do
  código.
- **Enviar código** é a ação principal da página de login. **Entrar com
  passkey** permanece uma alternativa online explícita.
- Academy e Zeladoria não entram na requisição pública de login. Elas continuam
  apenas como fontes MySQL `SELECT`-only para provisionamento/sincronização;
  o estado e a identidade operacional vivem no PostgreSQL `StaviasCortex`.
- O offline é uma fronteira distinta: em um navegador já preparado, o app abre
  o cofre local somente com passkey PRF e grant assinado. CPF, e-mail, OTP e
  PIN não são fallback offline, pois não provam posse do segredo local.

## Endurecimento aplicado

- O identificador é limitado antes da canonicalização e do lookup HMAC.
- `/api/auth/email/challenges` e sua verificação aceitam no máximo 4 KiB no
  nginx e em filtro API pré-MVC, incluindo requisições sem `Content-Length`.
- Emissão e verificação têm limites separados; a verificação consome orçamento
  por origem, global e desafio antes de bloquear/ler o desafio no banco.
- O desafio é uso único, expira e continua sob cookies host-only, CSRF e a
  política pública estrita já existente.

## Evidências automatizadas desta revisão

| Verificação | Resultado |
| --- | --- |
| `mvn clean test` com JDK 21 em `apps/api` | 1.039 testes, 0 falhas, 0 erros, 54 ignorados de integrações externas esperadas. |
| `mvn -Ppostgresql-it verify` com JDK 21 em `apps/api` | 157 testes, 0 falhas, 0 erros, 0 ignorados; Flyway aplicou V44–V60 em PostgreSQL descartável. |
| Testes focados de OTP/filtro/controlador | 29 testes, 0 falhas, 0 erros. |
| `npm test -- --run`, lint estrito e build em `apps/web` | 786 testes em 145 arquivos, lint limpo e build de produção aprovado. |
| `npm audit --omit=dev --json` | 0 vulnerabilidades em 78 dependências de produção. |
| `scripts/security/test-local-compose-security.sh` | Contratos PostgreSQL-only, loopback, Config Tree/secrets, fontes MySQL de leitura e hardening de contêiner aprovados. |
| Builds Docker API e web | Imagens `cortex-api:cpf-otp-release` e `cortex-web:cpf-otp-release` construídas. A sintaxe nginx foi validada com o upstream resolvido. |
| Corpo OTP no container web | Um POST de 5.000 bytes em `/api/auth/email/challenges` recebeu HTTP 413 antes de alcançar o upstream. |
| Preview da PWA | `http://127.0.0.1:5177/` respondeu HTTP 200 com CSP e headers de proteção. |
| `git diff --check` | Aprovado. |

## Limites honestos antes de publicação

Esta evidência não equivale a um deploy de produção. Ainda é preciso executar o
checklist de deploy na origem HTTPS final, com PostgreSQL real, SMTP autenticado,
uma identidade ALFA provisionada e os conectores Academy/Zeladoria reais de
leitura. Nenhum usuário, OTP, credencial ou dado operacional fictício foi criado
para demonstrar o fluxo. O scanner gerenciado de segurança do Codex não concluiu
uma execução nesta revisão e não é contado como evidência aprovada.

O Testcontainers passou em PostgreSQL 18.4, mas o Flyway avisou que a versão é
mais nova que sua faixa oficialmente verificada. Faça o smoke de migração no
PostgreSQL alvo antes do cutover.
