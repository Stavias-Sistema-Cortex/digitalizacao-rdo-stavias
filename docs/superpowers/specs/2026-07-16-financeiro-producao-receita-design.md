# Financeiro: Produção, Receita e PDOR

## Objetivo

Reformular a seção **Visão geral** da tab Financeiro, no escopo de uma obra, para conectar a produção registrada nos RDOs ao resultado econômico. A tela deve mostrar o que foi executado, o custo realizado, a receita operacional estimada e a projeção final do PDOR, sem tratar estimativa como faturamento ou recebimento.

## Escopo

O trabalho substitui a tab Financeiro atual por uma leitura econômica e ontológica da produção. Compras, notas fiscais, pagamentos, cobranças, rateios e centros de custo deixam de ser áreas navegáveis da tab, pois conflitam com o software financeiro já adotado pela Stavias. A tab Obras permanece uma fonte de contexto operacional; não receberá o painel financeiro.

O Financeiro abre com um consolidado das obras autorizadas e permite descer para uma obra e, então, para um tipo de serviço. O período selecionado afeta todas essas leituras.

### Leitura principal

O Financeiro terá três níveis de leitura:

1. **Consolidado entre obras**
   - produção, receita operacional estimada, custo e margem de todas as obras autorizadas;
   - projeção PDOR consolidada somente quando houver base comparável, sem somar snapshots incomparáveis;
   - comparação por obra para identificar o que está sendo produzido e onde.

2. **Consolidado por tipo de serviço**
   - cada tipo de serviço mostra as obras em que ocorre;
   - quantidade e unidade, custo, receita estimada e margem por obra e no total;
   - expansão para os RDOs que formam cada contribuição.

3. **Detalhe de uma obra**

Quando uma obra estiver selecionada, a tela terá dois blocos complementares:

1. **Resultado operacional dos RDOs**
   - produção realizada;
   - receita operacional estimada a partir de `execucao_servico_rdo` e do item contratual;
   - custo realizado dos serviços e das alocações de colaboradores;
   - margem atual em valor e percentual;
   - estados explícitos da receita: medida, aprovada, faturada e recebida.

2. **Projeção PDOR**
   - receita prevista final;
   - custo previsto final e margem prevista, quando disponíveis;
   - receita em risco, confiança e qualidade dos dados;
   - data de referência e status do cálculo.

O PDOR é uma projeção de receita final. Os valores originados pelos RDOs representam receita operacional estimada; faturamento e caixa devem continuar sendo exibidos somente pelos seus estados próprios.

### Serviços executados e tipos de serviço

A Visão geral mostrará uma tabela agregada por serviço, com:

- nome do serviço e unidade;
- quantidade executada;
- custo realizado;
- receita operacional estimada;
- margem em valor e percentual;
- quantidade de RDOs de origem.

Uma ação de detalhe deve permitir identificar os RDOs que sustentam cada linha, preservando a rastreabilidade. Serviços sem preço contratual associado não recebem receita inventada: permanecem identificáveis e exibem receita indisponível.

O agrupamento entre obras usa a identidade canônica do tipo de serviço (item contratual quando disponível, com nome e unidade explícitos para serviços ainda não vinculados). Não mistura unidades diferentes em um único volume.

### Painel de evidências ontológicas

Ao abrir uma obra, serviço ou métrica, um painel lateral mostra a linhagem completa: obra, serviço/tipo de serviço, RDOs de origem, item contratual, quantidade, custo, estados da receita, snapshot PDOR e eventos de sincronização relevantes. Cada evidência deve declarar a origem e a data observada; valores sem fonte ficam indisponíveis, jamais preenchidos por inferência.

### Filtros

Os filtros de data `De` e `Até` já existentes passam a recortar o resultado operacional e a tabela de serviços. Sem datas, o recorte é todo o período da obra. O snapshot PDOR continua sendo a última projeção disponível da obra, com sua data de referência visível; não será recalculado no navegador para simular um período filtrado.

## Arquitetura

O backend expõe uma leitura financeira operacional consolidada e por obra/período, derivada das tabelas canônicas de execução de serviço de RDO e das alocações já usadas por `PrevisaoFinanceiraService`. A resposta é somente de consulta e aplica `FINANCEIRO_VISUALIZAR` para cada obra incluída antes de retornar qualquer dado.

O frontend substitui a navegação e os painéis financeiros legados por uma única superfície de resultado operacional. Ela usa contratos próprios em `financeiroApi.ts`, carrega o consolidado e o detalhe conforme o contexto, e abre o painel lateral de evidências sem recomputar valores no cliente.

## Estados e falhas

- Sem RDOs no período: exibir estado vazio explicando que ainda não há produção registrada, sem substituir por números de demonstração.
- Dados parciais: manter custos e quantidades disponíveis; sinalizar serviços cuja receita não puder ser calculada.
- Sem snapshot PDOR: exibir que a projeção ainda não está disponível, mantendo visível o resultado dos RDOs.
- Falha da leitura operacional: exibir erro recuperável sem ocultar os demais resultados e suas evidências disponíveis.

## Testes e critérios de aceite

- O endpoint soma somente registros autorizados e dentro do intervalo solicitado.
- A agregação preserva obra, unidade e tipo de serviço; soma quantidade/custo/receita e contabiliza RDOs distintos.
- Serviço sem preço contratual não recebe receita ou margem fictícia.
- A autorização financeira bloqueia quem não possui `FINANCEIRO_VISUALIZAR`.
- A tab mostra consolidado por obra e por tipo de serviço, o PDOR identificado como projeção e os estados financeiro-operacionais sem troca de significado.
- Os filtros de período são enviados para a consulta operacional; o PDOR mostra a data do snapshot, sem falsa precisão por período.
- O painel de evidências apresenta a fonte de cada valor até o RDO e o evento ontológico correspondente.
