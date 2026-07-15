# Córtex: Financeiro Geral, Documentos Fiscais e Operação Rastreável

**Data:** 2026-07-14
**Status:** implementado e verificado; validação probabilística externa do PDOR permanece pendente
**Worktree:** `feat/cortex-mensagens-financeiro`
**Escopo:** banco, API, PWA/offline, UI, autorização, documentos fiscais, ontologia, StavIA, PDOR e verificação

## 1. Objetivo

Evoluir a rodada atual de Mensagens e Financeiro sem refazer o Córtex. O
Financeiro deixa de ser uma visão obrigatoriamente presa a uma obra e passa a
controlar compras, notas, lançamentos e rateios destinados a obras, ativos ou
custos administrativos. A mudança também corrige a linguagem e a paleta da
interface, fortalece Mensagens, expõe a criação de obra aos usuários Alfa,
permite a administração auditável de papéis e estabelece gates objetivos para
a cobertura ontológica e a validação do Monte Carlo do PDOR.

O resultado deve continuar offline-first, usar dados reais, preservar a
autoridade do backend e não inferir entidades financeiras ou ativos a partir de
texto legado.

## 2. Estado atual e problemas confirmados

- `FinanceiroPage` seleciona automaticamente a primeira obra e não renderiza o
  workspace sem `obraId`.
- Compras, notas fiscais, lançamentos, centros de custo e permissões têm
  `obra_id` obrigatório nas migrations V31-V33.
- `finance_lancamento_alocacao` distribui somente entre centro de custo e
  categoria pertencentes à mesma obra.
- A página Financeiro introduziu laranja, fundo quadriculado e o texto
  promocional “Dinheiro da obra, sem pontos cegos”, em conflito com a paleta e
  a voz existentes.
- A barra atual possui filtros fixos, mas não permite criar predicados manuais.
- O backend já armazena e relaciona documentos de nota, porém a página apenas
  lista e visualiza documentos previamente vinculados. Não existe upload no
  fluxo da nota nem extração automática.
- `ocr_status` permanece `NAO_CONFIGURADO`; nenhum valor deve ser apresentado
  como extraído enquanto um extrator real não o produzir.
- Mensagens é funcional e offline-first, mas usa apenas duas colunas e uma
  composição genérica de hero, lista e balões.
- A criação de obra existe em `GestaoObrasPage`, mas não está disponível
  diretamente em `ObrasPage`.
- ALFA é o papel administrativo global. Não há capacidade separada e auditável
  para promover ou rebaixar papéis.
- O PDOR 0.4 executa Monte Carlo determinístico e possui testes de invariantes,
  direção de risco e histórico, mas ainda não tem backtest fora da amostra nem
  calibração probabilística comprovada.

## 3. Restrições globais

- Migrations V1-V33 não serão editadas. Toda evolução parte da V34.
- Não será criada “obra corporativa” fictícia para contornar o modelo atual.
- Valores monetários usam `DECIMAL(19,4)` no banco e cálculo decimal na API.
- Rateio ativo fecha exatamente com o valor da fonte na mesma moeda.
- Arquivos permanecem fora do MySQL; o banco guarda metadados, hash e vínculos.
- CPF, e-mail, token, segredo, fornecedor, nota, ativo ou valor não será
  hardcoded nem inferido de texto legado.
- ALFA/BETA continuam sendo os únicos papéis operacionais.
- Administração de papéis é uma capacidade adicional, não um terceiro papel.
- Toda leitura e escrita aplica autorização no backend; esconder um botão não é
  mecanismo de segurança.
- Escritas offline reutilizam o IndexedDB, o outbox e os handlers de sync
  existentes.
- Cada mutação nova precisa ter auditoria, evento operacional, projeção
  ontológica, evidência StavIA quando consultável e teste de cobertura.
- Backend e testes Maven usam JDK 21.
- Toda interface e mensagem destinada ao usuário fica em português.

## 4. Arquitetura escolhida

### 4.1 Unidade de controle

`finance_unidade_controle` representa o destino econômico real. Tipos iniciais:

- `OBRA`: referência obrigatória para uma obra real;
- `ATIVO`: referência obrigatória para um registro real de `asset`;
- `ADMINISTRATIVO`: custo compartilhado sem obra ou ativo;
- `CORPORATIVO`: visão consolidada, nunca uma obra substituta.

Cada unidade tem nome, código, status, responsável e versão. Um `CHECK` exige a
referência compatível com o tipo e impede obra/ativo simultâneos. Obras
existentes recebem uma unidade por backfill determinístico. O backfill não cria
compras, notas, lançamentos, fornecedores ou ativos.

`CORPORATIVO` é um escopo de consulta, não um destino selecionável de rateio. Um
custo compartilhado usa uma unidade `ADMINISTRATIVO` real e nomeada.

### 4.2 Compatibilidade com o modelo por obra

A V34 adiciona `unidade_controle_id` aos agregados atuais e preenche o campo a
partir da unidade da obra. Durante a transição, `obra_id` permanece preenchido
para registros legados. Novos registros gerais usam a unidade como autoridade;
`obra_id` só é preenchido quando a unidade é do tipo `OBRA`.

Constraints e FKs compostas que hoje exigem obra são substituídas de forma
aditiva e ordenada pela migration, sem desativar integridade referencial. A
migration primeiro cria unidades e novas chaves, depois faz o backfill, valida
linhas órfãs e somente então relaxa `obra_id`.

### 4.3 Rateio canônico

`finance_rateio` é o cabeçalho versionado de uma distribuição. Ele referencia
exatamente uma origem por FKs nulas mutuamente exclusivas:

- compra;
- nota fiscal;
- lançamento financeiro.

`finance_rateio_item` contém unidade de controle, centro de custo opcional,
categoria opcional, valor, percentual informativo, responsável e versão. O
serviço valida em uma única transação:

1. origem existente e autorizada;
2. mesma moeda;
3. destinos ativos e visíveis ao ator;
4. centro/categoria compatíveis com a unidade;
5. valores positivos;
6. soma dos itens exatamente igual ao valor líquido da origem;
7. `baseVersao` atual;
8. `clientMutationId` idempotente.

O percentual é derivado do valor e não é a fonte de verdade. Diferença de
arredondamento não é absorvida silenciosamente: a interface mostra a diferença
e exige que uma parcela seja corrigida.

Cada versão gera histórico imutável com estado anterior/novo e um diff por
destino. Arquivar um destino usado não altera rateios históricos.

### 4.4 Compra e controle individual de equipamento

Um item de compra pode ser marcado como `CAPITALIZAVEL`, mas texto livre não
cria ativo automaticamente. Quando a compra chega ao status configurado como
recebido, um Alfa ou operador autorizado pode:

- vincular o item a um `asset` existente; ou
- confirmar a criação de um novo `asset` com código, nome, categoria e origem
  `CORTEX_FINANCEIRO`.

O vínculo preserva custo de aquisição, fornecedor, compra, nota, unidade de
controle, responsável, data e ator. Uma compra com quantidade maior que um
exige criar ou vincular cada unidade física individualmente quando o controle
individual estiver ativo.

## 5. Escopo e autorização

### 5.1 Financeiro

- ALFA visualiza e administra todas as unidades.
- BETA acessa unidade `OBRA` somente com vínculo ativo e capability financeira
  correspondente.
- BETA acessa unidade `ATIVO` somente com concessão explícita da unidade e
  capability correspondente.
- Unidades `ADMINISTRATIVO` e visão `CORPORATIVO` são exclusivas de ALFA nesta
  rodada.
- Consultas consolidadas aplicam a união dos escopos autorizados antes de
  agregar; não agregam globalmente para filtrar depois.

`finance_colaborador_permissao` passa a aceitar `unidade_controle_id`. Grants
legados por obra são migrados para a unidade da obra sem ampliar o acesso.

### 5.2 Administração de papéis

`ADMINISTRAR_PAPEIS` é uma capacidade concedida somente a uma conta ALFA. A
conta administrativa pode alternar `papel_acesso` entre `ALFA` e `BETA`.

Invariantes:

- o último ALFA ativo não pode ser rebaixado ou desativado;
- o último portador ativo de `ADMINISTRAR_PAPEIS` não pode perder a capacidade;
- um administrador não pode se rebaixar enquanto for o último administrador;
- rebaixamento revoga sessões atuais e remove imediatamente o escopo global;
- grants específicos existentes não são inventados nem apagados, mas só passam
  a valer segundo as regras BETA;
- promoção, rebaixamento, concessão e revogação exigem justificativa e geram
  histórico imutável.

## 6. Documentos fiscais e preenchimento automático

### 6.1 Formatos aceitos

Documentos fiscais principais:

- XML NF-e/NFC-e/CT-e e XML NFS-e suportado por adaptador;
- PDF textual ou digitalizado;
- JPEG;
- PNG;
- WebP;
- TIFF.

Arquivos complementares continuam podendo usar os formatos já autorizados pelo
storage, mas não alimentam campos fiscais sem um extrator compatível.

O inspetor valida MIME declarado e detectado, extensão, tamanho, SHA-256 e
assinatura mágica. XML usa parser com DTD e entidades externas desabilitados.
ZIP não é tratado automaticamente como nota fiscal; precisa ser aberto e cada
entrada validada com limites contra decompression bomb.

### 6.2 Pipeline

```text
seleção local
  -> Blob + SHA-256 no cofre do usuário
  -> BINARY_UPLOAD no outbox
  -> inspeção e armazenamento imutável no servidor
  -> finance_document_extraction_job
  -> extrator por formato
  -> candidatos com origem/confiança
  -> conferência automática
  -> revisão humana quando necessária
  -> criação/atualização transacional da nota e do rateio
```

Extratores:

- `XmlFiscalExtractor`: leitura estruturada e validação da chave de acesso,
  emitente, destinatário, itens, datas, totais e tributos;
- `PdfTextFiscalExtractor`: PDFBox para documentos com camada de texto;
- `OcrFiscalExtractor`: OCR real para imagem ou PDF renderizado, com idioma
  português e preservação de caixas/páginas;
- `InvoiceExtractionCoordinator`: escolhe o extrator, cruza resultados e aplica
  regras de confiança.

O adaptador OCR é configurável. O provider local/contêiner usa Tesseract com
modelo português. Um provider externo futuro implementa a mesma interface e
não muda o contrato do domínio.

### 6.3 Candidatos, confiança e revisão

Cada campo extraído registra:

- valor normalizado e texto original;
- extrator e versão;
- documento/hash;
- página, região ou caminho XML;
- confiança;
- regra de validação aplicada.

XML estruturalmente válido é preferido a OCR. PDF/imagem nunca confirma uma nota
sozinho quando chave, fornecedor ou total não atingem os limiares configurados.

O sistema recalcula `valorLiquido = valorBruto - desconto + acrescimo -
retencoes` e compara total de itens, tributos e total declarado. Divergência
gera `REVISAO_NECESSARIA`, preserva o original e não cria lançamento ou
liquidação.

O status de autorização fiscal só aparece como confirmado quando um provider
real de consulta fiscal o comprovar. Estrutura XML e chave válidas não são
apresentadas como autorização SEFAZ.

### 6.4 Upload e autoria

`finance_nota_fiscal_documento` passa a registrar explicitamente:

- `enviado_por`;
- `enviado_em`;
- `dispositivo_id`;
- `client_mutation_id`;
- `sha256_no_cliente` e `sha256_no_servidor`;
- status da inspeção;
- job/versão do extrator;
- `confirmado_por` e `confirmado_em`.

O proprietário do `stored_object` continua preservado, mas não substitui a
autoria do vínculo fiscal. Hashes cliente/servidor diferentes bloqueiam o
vínculo e colocam o objeto em quarentena.

## 7. Filtros manuais

A barra mantém filtros rápidos. O botão `Adicionar filtro` cria uma regra com:

- campo allowlisted;
- operador compatível com o tipo;
- valor tipado;
- remoção individual.

Operadores iniciais:

- texto: contém, não contém, igual, começa com;
- enum/referência: é, não é, está em;
- número/moeda: igual, maior, menor, entre;
- data: em, antes, depois, entre;
- booleano/status: é.

O usuário escolhe combinar `todos` (AND) ou `qualquer` (OR). A URL guarda uma
representação canônica e versionada, limitada a 20 regras. O backend converte
somente campos e operadores allowlisted em query parametrizada; a UI nunca
envia SQL, nomes de coluna ou fragmentos arbitrários.

Filtros rápidos e manuais alimentam o mesmo `FinanceQuerySpec`, utilizado por
listagens, indicadores e exportação. Totais diferentes entre tela e CSV são
falha de integridade.

## 8. Interface Financeiro

### 8.1 Voz e paleta

Remover o laranja, o grid decorativo e a frase promocional. Cabeçalho:

```text
Financeiro
Compras, notas fiscais, rateios, pagamentos e cobranças da operação.
```

Tokens obrigatórios:

- asfalto `#18231F`;
- teal Stavias `#124E4A`;
- teal forte `#0D3F3C`;
- amarelo operacional `#F2C800`;
- canvas `#F4F6F4`;
- superfície `#FFFFFF`;
- borda `#D8DFDA`;
- texto secundário `#68756E`.

Poppins permanece na interface. Valores usam algarismos tabulares, sem criar
uma família paralela “Finance”. A faixa amarela aparece uma única vez para
indicar o escopo ativo.

### 8.2 Estrutura

O seletor de escopo não seleciona automaticamente a primeira obra:

```text
Visão geral | Obras | Equipamentos | Administrativo
```

Depois do tipo, o usuário pode escolher uma unidade ou manter o consolidado
autorizado. A navegação contém:

1. Visão geral;
2. Compras;
3. Notas fiscais;
4. Pagamentos e cobranças;
5. Rateios;
6. Centros de custo;
7. Relatórios.

Estados vazios explicam a próxima ação. KPIs só aparecem com dados e período
identificados. Não haverá números decorativos, gráficos sintéticos, sombras
pesadas, glassmorphism ou chips sem função.

O fluxo de nova nota começa pelo arquivo. Após extração, o formulário mostra
valores preenchidos, origem e confiança, com diferenças destacadas. Também é
possível iniciar manualmente e anexar documentos depois.

## 9. Interface Mensagens

Mensagens torna-se uma bancada operacional, sem hero promocional:

```text
┌──────────────────┬─────────────────────────────┬──────────────────────┐
│ busca/conversas  │ histórico                   │ contexto             │
│ obra e não lidas │ mensagens e estados reais   │ obra, pessoas, docs  │
└──────────────────┴─────────────────────────────┴──────────────────────┘
```

- busca integrada ao topo da lista;
- lista densa com obra, última mensagem, horário e não lidas;
- histórico agrupado por data;
- autor exibido apenas quando muda;
- balões discretos, sem excesso de bordas;
- anexos apresentados como documentos;
- painel de contexto com participantes, obra e arquivos;
- composer compacto, expansível e operável por teclado;
- status de sync exibido somente quando útil: fila, enviando, falhou;
- retry ao lado do item falho;
- mobile em `lista -> conversa -> contexto`, preservando rascunho;
- foco visível e `prefers-reduced-motion` respeitado.

Nenhuma mudança altera a fronteira de autorização ou a persistência offline já
implementada.

## 10. Obras

`ObrasPage` recebe o botão `Criar obra` no cabeçalho somente quando a sessão
efetiva é ALFA. O botão abre um componente compartilhado com
`GestaoObrasPage`; formulário, validação e chamada de API não são duplicados.

O backend continua exigindo `requireAlfa()`. Após criar, a nova obra é
hidratada, selecionada e registrada na timeline. BETA não recebe o botão e uma
chamada direta continua retornando 403.

## 11. Ontologia e StavIA

### 11.1 Relações mínimas

```text
PESSOA --ENVIOU--> DOCUMENTO_FISCAL
DOCUMENTO_FISCAL --REPRESENTA--> NOTA_FISCAL
PESSOA --CONFIRMOU--> EXTRACAO_FISCAL
NOTA_FISCAL --ORIGINOU--> LANCAMENTO_FINANCEIRO
LANCAMENTO_FINANCEIRO --RATEADO_PARA--> UNIDADE_CONTROLE
UNIDADE_CONTROLE --REFERE_SE_A--> OBRA|ATIVO
COMPRA --ADQUIRIU--> ATIVO
PESSOA --ALTEROU_PAPEL_DE--> PESSOA
PESSOA --CRIOU--> OBRA
```

Cada relação inclui evidência de origem, ator, tempo, correlação e IDs de
domínio. Documentos e valores sensíveis não entram em texto livre da memória.

### 11.2 Gate de cobertura

Um `OperationalMutationCatalog` registra toda mutação suportada. Para cada
entrada, o build exige:

- evento de domínio;
- schema/versionamento do payload;
- projector ontológico;
- política de acesso;
- tipo de evidência StavIA ou justificativa explícita de não consulta;
- teste de idempotência e rastreabilidade.

`OntologyMutationCoverageTest` falha quando uma mutação não cobre algum item.
Testes de integração verificam objetos, relações, eventos e timeline para
Financeiro, documentos, rateio, ativos, papéis, obras e mensagens.

### 11.3 Consistência e desempenho

O serviço de domínio confirma a transação e grava um evento/outbox atômico. Um
único projector idempotente materializa `cortex_*` e `ontology_*`; controllers
não fazem dual-write. Falha de projeção é reprocessável por `eventId` e fica
visível operacionalmente.

Índices cobrem tipo+entidade, relação+origem+destino, ator+tempo,
unidade+tempo e correlação. Leituras em lote evitam N+1. Retenção não apaga
história financeira obrigatória. BETA é escopado antes da consulta.

## 12. PDOR 0.5 e validação Monte Carlo

### 12.1 Avaliação do 0.4

Pontos fortes confirmados:

- seed determinística e snapshots versionados;
- 2.000 a 80.000 iterações em lotes;
- percentis P10/P50/P80/P95;
- probabilidades de receita abaixo de 100%, 95% e 90% do contrato;
- invariantes de ordenação, finitude e direção de risco;
- histórico semanal real para produtividade e material;
- dependências causais simples;
- 25 testes focais passando em JDK 21 em 2026-07-14.

Limitações:

- quatro observações habilitam premissa histórica;
- equipamento permanece em premissa de protótipo;
- triangular é escolhida sem teste de aderência ou comparação de modelos;
- coeficientes causais são fixos;
- lotes cumulativos da mesma sequência podem declarar estabilidade prematura;
- confiança atual é heurística;
- não há sensibilidade quantitativa, backtest temporal ou cobertura fora da
  amostra.

Conclusão: há potencial para apoio à decisão e priorização, mas o 0.4 não deve
ser rotulado como previsão probabilística validada.

### 12.2 Evolução proposta

Status:

- `PROTOTIPO`: qualquer driver relevante usa premissa padrão;
- `HISTORICO_ASSISTIDO`: distribuição usa histórico, sem validação externa;
- `VALIDADO`: somente após gates fora da amostra.

O mínimo inicial para ajustar um driver é 12 semanas independentes e
configurável. A API preserva tamanho e qualidade da amostra. Séries
autocorrelacionadas têm tamanho efetivo reportado.

O motor separa:

- incerteza aleatória dos eventos;
- incerteza epistêmica dos parâmetros.

Parâmetros históricos usam bootstrap. A simulação executa réplicas
determinísticas independentes, com seeds derivados de snapshot+réplica. A
convergência exige estabilidade consecutiva e erro Monte Carlo abaixo do
limiar para P50/P80/P95 e probabilidades.

Cada snapshot inclui análise de sensibilidade por driver e identifica quais
premissas mais alteram o resultado.

### 12.3 Backtest

Para obras concluídas ou períodos com desfecho conhecido, o validador recria
snapshots em cortes históricos sem olhar o futuro e compara com receita final
realizada.

Métricas:

- cobertura empírica de P50/P80/P95;
- pinball loss dos quantis;
- Brier score das probabilidades de shortfall;
- erro/viés do P50;
- estabilidade por fase, tipo de obra e quantidade de histórico.

`VALIDADO` exige amostra mínima, limites versionados e desempenho fora da
amostra. Ausência de desfechos mantém `HISTORICO_ASSISTIDO`; nunca é convertida
em sucesso por falta de evidência.

Financeiro geral fornece evidências adicionais, mas custo e aquisição de ativo
não são convertidos automaticamente em receita. A relação com PDOR precisa ser
causal, documentada e testada.

## 13. Tratamento de erros

- Upload interrompido permanece no outbox com retry e hash original.
- MIME ou hash divergente coloca o objeto em quarentena.
- XML malformado ou PDF protegido mantém o documento e solicita revisão.
- OCR indisponível não inventa valores; permite digitação manual auditada.
- Duplicidade por hash ou chave fiscal abre o registro existente ou conflito
  explícito, conforme escopo e autorização.
- Total fiscal divergente bloqueia lançamento e liquidação.
- Rateio incompleto mostra diferença exata e não salva.
- Conflito de versão devolve estado atual para reconciliação.
- Perda de escopo durante operação causa 403 e não aplica mutação parcial.
- Falha do projector não desfaz o domínio; permanece pendente e reprocessável.
- PDOR sem histórico suficiente informa o status e as premissas de protótipo.

## 14. Estratégia de testes

### Banco e domínio

- Flyway V1-V34+ do zero e upgrade representativo V33 -> atual;
- constraints de unidade, referência exclusiva e rateio exato;
- backfill sem ampliar acesso;
- concorrência, idempotência e optimistic locking;
- promoção/rebaixamento e proteção dos últimos Alfa/admin;
- criação/vínculo individual de ativos;
- auditoria completa de upload, extração, confirmação e rateio.

### Documentos

- fixtures sintéticas mínimas XML, PDF textual, PDF digitalizado, JPEG, PNG,
  WebP e TIFF;
- MIME falso, arquivo truncado, XML XXE, ZIP bomb e hash divergente;
- precedência XML sobre OCR;
- cálculo e divergência de totais;
- provider OCR indisponível;
- arquivo repetido e chave fiscal duplicada;
- extração nunca confirma autorização fiscal sem provider real.

### API, sync e autorização

- ALFA global, BETA por unidade/capability e bloqueio corporativo;
- filtro manual parametrizado e allowlisted;
- mesmos filtros/totais em lista, visão geral e CSV;
- upload offline -> reconnect -> extração -> revisão;
- downgrade revoga sessão e escopo;
- chamadas diretas de criação de obra por BETA retornam 403.

### Ontologia e StavIA

- gate de cobertura para todas as mutações;
- projeção idempotente e reprocessamento;
- timeline inclui uploader, confirmador, alteração e rateio;
- respostas StavIA usam IDs/evidências autorizadas e não vazam unidades.

### PDOR

- invariantes atuais;
- réplicas e erro Monte Carlo;
- sensibilidade e monotonicidade;
- bootstrap/intervalos;
- backtest sem vazamento temporal;
- cobertura, pinball, Brier e viés;
- status nunca avança sem evidência.

### Interface

- Vitest dos filtros e contratos;
- renderização dos estados vazio, carregando, erro, offline e revisão;
- acessibilidade por teclado/foco;
- desktop, tablet e mobile;
- screenshot/visual QA comprovando paleta e ausência do hero genérico;
- criação de obra visível somente para ALFA;
- Mensagens preserva rascunho e anexos no drill-down mobile.

## 15. Evidência de conclusão

A entrega só é concluída quando houver:

1. migration aditiva aplicada em MySQL descartável e upgrade testado;
2. testes Maven JDK 21, Vitest, lint e build sem falhas;
3. browser QA real das páginas Financeiro, Mensagens e Obras;
4. upload e extração exercitados em todos os formatos declarados;
5. rateio geral provado para obra, ativo e administrativo;
6. uploader e histórico recuperáveis pela timeline;
7. promoção/rebaixamento e revogação de sessão provados;
8. cobertura ontológica sem mutações ausentes;
9. relatório de validação PDOR com limitações e métricas reais;
10. nenhuma alegação de OCR externo, autorização fiscal ou validação
    probabilística sem a evidência correspondente.

### Evidência executada em 2026-07-15

- `mvnw clean test` com JDK 21: 859 testes, 0 falhas, 0 erros. Após a
  complementação MySQL real, o agregado final ficou em 45 skips ambientais;
- MySQL 8.4 real: V1-V39 e integrações de rateio, nota/documento, governança
  Alfa/Beta, carga PDOR e fluxo CW38386 sem skips;
- frontend após o conserto encontrado no QA: 41 arquivos e 208 testes
  aprovados, lint e build de produção aprovados;
- navegador real em 1280 px e 390 px: Financeiro, Mensagens, Obras e Gerir
  obras sem overlay, erro de página ou overflow horizontal;
- upload XML exercitado pelo navegador com hash, extrator, confiança, alerta de
  revisão e preenchimento de número, série, emissão, chave e valores. PDF e
  imagens permanecem cobertos pelos testes dos extratores/providers; não se
  declara OCR externo nem autorização SEFAZ;
- a captura de screenshot do navegador headless não ficou disponível no
  ambiente. A evidência visual automatizada desta execução é estrutural
  (árvore acessível, estilos computados, dimensões e navegação), não uma imagem;
- o relatório `docs/checkpoints/pdor-monte-carlo-potential-validation-2026-07-15.md`
  confirma estabilidade numérica controlada e registra, sem ocultar, os gates
  de backtest e validação externa ainda ausentes.

## 16. Fora de escopo

- contabilidade fiscal completa ou substituição de ERP;
- escrituração/SPED;
- pagamento bancário automático;
- consulta SEFAZ/NFS-e sem credenciais/provider real;
- criação de ativos por inferência de descrição;
- conversão automática de textos legados de RDO em entidades financeiras;
- treinamento de modelo OCR próprio;
- uso do PDOR como decisão autônoma ou garantia de receita.

## 17. Decomposição da entrega

O desenho é um contrato integrado, mas a implementação será dividida em cinco
incrementos revisáveis. Cada incremento termina com software executável e seus
próprios gates; nenhum deles redefine a conclusão do objetivo completo.

1. **Fundação financeira geral:** unidade de controle, grants generalizados,
   rateio canônico, compatibilidade V33 e vínculo individual com ativos.
2. **Documentos fiscais:** storage/offline, formatos, extração, revisão,
   integridade de valores, autoria e histórico.
3. **Operação e interface:** filtros manuais, Financeiro na paleta, Mensagens em
   três painéis e criação compartilhada de obra.
4. **Governança e ontologia:** administração de papéis, revogação de sessão,
   catálogo de mutações, projector idempotente, timeline e StavIA.
5. **PDOR 0.5:** incerteza epistêmica, réplicas/convergência, sensibilidade,
   backtest e relatório de validação.

Dependências: 2 depende do modelo de unidade/rateio de 1; 3 consome contratos de
1 e 2; 4 atravessa todos os incrementos e seu gate começa em 1; 5 pode ser
desenvolvido em paralelo conceitual, mas só incorpora evidências financeiras
depois que 1 estiver validado.

## 18. Referências

- GAO Cost Estimating and Assessment Guide, GAO-09-3SP:
  <https://www.gao.gov/assets/gao-09-3sp.pdf>
- NASA, Characterizing Epistemic Uncertainty for Launch Vehicle Designs:
  <https://ntrs.nasa.gov/citations/20160007007>
- Especificação anterior da rodada:
  `docs/superpowers/specs/2026-07-13-auth-mensagens-financeiro-design.md`
