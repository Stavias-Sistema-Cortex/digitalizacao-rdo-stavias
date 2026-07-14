# Login direto por CPF com passkey minimalista

Data: 2026-07-14

## Objetivo

Simplificar o acesso no canteiro para uma etapa direta por CPF, sem depender de e-mail antes da entrada. Preservar a sessão opaca atual, os limites de acesso Alfa/Beta e a passkey como alternativa discreta para quem já a configurou.

## Decisão de produto

- O formulário inicial terá somente o campo `CPF` e o botão primário `Entrar`.
- A passkey continuará disponível como uma ação secundária visualmente discreta, com o texto `Usar passkey`, sem divisor `OU`, card ou explicação extensa.
- O login não solicitará, enviará nem verificará código por e-mail.
- Recursos de e-mail continuarão disponíveis somente dentro da aplicação autenticada.
- O acesso offline continuará protegido pela passkey registrada no dispositivo. CPF isolado não liberará o cofre offline.

O CPF isolado é uma prova de identidade mais fraca que OTP ou passkey. Esse risco é uma decisão operacional explícita para reduzir atrito no canteiro. A implementação deve limitar tentativas, evitar respostas que enumerem colaboradores e manter a passkey como alternativa mais forte.

## Experiência da tela

1. A pessoa informa o CPF cadastrado no Academy.
2. O cliente valida somente formato e dígitos verificadores.
3. Ao selecionar `Entrar`, a tela exibe `Entrando...` e bloqueia novo envio até a resposta.
4. Abaixo do botão principal aparece apenas `Usar passkey` em estilo secundário.
5. Falhas de CPF inexistente, colaborador inativo, identidade bloqueada ou papel inválido produzem a mesma mensagem pública.
6. Sem conexão, o CPF não cria sessão. A pessoa que já configurou uma passkey pode usar o fluxo offline existente.

Não haverá segunda etapa, contagem regressiva, reenvio, campo de código ou texto sobre e-mail.

## Arquitetura e fluxo de dados

### Frontend

- `LoginPage.tsx` volta a ser um formulário de uma etapa.
- `authApi.ts` expõe uma operação de login direto que envia apenas `{ cpf }` para `POST /api/auth/login`.
- `authService.ts` normaliza o CPF, recebe o perfil seguro da sessão e o mantém somente em memória pelo mecanismo atual.
- O cliente não recebe token, CPF mascarado, endereço de e-mail ou qualquer segredo.
- O fluxo de passkey existente continua funcional. Apenas sua apresentação visual será reduzida.

### Backend

- `POST /api/auth/login` permanece como o único endpoint público de CPF.
- O backend normaliza e valida o CPF, localiza uma identidade ativa pela fronteira HMAC/legado já existente e rejeita identidades ambíguas, bloqueadas, inativas, removidas ou com papel não canônico.
- Uma identidade válida é convertida em `AuthenticatedIdentity` e entregue ao `AuthSessionService` atual.
- O servidor cria a mesma sessão opaca revogável usada por OTP/passkey, grava apenas o cookie HttpOnly e retorna `AuthSessionResponse` com o escopo Alfa/Beta.
- O endpoint aplica limite por IP, identificador e volume global com chaves protegidas por HMAC.
- As rotas de desafio por e-mail deixam de ser públicas. Uma requisição sem sessão para essas rotas recebe `401` antes de alcançar o controller.

### Passkey e offline

- `Entrar com passkey` passa a ser apresentado como `Usar passkey`, sem alterar WebAuthn, cookie de sessão ou verificação criptográfica.
- O cadastro de passkey permanece após o acesso, em `Segurança do dispositivo`.
- O desbloqueio offline continua aceitando somente a passkey vinculada ao cofre local. Não haverá fallback offline por CPF, Bloom filter ou PIN.

## E-mail após o acesso

Esta mudança remove e-mail somente da autenticação pública. Os fluxos internos de financeiro, cobranças, documentos e outras mensagens por e-mail continuam atrás da sessão e das permissões já existentes. Nenhum endereço ou provedor será hardcoded no login.

## Erros e segurança

- CPF malformado: `400`, sem consulta de identidade.
- CPF não elegível: `401` com mensagem genérica.
- Limite excedido: `429` com mensagem genérica e sem indicar se o CPF existe.
- Conflito de identidade: falha fechada; nenhuma sessão ou cookie é criado.
- Falha ao emitir sessão: nenhuma sessão parcial é preservada no cliente.
- Todas as respostas de autenticação usam `Cache-Control: no-store`.
- A API não registra CPF bruto, cookie, token de sessão ou material HMAC.

## Testes e critérios de aceite

### Backend

- CPF de colaborador ativo cria uma sessão opaca e retorna somente o perfil autorizado.
- CPF inválido, inexistente, inativo, bloqueado, ambíguo ou com papel inválido não cria sessão nem cookie.
- Limite de tentativas impede nova autenticação sem consultar ou revelar a identidade.
- As rotas de e-mail não são públicas.
- Login, sessão atual, logout, passkey e escopo de obras continuam cobertos.

### Frontend

- O formulário contém somente CPF e a ação primária `Entrar`.
- Não existem campo de código, reenvio, contagem regressiva, divisor `OU` ou menção a e-mail.
- `Usar passkey` permanece visível como ação secundária minimalista.
- Sucesso grava somente `AuthProfile` em memória e recarrega o workspace.
- Erros mantêm o usuário na tela e devolvem foco ao CPF.

### Verificação integrada

- API saudável em Java 21 e banco migrado.
- Web buildada e servida pela mesma branch.
- Login direto com um colaborador real do banco local.
- Sessão confirmada por `GET /api/auth/session` sem token ou CPF na resposta.
- Workspace aberto e rotas internas acessíveis conforme o escopo.
- Login por passkey continua disponível e a tela não contém autenticação por e-mail.

## Fora de escopo

- Reintroduzir JWT ou CPF em `localStorage`.
- Restaurar filtro de Bloom ou login offline por CPF.
- Criar PIN, senha ou credenciais hardcoded.
- Alterar os fluxos internos de envio de e-mail do Financeiro.
- Modificar as regras Alfa/Beta ou os vínculos de obra.
