# Córtex local: integração autoritativa com Academy e Zeladoria

Data: 18/08/2026

## Objetivo

Restabelecer no servidor local do Córtex a leitura automática dos bancos atuais
do Academy e da Zeladoria, como ocorria antes da publicação web, sem tornar os
bancos fonte graváveis pelo Córtex e sem perder o histórico já consolidado no
PostgreSQL do Córtex.

O estado atual das fontes é autoritativo. Em especial, as pessoas desativadas
pelo Paulo no Academy devem deixar de ter acesso e de aparecer como ativas no
Córtex. Isso não é divergência a ser corrigida no Academy: é uma alteração de
negócio válida que o Córtex deve consumir.

## Restrições de segurança

- O Córtex não executa `INSERT`, `UPDATE`, `DELETE`, DDL ou procedimentos nos
  bancos MySQL do Academy e da Zeladoria.
- Nenhuma etapa desta entrega modifica conexões, consultas, usuários ou dados
  no MySQL Workbench.
- Os adaptadores JDBC abrem conexões somente leitura e os usuários de banco
  usados pelo servidor devem possuir somente `SELECT` nas tabelas necessárias.
- Senhas não entram no Git, nos logs, no chat ou em variáveis inline do
  contêiner. Elas são montadas como arquivos secretos somente leitura.
- Os dois MySQL usam TLS com validação do certificado apresentado. Como os
  certificados atuais não possuem hostname compatível, o modo permitido é
  `VERIFY_CA` com um truststore PKCS12 contendo exatamente o certificado folha
  observado e aprovado para cada fonte. `VERIFY_IDENTITY` continua permitido
  quando o certificado do servidor passar a cobrir o hostname.
- Falhas de fonte não derrubam a API, RDO, mapa, mensagens ou financeiro. Elas
  aparecem no estado da integração e nos registros de execução.

## Baseline observado

Leituras manuais somente leitura, realizadas antes desta especificação:

- Academy atual: 373 linhas em `dbstavias_acad.usuarios`;
- Zeladoria atual: 130 linhas em `dbstavias_zld.ativos`;
- último snapshot Academy consolidado no Córtex: 479 linhas lidas em
  27/07/2026, com 480 colaboradores de origem Academy preservados;
- último snapshot Zeladoria consolidado no Córtex: 130 linhas lidas em
  27/07/2026, com 130 ativos de origem Zeladoria preservados.

Esses números servem apenas como evidência de rollout. Eles não serão
hardcoded na aplicação nem usados como regra de validade futura.

## Topologia

```text
Navegadores Stavias
        |
        | HTTPS cortex.portalstavias.com.br
        v
Apache do servidor local
        |
        +-- PWA Córtex
        `-- /api -> API Córtex
                       |
                       +-- PostgreSQL Córtex (dados operacionais)
                       +-- Academy MySQL (SELECT, TLS)
                       `-- Zeladoria MySQL (SELECT, TLS)
```

Somente a API local executa os agendadores das fontes. O Render permanece com
os dois agendadores desligados, evitando dois escritores concorrentes no
PostgreSQL do Córtex e duas leituras periódicas desnecessárias nas fontes.

## Contrato do Academy

O adaptador lê um snapshot completo e consistente de `usuarios`, com paginação
determinística dentro de uma transação MySQL `REPEATABLE READ` somente leitura.
Antes de qualquer alteração no PostgreSQL do Córtex, o snapshot é rejeitado se
for incompleto, se possuir identificadores de origem inválidos ou duplicados,
ou se houver conflito de CPF/identidade que torne a aplicação ambígua.

Depois da validação, uma única transação PostgreSQL:

1. insere novos colaboradores;
2. atualiza nome, e-mail, grupo, perfil, papel e demais dados alterados;
3. desativa imediatamente linhas explicitamente inativas na fonte;
4. desativa colaboradores ausentes do snapshot completo após a carência
   configurada; no servidor local desta entrega, a carência será zero para que
   o estado atual seja refletido já no primeiro ciclo;
5. revoga a identidade de login Academy de quem ficou inelegível, sem liberar
   CPF para uma identidade errada;
6. preserva o colaborador histórico, suas relações, RDOs e evidências; não há
   exclusão física;
7. grava o resultado e o checkpoint no mesmo commit do domínio.

Uma falha tardia reverte colaboradores, identidades, memória operacional,
checkpoint e sucesso da execução. O registro de falha é persistido em uma
transação separada e contém somente mensagem segura.

## Contrato da Zeladoria

O adaptador da Zeladoria passa a oferecer o mesmo nível de confiança:

- snapshot completo, consistente e somente leitura;
- paginação sem limite silencioso de 10 mil linhas;
- validação de identificadores de origem vazios ou duplicados antes da
  aplicação;
- lock distribuído PostgreSQL por conector para impedir duas importações
  simultâneas;
- aplicação integral em uma única transação PostgreSQL;
- upsert de ativos presentes;
- desativação lógica de ativos que não aparecem no snapshot completo atual;
- reativação segura se o ativo voltar a aparecer;
- manutenção de `deleted_at`, `last_seen_at`, `source_hash` e `row_version`;
- preservação da linha e de todas as referências históricas de RDO.

Ativo ausente não é apagado. Ele fica `active=false` e deixa de ser oferecido
para novos apontamentos, mas continua resolvível em RDOs históricos, exportação,
sincronização offline e auditoria.

## Concorrência e atomicidade

Cada conector usa um advisory lock PostgreSQL dedicado mantido em conexão
própria durante a leitura e aplicação. Se outra execução já estiver ativa, a
segunda falha de modo seguro e visível, sem iniciar uma segunda leitura.

As leituras das duas fontes são independentes; uma fonte indisponível não
impede a outra. A aplicação de cada snapshot é atômica no PostgreSQL e nunca
mistura um snapshot parcial com o estado anterior.

## Configuração e segredos

O runtime de produção local recebe, para cada fonte:

- URL JDBC com `sslMode=VERIFY_CA`, caminho fixo do truststore e
  `fallbackToSystemTrustStore=false`;
- usuário MySQL somente leitura;
- arquivo de senha montado em `/run/secrets`;
- truststore PKCS12 montado em `/run/secrets`.

Em perfil `production`, senha inline é rejeitada mesmo com o agendador
desligado. Em testes e desenvolvimento isolado, a compatibilidade inline pode
existir somente para fixtures locais explícitas.

Mensagens públicas não revelam URL, usuário, senha, CPF, e-mail, SQL ou detalhe
de driver. Diagnóstico interno registra apenas fase, conector e código seguro.

## Agendamento e observabilidade

Depois de uma importação manual controlada e validada, ambos os agendadores
locais serão habilitados com intervalo inicial de cinco minutos. Cada integração
expõe:

- última execução e duração;
- registros lidos, inseridos, atualizados e desativados;
- último sucesso;
- estado `ATRASADA` quando o agendador está ligado e a janela esperada foi
  ultrapassada;
- falha segura, sem segredos ou dados pessoais.

Academy e Zeladoria usam a mesma política de staleness, cada uma com flag e
janela próprias. A readiness estrutural da API continua independente da
disponibilidade momentânea dessas fontes.

## Rollout

1. implementar e validar a revisão em branch isolada;
2. executar testes unitários, integrações PostgreSQL/MySQL, contratos de
   segurança e compose;
3. publicar por PR e confirmar o SHA exato no servidor local;
4. instalar senhas e truststores como secrets, com ação humana somente quando
   o macOS exigir autorização para liberar credenciais existentes;
5. validar conexão `SELECT`-only das duas fontes, ainda sem agendamento;
6. executar um snapshot Academy e um Zeladoria manualmente;
7. comparar o resultado com o baseline atual, aceitando as desativações reais
   do Academy e investigando apenas inconsistências estruturais;
8. validar login de pessoa ainda ativa, negação de pessoa desativada, lista de
   equipamentos, RDO histórico, criação offline e sincronização posterior;
9. habilitar os dois agendadores somente no servidor local;
10. observar ao menos dois ciclos completos sem duplicidade ou erro.

## Rollback

- Desligar os dois flags de agendamento interrompe novas importações sem afetar
  o restante do Córtex.
- A imagem anterior permanece disponível para rollback do runtime.
- Dados de fonte nunca são modificados, portanto não há rollback no MySQL.
- Desativações no Córtex são lógicas; uma correção legítima na fonte é refletida
  pelo próximo snapshot e pode reativar a entidade sem perder histórico.
- Não haverá downgrade destrutivo de schema. Correções de banco são forward-only.

## Critérios de aceitação

- nenhuma escrita observada nos MySQL das fontes;
- TLS validado com truststore distinto para Academy e Zeladoria;
- snapshot incompleto ou ambíguo produz zero alteração de domínio;
- desativações atuais do Academy são refletidas com revogação de login;
- login de usuário ativo continua funcionando;
- ativos ausentes da Zeladoria ficam inativos, mas RDOs históricos continuam
  íntegros;
- falha tardia reverte integralmente a aplicação do snapshot;
- duas execuções concorrentes do mesmo conector não aplicam em paralelo;
- ambos os estados podem ficar `ATRASADA` sem derrubar a readiness da API;
- hosted/Render permanece com os agendadores desligados;
- dois ciclos automáticos locais terminam com sucesso e sem alterações espúrias.
