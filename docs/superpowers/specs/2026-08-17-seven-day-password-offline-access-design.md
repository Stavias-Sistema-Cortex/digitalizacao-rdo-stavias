# Córtex — acesso offline por sete dias com senha individual

## Status

Design aprovado pelo responsável em 17 de agosto de 2026. Esta especificação
substitui, somente para autenticação offline, as decisões anteriores que
limitavam o grant colaborativo a 24 horas e permitiam desbloqueio apenas com
CPF. A passkey continua disponível como alternativa.

## Objetivo

Permitir que um colaborador que já autenticou este aparelho junto ao Córtex
continue trabalhando por até sete dias sem alcançar a VM, usando CPF e sua
senha individual para abrir os dados locais autorizados. Quando a conexão
voltar, a aplicação retoma a sessão online e sincroniza a outbox sem exigir
perfil individual, senha ou outra alteração no MySQL do Academy/Zeladoria.

## Decisões aprovadas

- O prazo máximo do grant offline colaborativo passa de 24 horas para sete
  dias (`604800` segundos).
- O primeiro acesso offline de um aparelho continua impossível. O aparelho
  precisa concluir ao menos um login online por CPF e senha.
- O desbloqueio colaborativo offline exige CPF e a senha individual.
- A senha, o hash Argon2id do servidor e um verificador reutilizável da senha
  nunca são persistidos no navegador.
- O grant assinado deixa de ser armazenado em claro no registro colaborativo;
  ele fica dentro de um cofre AES-GCM protegido por uma chave derivada da
  senha.
- O cofre é restrito ao mesmo perfil de navegador/aparelho e ao escopo
  assinado pelo servidor. Copiar apenas o banco local, sem a senha, não cria
  um novo acesso válido. Este desenho não afirma vínculo a hardware sem
  passkey/PRF.
- O grant é renovado automaticamente enquanto existir uma sessão online e
  restar no máximo um terço de sua validade.
- A passkey/PRF permanece como alternativa de desbloqueio e não é removida.
- Redefinição de senha, inativação e revogação têm efeito online imediato. Um
  aparelho sem qualquer conexão só pode conhecer essa mudança ao reconectar;
  até lá, o risco fica limitado pela validade de sete dias.
- Grant vencido bloqueia a abertura, mas não apaga dados ou mutações locais
  pendentes. Depois de uma nova autenticação online, elas podem ser
  reconciliadas e sincronizadas sob a autorização atual.

## Limites explícitos

"Offline" significa que o navegador não alcança a API do Córtex. Um aparelho
sem internet, mas conectado à rede da Stavias e capaz de alcançar a VM, usa o
fluxo online normal pela LAN e não consome este mecanismo.

O desenho não promete:

- login offline em aparelho novo, navegador novo ou armazenamento limpo;
- primeiro acesso, geração de código ou redefinição de senha sem alcançar a
  VM;
- sincronização com outros aparelhos enquanto a API estiver inacessível;
- sincronização Academy/Zeladoria enquanto a VM estiver sem internet para
  chegar ao MySQL da Locaweb;
- revogação instantânea em um aparelho fisicamente isolado da rede;
- funcionamento offline ilimitado;
- suporte offline automático a telas que ainda dependam exclusivamente da
  API e não tenham repositório local/outbox.

## Fluxos

### Preparação online

1. O colaborador informa CPF e senha no login normal.
2. A API valida a identidade Academy ativa e o hash Argon2id no PostgreSQL do
   Córtex.
3. A API cria a sessão e emite um grant assinado com identidade, escopo,
   emissão, expiração de sete dias e época de autorização.
4. Ainda na memória da página, o frontend usa a senha recém-validada para
   derivar a chave do cofre.
5. O frontend cifra o grant, persiste somente o envelope protegido e elimina
   as referências à senha e ao grant em claro.
6. A aplicação prepara os dados locais e passa a renovar o grant quando
   houver rede e ele entrar no último terço de validade.

Falhar ao criar o cofre não desfaz um login online válido, mas a interface
deve avisar claramente que este aparelho ainda não está preparado para uso
offline.

### Desbloqueio sem API

1. A PWA abre o shell previamente armazenado.
2. A pessoa informa CPF e senha; a passkey continua disponível em paralelo.
3. O CPF localiza, em tempo constante dentro do limite de registros, o cofre
   candidato sem expor a identidade armazenada.
4. A senha deriva a chave e tenta autenticar/decriptar o AES-GCM.
5. O frontend verifica assinatura, fingerprint da chave pública, dono,
   escopo, época carregada no grant, prazo e relógio local monotônico.
6. Somente depois dessas verificações a sessão offline é ativada e o banco
   local daquele escopo é aberto.

CPF ou senha incorretos, cofre adulterado, grant vencido e identidade
divergente retornam a mesma mensagem genérica. O erro não informa se existe
um cofre ou qual campo estava correto.

### Trabalho e reconexão

- Leituras já hidratadas continuam disponíveis no armazenamento local.
- Operações cobertas pelo coordenador local são gravadas atomicamente junto à
  outbox e ao evento operacional.
- Enquanto a API estiver inacessível, nada é marcado como sincronizado.
- Ao reconectar, o navegador tenta retomar a sessão online. Se a sessão tiver
  sido revogada, exige CPF e senha novamente.
- A API revalida identidade, papel, obra, versão e época de autorização antes
  de aceitar qualquer mutação.
- Mutações não mais autorizadas permanecem auditáveis e são marcadas como
  rejeitadas; não recebem permissão retroativa.
- Uma mutação idempotente não pode ser aplicada duas vezes durante a retomada.

## Criptografia e armazenamento local

### Derivação e cofre

O cofre colaborativo usa:

- AES-256-GCM;
- IV aleatório de 96 bits por gravação;
- sal aleatório e independente para a derivação da senha;
- PBKDF2-HMAC-SHA-256 com pelo menos 600.000 iterações, executado pela Web
  Crypto API;
- dados adicionais autenticados contendo somente campos públicos imutáveis
  necessários antes da decriptação: versão do protocolo, chave aleatória do
  registro, verificador protegido do CPF e fingerprint da chave do servidor;
- dono, escopo e seu fingerprint permanecem dentro do ciphertext e são
  conferidos contra o grant assinado depois da decriptação autenticada;
- buffers temporários limpos assim que a API do navegador permitir.

O Argon2id permanece obrigatório no servidor. O PBKDF2 local não substitui o
hash de autenticação; ele serve somente para derivar uma chave de cifragem
compatível com Web Crypto sem baixar um runtime criptográfico adicional.

O registro local versão 3 contém apenas:

```text
key
versao = 3
cpfSalt
cpfVerifier
passwordSalt
kdf = PBKDF2-SHA256
kdfIterations
iv
ciphertext
serverKeyFingerprint
atualizadoEm
failedAttemptState
```

O `ciphertext` contém o grant assinado e o material mínimo para confirmar dono
e escopo, inclusive `lastTrustedTime`. Não ficam em claro senha, hash de senha,
grant, nome, papel, IDs de obra, `authEpoch` ou o último horário confiável.

### Tentativas locais

Cada falha de decriptação incrementa um estado de espera com atraso crescente
e limite de cinco tentativas por janela. Limpar o armazenamento também remove
o próprio cofre e, portanto, não cria um caminho de acesso. A resistência
principal contra ataque offline é a senha mínima de 12 caracteres combinada
ao custo do KDF.

### Tempo e validade

O grant continua trazendo `emitidoEm` e `expiraEm` assinados. O navegador
persiste o maior horário confiável já observado e rejeita regressão do relógio
superior à tolerância existente de cinco minutos. Um dispositivo cujo relógio
voltar de forma suspeita exige conexão antes de liberar os dados.

Nenhum software puramente offline consegue provar tempo real contra alguém
com controle total do aparelho. Este risco residual é aceito porque os
aparelhos são da Stavias, o prazo é limitado e o servidor revalida tudo na
reconexão.

## Revogação e época de autorização

O grant ganha `authEpoch`, número mantido pelo servidor para a credencial do
colaborador. Primeiro acesso, redefinição de senha, bloqueio e outras
revogações relevantes incrementam essa época e revogam as sessões online.

- Todo grant novo carrega a época atual.
- A outbox nunca trata a época local como autorização final.
- Ao retomar a conexão, época divergente encerra a sessão offline, impede
  envio sob o grant antigo e exige nova autenticação.
- Um aparelho totalmente isolado pode usar o grant antigo somente até
  `expiraEm`; não existe revogação instantânea sem canal de comunicação.

## Migração do formato atual

O formato colaborativo versão 2 contém o grant assinado em claro e não pode
ser convertido com segurança sem a senha. A migração será progressiva:

1. grants v2 existentes permanecem fisicamente preservados com sua expiração
   original de no máximo 24 horas para rollback, mas a interface v3 não os
   apresenta como cofre por senha preparado;
2. eles não são ampliados para sete dias nem renovados como v2;
3. no próximo login online com senha, o frontend cria um cofre v3 e remove o
   v2 correspondente somente depois da persistência v3 ser confirmada;
4. falha na migração preserva o v2 para rollback e mostra que o acesso de sete
   dias não foi preparado;
5. passkey vaults existentes não são modificados.

## Backend

- `OfflineGrantProperties` passa a aceitar e usar no máximo `604800`
  segundos.
- `OfflineGrantClaims`, serialização, parser web e testes incluem versão do
  protocolo e `authEpoch`.
- A persistência de senha recebe uma época monotônica ou estrutura equivalente
  que seja incrementada transacionalmente em primeiro acesso/redefinição e
  bloqueio.
- A emissão do grant lê a mesma época da credencial ativa.
- Sessão, emissão de grant e endpoints de sincronização continuam exigindo
  identidade Academy ativa e autorização vigente.
- O MySQL Academy/Zeladoria permanece somente como fonte externa; nenhuma
  senha individual é gravada nele.

## Frontend

- `autenticarPorCpf` prepara o cofre v3 usando a senha já presente apenas na
  chamada de login.
- `OfflineUnlockPage` pede CPF e senha no modo colaborativo, mantendo o botão
  de passkey quando disponível.
- `collaborativeOfflineGrant` passa a cifrar/decriptar o envelope, nunca a
  comparar um hash de senha persistido.
- `offlineVault` valida prazo de sete dias, época, assinatura, escopo e relógio
  confiável.
- `renovacaoDoGrantOffline` só renova v3 quando puder obter a senha por uma
  nova autenticação segura. Uma sessão online silenciosa pode atualizar o
  grant assinado, mas não recifrar um cofre protegido pela senha sem possuir a
  chave derivada; por isso a chave de cofre pode permanecer apenas na memória
  da sessão ativa e deve ser descartada ao fechar/recarregar.
- Se a chave não estiver mais na memória quando chegar a hora da renovação, a
  interface pede a senha antes do vencimento em vez de rebaixar para CPF-only.

## Academy e Zeladoria

Os schedulers da VM continuam separados da autenticação offline. Quando
habilitados, uma falha de internet é registrada e a próxima execução tenta
novamente. Com o intervalo padrão, a nova tentativa ocorre em até cinco
minutos depois de a conexão voltar. As flags permanecem desligadas por padrão
até as credenciais SELECT-only, TLS e mapeamentos passarem pela validação do
ambiente real.

## Tratamento de erros

- Erros de CPF, senha, existência do cofre, assinatura e decriptação usam uma
  resposta única.
- Erro de armazenamento após login não invalida a sessão online; informa que
  o modo offline não foi preparado.
- Grant vencido preserva a outbox e exige login online.
- Relógio regressivo bloqueia o cofre sem apagar dados.
- Falha de sincronização mantém a mutação na fila com o estado e motivo
  existentes.
- Falha de fonte Academy/Zeladoria não bloqueia o Córtex local nem apaga o
  último snapshot confirmado.

## Verificação obrigatória

### Backend

1. Testes provam o limite exato de sete dias e rejeitam prazo maior.
2. Assinatura e parsing incluem `authEpoch` sem aceitar campos ausentes ou
   extras.
3. Primeiro acesso, redefinição, bloqueio e inativação incrementam a época e
   revogam sessões dentro da mesma fronteira transacional aplicável.
4. Emissão de grant recusa identidade ou credencial inativa.
5. Migração PostgreSQL é validada em PostgreSQL 18 real por Testcontainers.

### Frontend

1. Um teste escrito antes da implementação prova que o formato v2 não é
   estendido silenciosamente.
2. CPF e senha corretos abrem o v3; senha errada, CPF errado, ciphertext/IV
   adulterado e fingerprint divergente falham com erro genérico.
3. O registro IndexedDB não contém senha, hash Argon2id, grant em claro, nome,
   papel ou IDs de obra.
4. A validade funciona imediatamente antes e depois de sete dias, incluindo
   regressão suspeita do relógio.
5. Cinco falhas acionam espera local e sucesso válido limpa o contador.
6. A página offline exige senha, mantém passkey e explica a necessidade do
   primeiro login online no aparelho.
7. Build PWA confirma que todo código necessário ao desbloqueio está no shell
   offline.

### Integração e aceitação

Executar no mesmo build/revisão:

1. login online por CPF e senha;
2. confirmação de que o cofre v3 foi persistido sem segredos em claro;
3. desligamento efetivo da rede/API;
4. fechamento e reabertura do navegador;
5. desbloqueio por CPF e senha;
6. criação e edição de um RDO offline;
7. reconexão e autenticação atual;
8. envio único da outbox e recebimento do estado canônico;
9. repetição do reconnect sem duplicar a mutação;
10. redefinição de senha e inativação, provando bloqueio ao reconectar;
11. simulação de sete dias, provando bloqueio depois da expiração;
12. execução em dois aparelhos para provar isolamento dos cofres.

Testes unitários e contratos não substituem essa aceitação real de navegador.

## Operação e implantação

- `CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS=604800` deve ser igual nos ambientes
  local, hospedado e nos contratos de publicação.
- O frontend e a API precisam ser publicados pela mesma revisão de protocolo.
- A fingerprint da chave pública do grant continua obrigatória no build web.
- A chave privada continua somente no servidor, montada como secret file.
- Não há nova credencial do MySQL, serviço pago, VPN ou perfil por usuário.
- Rollback preserva grants v2 ainda válidos e não deve apagar outbox/dados
  locais v3; uma versão antiga apenas não poderá abrir o novo cofre.

## Critérios de conclusão

O trabalho só pode ser considerado entregue quando:

- os testes backend/frontend, build PWA, migração PostgreSQL e contratos de
  deploy passarem;
- não houver senha, grant colaborativo em claro ou credencial externa nos
  artefatos/armazenamento local inspecionados;
- o ciclo real online → offline → edição → reconexão tiver evidência sem
  duplicidade;
- um grant vencido, uma senha redefinida e um colaborador inativado forem
  recusados nos pontos em que o dispositivo consegue conhecer a mudança;
- CI, publicação e revisão implantada forem relatados separadamente.
