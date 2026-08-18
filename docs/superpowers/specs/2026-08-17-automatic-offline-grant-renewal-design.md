# Córtex — renovação automática do acesso offline

## Status

Direção aprovada pelo responsável em 17 de agosto de 2026. Esta especificação
substitui somente o trecho de renovação manual descrito no desenho anterior de
acesso offline por senha. O limite de sete dias, o desbloqueio offline por CPF
e senha, a alternativa por passkey e a autoridade final do servidor continuam
válidos.

## Problema confirmado

O backend já permite emitir um grant novo por `POST /api/auth/offline-grant`
usando a sessão online vigente, sem receber CPF ou senha no corpo. A API deriva
o dono do principal autenticado, relê papel, escopo, atividade e `authEpoch`,
usa o relógio do PostgreSQL e assina um grant de no máximo sete dias.

O bloqueio está no navegador. O cofre colaborativo v3 guarda o grant dentro de
um envelope AES-GCM cuja chave é derivada da senha e mantida apenas em memória.
Depois de fechar ou recarregar a página, a sessão HTTP pode continuar válida,
mas a chave do cofre desaparece. O fluxo atual retorna
`SENHA_NECESSARIA` antes de descobrir se o grant ainda tem vários dias de
validade e abre `OfflineGrantRenewalPrompt`, que executa outro login completo.

Há quatro efeitos colaterais adicionais no fluxo atual:

- o prompt pode aparecer imediatamente após um reload, sem proximidade do
  vencimento;
- eventos simultâneos de início e sincronização podem iniciar renovações
  concorrentes;
- um grant assinado mais antigo ainda pode sobrescrever um mais novo;
- logins repetidos podem acumular cofres v3 até ultrapassar o limite de busca
  de vinte registros e ocultar cofres válidos mais antigos.

A renovação não faz parte da outbox. O banco de autenticação local é separado
dos bancos de dados operacionais, e `SYNC_COMPLETED_EVENT` só é emitido depois
de o estado durável da sincronização ter sido gravado. Portanto a correção deve
preservar essa separação: renovar acesso nunca confirma, apaga, move ou rejeita
uma mutação operacional.

## Objetivos

- Eliminar totalmente o formulário de renovação manual.
- Preparar ou renovar o acesso offline quando houver uma sessão online válida,
  sem pedir CPF, senha ou passkey apenas para renovar.
- Manter grants assinados com janela móvel de no máximo sete dias e aplicar esse
  prazo com o relógio normal do aparelho; cada conexão autenticada pode avançar
  a janela, mas a aplicação não remove nem amplia o teto assinado.
- Continuar exigindo CPF e senha, ou passkey, para desbloquear dados depois de
  uma abertura realmente offline.
- Não persistir senha, hash Argon2id, chave derivada da senha ou grant em claro.
- Preservar outbox, dados locais, idempotência e autorização do servidor.
- Suportar aparelhos compartilhados por mais de um colaborador sem acrescentar
  dono em claro aos novos stores de cápsula. Esta mudança não promete remover
  identificadores já existentes nos cofres passkey ou nomes dos bancos
  operacionais.
- Fazer migração progressiva dos cofres v3 existentes sem uma ação especial do
  encarregado.

## Limites explícitos

Esta mudança não promete:

- primeiro acesso offline em navegador novo ou com armazenamento apagado;
- renovação enquanto a API estiver realmente inacessível;
- renovação de uma sessão online já expirada ou revogada;
- reaproveitamento silencioso de um cofre criado antes de redefinição de senha
  ou avanço de `authEpoch`;
- reprovisionamento offline por passkey quando o autenticador ou navegador não
  oferece a extensão PRF;
- revogação instantânea em um aparelho sem qualquer canal de comunicação;
- funcionamento offline por mais de sete dias contínuos sem uma conexão
  autenticada;
- resistência forte a congelamento/manipulação deliberada do relógio local ou a
  restauração coordenada do perfil do navegador; um browser sem relógio
  monotônico de hardware ou autoridade externa não consegue garantir passagem
  real do tempo diante de um host malicioso;
- sincronização entre aparelhos enquanto o servidor estiver inacessível;
- autorização retroativa de uma mutação que o servidor rejeite após mudança
  de papel, obra, identidade ou época.

Grant vencido não apaga dados nem outbox. Depois de uma autenticação online
válida, a aplicação prepara novamente o acesso offline e tenta sincronizar as
operações pendentes sob as permissões atuais.

## Alternativas consideradas

### 1. Cápsula criptografada do aparelho — escolhida

O grant renovável fica em um envelope separado, cifrado por uma chave AES-GCM
aleatória, não extraível e persistida pelo próprio navegador. O cofre v3
protegido pela senha continua sendo a prova local necessária para abrir os
dados; a cápsula apenas fornece o snapshot de autorização assinado mais novo.

Esta divisão permite trocar o grant com uma sessão online sem possuir a senha,
mas não cria um caminho de desbloqueio sem CPF e senha. Também permite atender
cofres v3 existentes por meio de um sidecar, sem reescrever seu ciphertext.

### 2. Persistir ou embrulhar a chave derivada da senha — rejeitada

Isso permitiria recifrar o cofre atual, mas entregaria ao aparelho uma forma de
decriptar toda a identidade protegida sem a senha. Mesmo que a chave fosse
marcada como não extraível, o runtime poderia usá-la para abrir o cofre. A
proteção da senha deixaria de ser uma fronteira real do fluxo.

### 3. Guardar o grant em claro ou remover a expiração — rejeitada

Grant em claro expõe nome, papel e IDs de obra no armazenamento local. Remover
o prazo torna bloqueio, inativação e redefinição de senha ineficazes em um
aparelho isolado por tempo indeterminado. Nenhuma das duas opções é necessária
para automatizar a renovação.

## Arquitetura escolhida

### 1. Sidecar por proprietário

O IndexedDB `cortex-auth-vaults` avança para uma nova versão e recebe três
object stores:

- `grant_capsules`: envelopes cifrados do grant atual;
- `grant_capsule_keys`: chaves `CryptoKey` não extraíveis, referenciadas por
  identificador opaco;
- `grant_capsule_state`: controle opaco de tentativa, backoff e último sucesso,
  sem CPF, dono, grant ou escopo em claro.

O registro do aparelho mantém duas chaves Web Crypto não extraíveis: uma
AES-256-GCM para cifrar cápsulas e uma HMAC-SHA-256 para produzir um índice
cego do proprietário. O índice usa domínio separado,
`HMAC(deviceLookupKey, "owner:v1\0" || ownerId)`; ele permite
localizar diretamente a cápsula do dono conhecido pela sessão online ou pelo
cofre aberto com senha, sem gravar `ownerId` e sem limitar a pesquisa aos
primeiros vinte usuários.

Cada cápsula persistida contém somente:

```text
ownerLookup
versao = 1
revision
deviceKeyGeneration
iv
ciphertext
serverKeyFingerprint
atualizadoEm
```

O plaintext cifrado contém:

```text
signedGrant
ownerId
scopeFingerprint
authEpoch
lastTrustedTime
```

`revision` é um nonce aleatório de 128 bits, opaco e trocado a cada gravação.
`deviceKeyGeneration` identifica o par AES/HMAC usado no índice e na cifra. O
`ownerId`, o grant, o escopo, a época e o relógio confiável nunca aparecem em
índices ou campos públicos. `ownerLookup` é opaco e não reversível sem a chave
HMAC do perfil. As chaves do aparelho são criadas com `extractable: false` e
somente com os usos necessários (`encrypt`/`decrypt` ou `sign`). O AAD liga a
cápsula à sua versão, `revision`, `ownerLookup`, `deviceKeyGeneration`,
algoritmo e fingerprint do servidor.

O primeiro bootstrap das chaves ocorre antes de calcular qualquer índice e usa
um lock global entre abas. O par AES/HMAC e sua geração são gravados juntos em
uma transação com `add-if-absent`. Se outra aba vencer a corrida, as chaves
candidatas são descartadas e a aba relê o par persistido. Web Locks é a primeira
opção; um lease em registro fixo, que não depende de `ownerLookup`, é o fallback.

O fingerprint armazenado é apenas metadado autenticado pelo AAD; ele nunca
define em qual chave confiar. Toda assinatura é validada exclusivamente contra
um keyring compilado e revisado no build do Córtex. Uma chave desconhecida falha
mesmo que o registro traga fingerprint e assinatura autoconsistentes.

O keyring distingue chaves `ACTIVE`, `RETIRED` e `REVOKED`. Uma emissão nova só
aceita assinatura por chave ativa. Uma cápsula já emitida pode ser verificada
por chave `RETIRED` somente se `emitidoEm` for anterior ao cutoff compilado e o
instante atual não ultrapassar `min(notAfter, expiraEm)`. O vínculo histórico
segue a política de retenção planejada da chave e nunca transforma um grant
vencido em autorização por si só.

Uma rotação publica primeiro um bundle que confia nas duas chaves durante o
overlap; depois do corte, a antiga vira `RETIRED` apenas para verificar material
emitido antes do cutoff e só é removida após a janela de reprovisionamento.
Chave `REVOKED` por comprometimento nunca é aceita, nem como histórica; nesse
caso, o aparelho precisa de autenticação online para reprovisionar o cofre.

Uma cópia textual dos registros não contém a chave bruta nem o grant. Isto não
é apresentado como vínculo físico a hardware: quem controla o mesmo perfil do
navegador e executa código sob a origem do Córtex continua dentro do modelo de
ameaça do aparelho corporativo. A passkey/PRF permanece a alternativa mais
forte quando houver suporte.

Antes de marcar o aparelho como preparado, um feature probe cria, persiste,
relê e usa uma chave não extraível no IndexedDB. Se o navegador não suportar o
structured clone de `CryptoKey`, a aplicação falha fechada: mantém o grant já
existente enquanto válido, não grava material em claro e registra que aquele
navegador não oferece renovação silenciosa. Os navegadores corporativos
suportados precisam passar esse probe na matriz real de release.

### 2. Separação entre autenticação local e autorização atual

O cofre v3 continua protegido pela chave derivada da senha e continua sendo
obrigatório no desbloqueio colaborativo. Para usar uma cápsula:

1. CPF e senha localizam e decriptam o cofre v3.
2. O grant original do cofre é verificado como material histórico assinado:
   esquema exato, assinatura, fingerprint, dono e teto de sete dias na emissão
   continuam obrigatórios, mas a expiração passada não produz uma sessão.
3. A aplicação calcula o índice cego com o dono autenticado pelo cofre, abre a
   cápsula correspondente e confirma novamente o dono dentro do ciphertext.
4. O grant da cápsula é verificado pelo caminho estrito atual, inclusive prazo,
   relógio, escopo e `authEpoch`; dentro da mesma época, `emitidoEm` não pode ser
   anterior ao grant histórico aberto pelo fator local.
5. A `authEpoch` assinada no grant histórico do cofre deve ser exatamente igual
   à da cápsula. Não basta que a cápsula seja mais nova: isso impediria que uma
   senha antiga, capaz de abrir um cofre sobrevivente, recebesse autorização de
   uma geração criada depois de uma redefinição.
6. Somente a combinação do cofre aberto pela senha com uma cápsula atual do
   mesmo dono e da mesma `authEpoch` pode ativar a sessão offline.
7. Se não houver cápsula válida, o grant original do cofre só pode ser usado
   enquanto ainda estiver válido pelo verificador estrito existente.

Nesta arquitetura, `authEpoch` é uma geração global da identidade, não uma
versão exclusiva da senha. Redefinição de senha, revogação de passkey e qualquer
revogação de fator que invalide acesso avançam a mesma época. Cadastrar uma
passkey não avança a época: a sessão atual precisa continuar válida para obter o
grant e selar o cofre PRF recém-criado. Grants
legados sem `authEpoch` nunca são ligados a cápsulas: podem usar apenas seu grant
embutido enquanto ele for estritamente válido e exigem reprovisionamento online
para entrar no formato novo.

O verificador histórico nunca retorna uma sessão nem autoriza dados sozinho.
Ele existe apenas para manter o vínculo estável do cofre antigo com o dono ao
qual um grant atual deve corresponder.

O relógio confiável é monotônico. Ao emitir ou abrir uma cápsula, a aplicação
preserva `max(lastTrustedTime do cofre, lastTrustedTime da cápsula,
emitidoEm assinado pelo servidor)` e nunca aceita uma redução. No desbloqueio,
o instante efetivo é pelo menos esse valor, mesmo que o relógio do aparelho
tenha sido atrasado depois de avançar. Isso bloqueia regressão simples do
relógio, mas não detecta um relógio congelado desde a emissão nem rollback total
do perfil. Sob relógio normal, um valor local adiantado pode encerrar o acesso
antes e nunca o estende além do vencimento assinado.

Depois de um desbloqueio bem-sucedido, quando a chave derivada da senha está em
memória, o cofre v3 pode ser recifrado com o grant atual e um IV novo. Isso é
uma otimização defensiva; a cápsula continua sendo a fonte renovável.

### 3. Renovação automática

Uma função idempotente e single-flight, por dono da sessão, garante a cápsula
atual. Ela é acionada:

- quando uma sessão online válida termina de preparar o banco local;
- quando `retomarSessaoOnline` confirma o mesmo dono após reconexão;
- depois de `SYNC_COMPLETED_EVENT`, apenas como retry ou atualização devida;
- ao voltar para uma aba visível, se houver rede e a atualização estiver
  vencida.

Cada sessão autenticada expõe ao cliente um marcador opaco, não secreto,
estável durante reloads do mesmo cookie e trocado somente em nova autenticação.
O cliente guarda apenas `HMAC(deviceLookupKey, "session:v1\0" || marker)` no
estado da cápsula. Backoff terminal e respostas em voo são vinculados à dupla
`ownerLookup + sessionLookup`: reload conserva a decisão; novo login, inclusive
do mesmo dono, recebe outra geração e pode tentar novamente.

Uma cápsula ausente é criada imediatamente. Uma sessão online recém-validada
reavança a janela de sete dias quando a última emissão confirmada tiver mais de
seis horas. Esse limite é persistente e sobrevive a reloads: várias aberturas
do app dentro da mesma janela não geram várias emissões. Eventos de
sincronização dentro do intervalo apenas confirmam que não há trabalho de
renovação. Falha transitória depois do backoff, cápsula próxima do vencimento
ou mudança de sessão libera uma nova tentativa. Um bloqueio single-flight evita
chamadas concorrentes no mesmo documento. Entre abas, a aplicação usa Web Locks
e, quando indisponível, um lease curto no `grant_capsule_state`; depois de
adquirir o lock ela relê o estado e reaplica o throttle. O CAS transacional por
`revision` continua sendo a última defesa contra regressão.

O estado persistente de tentativa impede que o scheduler de sync transforme
uma falha durável em chamadas a cada trinta segundos:

- rede, `5xx` e falha transitória de IndexedDB usam backoff exponencial com
  jitter, de um minuto até uma hora;
- `401` e `403` ficam bloqueados até mudança de sessão ou login bem-sucedido;
- `422`, assinatura inválida, fingerprint desconhecido, dono divergente ou
  corrupção criptográfica ficam bloqueados até mudança de sessão, keyring ou
  revisão do app;
- sucesso limpa o erro e grava a próxima emissão elegível.

Eventos de sync e visibilidade podem chamar a função durante o backoff, mas ela
retorna `SKIPPED` sem acessar a API. O estado da renovação não altera o
resultado de uma sincronização já concluída.

O código captura `ownerLookup` e `sessionLookup` antes da chamada. Se `apiFetch`
receber `401` e limpar a sessão, a classificação ainda persiste o bloqueio para
a geração capturada, sem gravar qualquer cápsula candidata.

Essa política não espera o último terço do grant. A sessão online dura menos
que o grant offline; esperar vários dias faria a oportunidade segura de
renovar desaparecer junto com a sessão. A emissão no início da sessão é barata
e usa o endpoint que já existe.

Antes de persistir, a função exige:

- `sessionLookup` ainda igual ao marcador capturado no início, inclusive quando
  logout e novo login pertencem ao mesmo dono;
- assinatura e fingerprint válidas;
- dono igual ao da sessão e, quando houver cápsula anterior, ao dono anterior;
- `authEpoch` não regressiva;
- `emitidoEm` não regressivo;
- duração assinada de no máximo sete dias;
- escopo e fingerprint derivados do mesmo grant.

Uma resposta antiga que chega depois de uma nova sessão é descartada. A chave
estável do aparelho não é rotacionada durante uma renovação comum. Fora da
transação, a aplicação relê/decripta a cápsula corrente, valida a monotonicidade
de `authEpoch`, `emitidoEm` e `lastTrustedTime`, e cifra o candidato com uma
`revision` nova. Dentro da transação ela relê apenas o registro bruto e compara
sua `revision` àquela que foi validada. Se for igual, executa `put`; se mudou,
aborta, relê/decripta fora da transação e reavalia. Nenhuma operação
`crypto.subtle` é aguardada com a transação aberta, e uma corrida não consegue
regredir os campos cifrados.

O upgrade do IndexedDB fecha conexões ao receber `versionchange` e avisa outras
abas por `BroadcastChannel`. Um evento `blocked` é tratado como falha transitória
com retry, nunca como motivo para apagar ou recriar o banco. O teste de migração
mantém uma conexão antiga aberta e prova que o novo schema só avança depois do
fechamento coordenado.

Se o par de chaves do aparelho estiver ausente ou corrompido, as cápsulas
inutilizáveis são removidas, um novo par pode ser criado e cada dono precisa
ser preparado novamente na próxima sessão online válida. Nenhum banco
operacional ou item da outbox é removido nesse reset.

### 4. Migração automática

Não há conversão destrutiva do cofre v3. O cutover da época global avança uma
vez todas as identidades, porque o histórico antigo não prova que uma passkey
revogada tenha elevado a época da senha. Por isso nenhuma sessão ou cofre
anterior ao deploy consome uma cápsula nova. No primeiro acesso online, um login
normal cria silenciosamente cápsula e cofre na época global; não existe tela
especial de renovação.

Cofres anteriores continuam podendo usar somente o grant embutido enquanto ele
estiver estritamente válido; a revogação não pode ser instantânea num aparelho
sem contato. Depois do login de cutover, cofres novos da mesma `authEpoch` podem
usar a cápsula renovada mesmo quando o grant embutido expirar. Um cofre de época
anterior nunca consome a cápsula nova, mesmo que sobreviva à limpeza.

O próximo formato de cofre por senha inclui somente um índice cego com domínio
separado, `HMAC(deviceLookupKey, "cpf:v1\0" || cpfNormalizado)`. Depois de
confirmar e reler um cofre
novo, a aplicação remove em uma transação apenas os registros já indexados pelo
mesmo CPF e preserva os de outras pessoas.

Cofres v3 legados não são todos submetidos a PBKDF2 durante o login. Eles são
migrados incrementalmente, no máximo um por janela ociosa e com orçamento por
sessão. Cada legado só recebe o índice depois de o CPF ser comprovado pelo
verificador de 600 mil iterações; duplicatas só são removidas após a releitura
do cofre novo. Assim a limpeza converge sem travar o formulário nem retirar o
limite defensivo do caminho interativo.

### 5. Passkey

O mesmo sidecar pode fornecer o grant atual depois que a PRF da passkey abrir e
validar o cofre correspondente. A renovação automática não pede presença do
autenticador; a presença continua obrigatória para desbloquear os dados. A
continuidade de dono passa a ser verificada explicitamente antes de salvar ou
usar uma cápsula.

O fluxo de cadastro mantém a sessão/época, obtém o grant e só anuncia a passkey
como pronta para offline depois de persistir e reler o cofre PRF. Se
`authEpoch` avançar, o cofre passkey antigo também não pode consumir a
cápsula nova. No próximo login por passkey, o cliente solicita novamente a
extensão PRF e, quando o autenticador devolver o segredo, cria e relê um cofre
passkey ligado à época nova antes de remover o anterior. Se o navegador ou
autenticador não devolver PRF, o login online continua possível, mas o acesso
offline por passkey só volta depois de reprovisionar a passkey ou preparar o
cofre por senha; nunca há downgrade para material antigo.

## Relação com a sincronização

A renovação da cápsula escreve apenas em `cortex-auth-vaults`. Ela não acessa
bancos de RDO, outbox, recibos, fotos, cursores ou estado financeiro.

- Trabalho sem rede continua sendo gravado atomicamente com sua outbox.
- Nada é marcado como sincronizado enquanto não houver sessão online e resposta
  do servidor.
- O evento de sincronização só agenda uma renovação depois de push, pull, ack e
  estado durável concluírem.
- Falha de renovação não transforma uma sincronização concluída em erro e não
  retira mutações da fila.
- Grant offline nunca é enviado como autorização de sync. O servidor valida a
  sessão online, o dono, a obra, a versão e as permissões atuais.
- Mudança de escopo em um grant novo prepara a próxima sessão offline, mas não
  troca o namespace operacional da sessão online em andamento.

Como o banco operacional é nomeado por dono e fingerprint de escopo, preservar
fisicamente uma fila antiga não basta: depois de uma mudança de escopo ela pode
ficar fora do namespace corrente. Um reconciliador separado da renovação mantém
um registro central dos namespaces criados e, na primeira execução desta
versão, faz backfill dos bancos legados descobertos pelo navegador. Com sessão
online válida, ele seleciona somente namespaces do dono autenticado que ainda
tenham qualquer uma das três classes de trabalho pendente e os processa, um por
vez:

- mutações `SYNC_PUSH` da outbox genérica;
- `OBJECT_UPLOAD` e seus `mensagem_anexos`, que usam transporte próprio;
- fotos pendentes em `rdo_attachments`, mesmo quando não existe outbox.

O reconciliador nunca usa `getCortexDb()` para uma fila antiga. Ele abre um
handle explícito pelo nome registrado do banco de origem, lê o `deviceId` de
`sync_state` daquele banco e exige que envelopes `SYNC_PUSH` tenham o mesmo
`deviceId` de origem. Ausência ou divergência falha fechada, mantém os itens e
registra diagnóstico; o código nunca substitui pelo `deviceId` do namespace
novo. Os processadores de objeto e foto também recebem o handle explícito de
origem e nunca chamam `getCortexDb()` internamente.

Um lock global de sync por dono coordena o scheduler corrente e o reconciliador
legado. Web Locks é a primeira opção; o fallback é um lease no registro central,
não em cada banco de escopo, para que namespaces diferentes também se excluam.

O reconciliador preserva a ordem de transportes do motor atual: objeto,
vínculo/upload de foto e então push genérico. Nada é copiado ou reescrito para o
escopo novo. Cada classe conserva IDs, payload, dependências e namespace de
origem e usa sua confirmação própria:

- `SYNC_PUSH` só poda a mutação após resultado `APLICADA` do push;
- `OBJECT_UPLOAD` só conclui depois de upload confirmado e estado durável do
  anexo/mutação no banco de origem;
- foto de RDO só conclui depois de upload, vínculo no servidor e marca local
  durável de sincronizada.

Pull e `/sync/ack` continuam exclusivos do sync normal do namespace corrente;
não confirmam trabalho antigo. Falha, conflito ou rejeição permanece no banco
de origem com motivo auditável e alcançável pela interface de reconciliação. Um
namespace antigo não é apagado automaticamente nesta entrega.

Assim, mudança de escopo não torna a fila invisível e a cápsula nunca promove
uma operação recusada. Se a sessão perder autorização durante o ciclo, o replay
para e conserva os itens restantes.

## Tratamento de falhas

- Rede indisponível: mantém cápsula e cofre anteriores e tenta novamente depois
  do backoff no próximo gatilho elegível.
- `401`: não cria autorização local nova e permite que o cliente invalide a
  sessão como já ocorre hoje. Um sync concorrente deve abortar no guard e
  preservar toda a fila; não se promete que ele termine depois de perder a
  autenticação.
- `403`: não cria autorização local nova e bloqueia novas tentativas até mudar
  a sessão.
- `422`: preserva o grant anterior e registra estado não bloqueante na tela de
  segurança do dispositivo.
- Assinatura, fingerprint, dono, época ou emissão divergentes: descarta a
  resposta e mantém o registro anterior.
- IndexedDB, chave ou ciphertext corrompidos: não apaga a outbox; reseta apenas
  o sidecar e recria a cápsula somente com sessão online válida.
- Troca de usuário ou logout: cancela renovação em voo e limpa chaves derivadas
  da senha mantidas em memória. Cápsulas de outros donos permanecem cifradas.
- Avanço de `authEpoch`: a cápsula nova pode ser preparada online, mas nenhum
  cofre de época anterior a usa; um login normal cria o novo cofre protegido
  pelo fator atual. Para passkey, isso inclui uma nova cerimônia PRF quando
  houver suporte.
- Nenhuma falha abre modal sobre o trabalho. Um estado persistente pode ser
  consultado em Segurança do dispositivo, sem exigir ação durante a operação.

## Alterações previstas

### Frontend

- Criar um módulo de criptografia e validação da cápsula do aparelho.
- Evoluir `offlineVaultRepository` com os três stores, índice cego e CAS
  transacional sem aguardar Web Crypto dentro da transação.
- Alterar `passwordOfflineVault` para separar vínculo histórico e grant atual,
  validar somente contra o keyring confiável e migrar duplicatas em lotes
  ociosos limitados.
- Alterar `collaborativeOfflineGrant` para usar a cápsula no desbloqueio e
  remover cofres duplicados somente após persistência confirmada.
- Reescrever `renovacaoDoGrantOffline` como garantia automática single-flight.
- Remover `OfflineGrantRenewalPrompt` e todo estado relacionado de `App`.
- Integrar os gatilhos de sessão, reconexão, sync e visibilidade.
- Reforçar continuidade de dono/época e reprovisionamento PRF por passkey.
- Limpar chaves derivadas em logout e troca de sessão.
- Registrar namespaces operacionais e reconciliar outboxes antigas do mesmo
  dono sem copiar, renomear ou apagar operações.

### Backend

O endpoint de grant continua sem senha no body e protegido por cookie HttpOnly,
prova da instância, CSRF e autorização atual. A resposta de sessão/login passa
a incluir o marcador opaco de geração usado pelo cliente; ele não é credencial
nem substitui o cookie.

Uma migração move a autoridade de `authEpoch` para a identidade global. Ela
parte do maior valor conhecido da identidade/credencial de senha e executa um
bump de cutover para todas as identidades, inclusive as que não possuem senha.
Isso invalida material anterior cuja história de passkey não é confiavelmente
reconstruível. Depois do corte, redefinir senha, revogar passkey ou revogar outro
fator avança a mesma época de forma transacional. Cadastrar passkey preserva a
época e a sessão para concluir o provisionamento PRF.

O contrato interno de sessão também é endurecido: cada sessão carrega a
`authEpoch` vigente no momento da autenticação, e a fronteira de autorização
compara essa época à identidade atual. Uma sessão anterior ao avanço é
invalidada e não pode emitir uma cápsula da época nova.

Sessões preexistentes à migração ficam com época nula e falham fechadas; nunca
recebem backfill com a época atual. O primeiro acesso pós-deploy exige novo
login e cria sessão com marcador/época corretos. Ao autenticar, a verificação do
fator produz a versão da credencial e a época observadas. Antes de inserir a
sessão, uma transação relê fator e identidade, confirma que versão, revogação e
época não mudaram e só então grava a sessão; divergência aborta a autenticação.
O boundary de cada sessão compara sua época à identidade atual na mesma consulta
autoritativa, evitando TOCTOU entre validação e emissão do grant.

Será adicionada cobertura integrada com Spring e PostgreSQL para provar a
emissão por sessão real, migração fail-closed das sessões antigas, a revogação
do cookie depois de reset de senha e revogação de passkey, e as recusas por
sessão ou identidade inválidas.

## Impacto de implantação

O primeiro deploy desta arquitetura invalida sessões e cofres online/offline
anteriores para uso com cápsulas novas, porque eles não provam a história de
época de todos os fatores. Cada colaborador faz um login normal uma vez; não
aparece formulário de “renovar acesso offline”. Depois desse login, cápsula,
marcador de sessão e cofre atual são preparados automaticamente. Um cofre antigo
ainda pode cumprir somente o grant que já possuía até o vencimento assinado.
Dados locais e outbox não são apagados durante essa transição.

## Estratégia de testes

### TDD frontend

1. Provar que reload com sessão online não renderiza nem importa o prompt e
   cria/renova uma cápsula sem chamar login por senha.
2. Provar persistência sem senha, hash, grant, dono, escopo ou época em claro,
   índices HMAC opacos, chaves AES/HMAC não extraíveis, `exportKey` recusado e
   fallback fechado quando o feature probe não persiste a chave.
3. Provar bootstrap simultâneo em duas abas: um único par AES/HMAC e uma única
   `deviceKeyGeneration` vencem por `add-if-absent`.
4. Provar desbloqueio de um v3 cujo grant embutido venceu, usando CPF/senha e
   uma cápsula atual do mesmo dono e da mesma `authEpoch`.
5. Provar que senha errada, cápsula de outro dono, cápsula de outra
   `authEpoch`, fingerprint alterada, ciphertext adulterado, época regressiva e
   emissão regressiva falham sem substituir o registro. O caso de regressão
   deve manter um cofre antigo após redefinição e provar que a senha anterior
   não recupera acesso por meio da cápsula nova.
6. Provar que uma chave atacante com fingerprint autoconsistente falha fora do
   keyring; provar chave ativa, retirada antes/depois de `notAfter` e revogada.
7. Provar criação automática, throttle persistente de seis horas, backoff com
   jitter, estados terminais e single-flight diante de início + sync +
   visibilidade concorrentes, inclusive Web Lock e fallback por lease; reload
   conserva `sessionLookup`, novo login do mesmo dono troca o marcador e `401`
   bloqueia a geração capturada mesmo depois de limpar a sessão local.
8. Provar que uma resposta em voo é descartada após logout, troca de dono ou
   relogin do mesmo dono, e que duas conexões/abas não conseguem regredir
   `emitidoEm` no CAS.
9. Provar que a migração incremental não executa PBKDF2 sem limite no submit,
   mantém somente o cofre atual daquele CPF depois de convergir e não remove
   cofres de outras pessoas.
10. Provar monotonicidade de `lastTrustedTime` com relógio local atrasado e
   emissão/cápsula regressivas.
11. Provar que passkey não aceita grant de outro dono/época, que grant legado
    sem época falha fechado para cápsula e que uma cerimônia PRF válida
    reprovisiona o cofre depois do avanço de `authEpoch`. No cadastro, provar a
    sequência registro -> grant -> persistência/releitura -> estado `READY` sem
    invalidar a sessão atual.
12. Provar que remover o prompt não muda LoginPage, OfflineUnlockPage nem os
   avisos normais de indisponibilidade offline.
13. Provar upgrade com duas conexões: `versionchange` fecha a antiga,
    `BroadcastChannel` coordena as abas e `blocked` preserva o banco até retry.

### Integração e sincronização

1. Com IndexedDB real, preparar cofre e cápsula, recarregar os módulos, abrir
   offline, criar/editar RDO e confirmar que a outbox permanece idêntica após
   renovação.
2. Reconectar com sessão do mesmo dono, executar replay e provar uma única
   aplicação idempotente.
3. Falhar a renovação com rede, `5xx`, `403` e IndexedDB antes/depois do sync e
   provar que uma sincronização já concluída não muda. Separadamente, devolver
   `401`, provar invalidação/aborto pelo guard e fila integralmente preservada.
4. Trocar escopo, abrir o namespace novo e provar que o reconciliador abre um
   handle explícito do banco anterior, preserva payload/ID/ordem/`deviceId` e só
   poda a mutação após resultado `APLICADA` do push. Divergência de `deviceId`
   falha fechada; pull/ack não são executados no namespace antigo. Duas rotinas
   em bancos distintos são serializadas pelo lock/lease central do dono.
5. Criar namespace antigo contendo apenas `OBJECT_UPLOAD` + `mensagem_anexos` e
   provar upload pelo handle de origem, confirmação durável e ausência de push
   genérico indevido.
6. Criar namespace antigo contendo apenas foto pendente em `rdo_attachments` e
   provar upload, vínculo no servidor e marca local durável antes de concluir.
7. Trocar época e provar que o grant antigo não substitui o novo e que a mutação
   pendente não é apagada nem fica inalcançável.
8. Testar o endpoint completo com sessão, prova de cliente, CSRF, PostgreSQL e
   assinatura reais; resetar senha e, separadamente, revogar passkey, provar o
   avanço global de `authEpoch` e que o cookie anterior recebe `401` em vez de
   um grant da época nova. Avançar a época diretamente, sem depender de
   `revokeAll`, e provar o boundary; sessão migrada com época nula também recebe
   `401`. Criar uma passkey revogada antes da migração e provar que o bump de
   cutover impede seu cofre PRF antigo de consumir cápsula nova. Cadastrar uma
   passkey preserva época/sessão e permite emitir o grant necessário ao cofre
   PRF novo.

### Browser e release

Na mesma revisão publicada:

1. login online normal;
2. confirmação de cápsula cifrada e ausência de senha/grant em claro;
3. reload e fechamento completo do navegador sem prompt de renovação, provando
   persistência e uso real da `CryptoKey` não extraível;
4. corte de rede e reload completo da PWA;
5. desbloqueio por CPF/senha e, separadamente, por passkey;
6. criação e edição de RDO offline;
7. reconexão, renovação silenciosa e um único replay;
8. mudança controlada de escopo e reconciliação visível de mutação genérica,
   anexo de mensagem e foto de RDO no namespace anterior;
9. verificação de outbox vazia somente após confirmação do servidor;
10. nova queda de rede provando que o aparelho continua preparado;
11. verificação dos timestamps assinados provando duração de no máximo sete
    dias, sem alterar o relógio de produção.

O limite temporal completo é testado de forma determinística no mesmo caminho
de código com relógio injetado e TTL reduzido apenas no ambiente de teste. Um
soak real de sete dias pode continuar após a publicação como evidência
operacional adicional; até ele terminar, será relatado separadamente e não será
falsamente apresentado como teste real já concluído.

Testes locais, CI, deploy e aceitação autenticada em navegador serão relatados
como camadas separadas. A publicação só será considerada entregue depois de o
job de revisão exata, a revisão servida no Render e o artefato correspondente
no Cloudflare serem confirmados.
