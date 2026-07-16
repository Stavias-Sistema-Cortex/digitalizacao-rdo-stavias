# Financeiro: Produção, Receita e PDOR

## Objetivo

Reformular a seção **Visão geral** da tab Financeiro, no escopo de uma obra, para conectar a produção registrada nos RDOs ao resultado econômico. A tela deve mostrar o que foi executado, o custo realizado, a receita operacional estimada e a projeção final do PDOR, sem tratar estimativa como faturamento ou recebimento.

## Escopo

O trabalho se limita à área Financeiro e ao escopo `OBRA` já existente. A tab Obras permanece uma fonte de contexto operacional; não receberá o painel financeiro.

### Leitura principal

Quando uma obra estiver selecionada em Financeiro > Visão geral, a tela terá dois blocos complementares:

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

### Serviços executados

A Visão geral mostrará uma tabela agregada por serviço, com:

- nome do serviço e unidade;
- quantidade executada;
- custo realizado;
- receita operacional estimada;
- margem em valor e percentual;
- quantidade de RDOs de origem.

Uma ação de detalhe deve permitir identificar os RDOs que sustentam cada linha, preservando a rastreabilidade. Serviços sem preço contratual associado não recebem receita inventada: permanecem identificáveis e exibem receita indisponível.

### Filtros

Os filtros de data `De` e `Até` já existentes passam a recortar o resultado operacional e a tabela de serviços. Sem datas, o recorte é todo o período da obra. O snapshot PDOR continua sendo a última projeção disponível da obra, com sua data de referência visível; não será recalculado no navegador para simular um período filtrado.

## Arquitetura

O backend expõe uma leitura financeira operacional por obra e período, derivada das tabelas canônicas de execução de serviço de RDO e das alocações já usadas por `PrevisaoFinanceiraService`. A resposta é somente de consulta e aplica `FINANCEIRO_VISUALIZAR` antes de retornar qualquer dado.

O frontend adiciona esse contrato a `financeiroApi.ts`, faz o carregamento em `useFinanceiroData` apenas na Visão geral, e entrega os dados a um componente próprio de resultado operacional. `FinanceOverviewPanel` continua responsável pelo resumo de lançamentos, pagamentos e cobranças; ele apenas compõe os novos blocos, sem duplicar regras de cálculo.

## Estados e falhas

- Sem RDOs no período: exibir estado vazio explicando que ainda não há produção registrada, sem substituir por números de demonstração.
- Dados parciais: manter custos e quantidades disponíveis; sinalizar serviços cuja receita não puder ser calculada.
- Sem snapshot PDOR: exibir que a projeção ainda não está disponível, mantendo visível o resultado dos RDOs.
- Falha da leitura operacional: exibir erro recuperável no Financeiro sem ocultar o resumo financeiro já carregado.

## Testes e critérios de aceite

- O endpoint soma somente registros da obra e dentro do intervalo solicitado.
- A agregação preserva unidade e serviço, soma quantidade/custo/receita e contabiliza RDOs distintos.
- Serviço sem preço contratual não recebe receita ou margem fictícia.
- A autorização financeira bloqueia quem não possui `FINANCEIRO_VISUALIZAR`.
- A Visão geral mostra os valores operacionais, o PDOR identificado como projeção e os estados financeiro-operacionais sem troca de significado.
- Os filtros de período são enviados para a consulta operacional; o PDOR mostra a data do snapshot, sem falsa precisão por período.
