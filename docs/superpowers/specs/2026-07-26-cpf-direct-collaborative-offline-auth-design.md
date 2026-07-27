# Córtex — CPF direto e acesso offline em dispositivo colaborativo

**Status:** aprovado pelo proprietário em 2026-07-26.

## Objetivo

Substituir o fluxo normal de CPF + OTP por um acesso direto por CPF de
colaborador ativo da Academy, mantendo a passkey como alternativa. O mesmo
perfil, depois de sincronizado neste navegador, pode ser reaberto offline por
CPF em um dispositivo colaborativo.

## Contexto e decisão de produto

- A Academy é a única fonte de pessoas/CPF. Zeladoria, nesta base, fornece
  somente ativos/equipamentos; não entra no fluxo de identidade.
- Academy e Zeladoria continuam como integrações MySQL somente leitura. O
  Córtex consulta exclusivamente o espelho canônico PostgreSQL
  (`colaborador` + `auth_identity`) para o login e para o escopo offline.
- O proprietário autorizou explicitamente CPF como credencial suficiente no
  dispositivo colaborativo. Portanto este fluxo não promete proteção contra
  alguém que conheça o CPF e tenha acesso físico ao navegador já sincronizado.
- Não haverá e-mail, código OTP, PIN, senha ou dado de demonstração na tela
  normal de acesso.

## Alternativas consideradas

1. **CPF + OTP por e-mail:** maior prova remota de identidade, mas contradiz
   a exigência de não usar e-mail ou código.
2. **CPF online, passkey obrigatória offline:** preserva o cofre PRF atual,
   mas não atende o uso de aparelho colaborativo aprovado pelo proprietário.
3. **CPF online e CPF offline a partir de grant sincronizado:** escolhido.
   Mantém PostgreSQL como origem canônica, preserva o escopo assinado e torna
   explícita a menor garantia do dispositivo colaborativo.

## Arquitetura

### Acesso online

1. O usuário informa CPF; a interface valida apenas a forma localmente e o
   envia no corpo de `POST /api/auth/login`.
2. `AuthService` resolve o CPF por candidatos HMAC em `auth_identity`, exige
   exatamente um colaborador Academy ativo, não deletado, com papel ALFA ou
   BETA e identidade ATIVA.
3. O backend emite a sessão opaca existente e os cookies de sessão/CSRF. A
   rota é pública somente como `POST /api/auth/login`; sessões e demais APIs
   continuam protegidas.
4. O frontend guarda somente o perfil em memória e navega para a aplicação.
   Passkey continua uma ação alternativa que emite a mesma sessão.

O login não consulta MySQL ao vivo. A atualização dos colaboradores é feita
pela sincronização Academy -> PostgreSQL; indisponibilidade temporária da
Academy não invalida o último espelho válido.

### Cache offline colaborativo

Após um login online por CPF, o navegador solicita `POST /api/auth/offline-grant`
na sessão recém-criada. O backend assina o perfil, papel, escopo de obras e
validade já existentes. O navegador persiste:

- o envelope `SignedOfflineGrant` assinado pelo servidor;
- o fingerprint da chave pública do servidor;
- uma chave de pesquisa derivada por SHA-256 do CPF canônico, nunca o CPF
  textual;
- data de atualização e identificador do colaborador que já existem no grant.

O grant não é uma sessão de servidor, não contém cookie, e sua assinatura,
expiração e escopo são verificados antes de criar uma sessão offline em memória.
O hash local reduz exposição acidental do CPF, mas não é tratado como segredo.

Quando estiver offline (ou quando a API estiver momentaneamente indisponível),
a tela apresenta o campo CPF. O CPF precisa corresponder a um grant local ainda
válido; então o Córtex verifica a assinatura e ativa apenas o escopo assinado.
CPF inexistente, grant expirado, grant alterado ou escopo inválido não abre a
aplicação e não revela qual perfil está armazenado.

### Passkey e coexistência

O cofre PRF/passkey atual não é removido. Se existir, **Usar passkey** continua
disponível online e offline. O novo grant colaborativo é um segundo tipo de
metadado no mesmo banco IndexedDB, com índice de atualização separado; um
dispositivo pode guardar ambos sem cruzar escopos.

## Limites e controles mantidos

- CPF é normalizado/validado no backend antes da consulta e jamais aparece em
  URL, mensagem de erro, log, `localStorage` ou `sessionStorage`.
- A rota direta recebe limite por IP e por identificador HMAC/decoy antes da
  resolução de identidade; payload é limitado antes do MVC para reduzir abuso.
- Respostas para CPF inválido, ausente, inativo, ambíguo ou desconhecido usam
  a mesma mensagem de recusa e não enumeram colaboradores.
- A configuração normal PostgreSQL publica somente login CPF direto e os dois
  endpoints de autenticação por passkey. Rotas OTP voltam a ficar restritas ao
  perfil isolado `postgresql-activation`.
- A sessão continua `HttpOnly`, `Secure` em produção, `SameSite`, com CSRF
  host-only; o deploy deve publicar `/api` na mesma origem da PWA. O preview
  local usa proxy same-origin, nunca uma mistura `localhost`/`127.0.0.1`.
- Um grant offline expira segundo o TTL assinado pelo servidor. Para renovar
  escopo/revogar acesso, o colaborador reconecta e faz login; dados locais
  continuam sujeitos ao namespace do colaborador e ao escopo do grant.

## UX e erros

- Texto principal: “Informe seu CPF para acessar o Córtex.”
- Botões: “Entrar” e “Entrar com passkey”. Não aparecem e-mail, código,
  destinatário ou etapas OTP.
- Sem rede e com grant colaborativo: “Informe o CPF sincronizado neste
  dispositivo.”
- Sem rede e sem grant local: a tela informa que o primeiro acesso precisa de
  conexão, sem inventar conta, CPF ou escopo.
- Falha ao atualizar o grant após login não derruba a sessão online; mostra
  aviso claro de que o acesso offline ainda não foi atualizado.

## Verificação

1. Testes Java cobrem a publicação exata da rota direta em PostgreSQL, o
   bloqueio do OTP normal, rate limit, payload inválido e emissão de sessão
   apenas para identidade Academy canônica ativa.
2. Integração PostgreSQL confirma que sync Academy produz a identidade HMAC e
   que o login jamais precisa de conexão MySQL ao vivo.
3. Testes Vitest cobrem CPF direto como ação primária, passkey secundária,
   gravação automática de grant após login e falha não bloqueante dessa gravação.
4. Testes offline cobrem CPF correspondente, CPF diferente, grant expirado,
   assinatura alterada e coexistência com o cofre PRF.
5. Gates finais incluem frontend, backend, integração PostgreSQL, build de
   containers, verificação de mesma origem e varredura de segredos.
