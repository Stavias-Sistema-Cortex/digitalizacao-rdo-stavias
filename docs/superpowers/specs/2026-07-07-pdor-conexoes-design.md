# PDOR + Conexões Reais — Design

**Data:** 2026-07-07
**Status:** aprovado em brainstorm
**Relacionado:** sequência da Home (`2026-07-06-home-page-design.md`)

## Contexto

O negócio renomeou PDOC → **PDOR**, e o conceito passa a ser **receita, não custo**.
Hoje o módulo `pdoc` da API é um motor probabilístico de custo final (EAC,
P50/P80, Monte Carlo) usado pela Stav.IA. Além do rename/remodelagem, esta
iniciativa fecha lacunas de integração da Home recém-entregue: registro
ontológico de obras e remoção de valores inventados (links, status de chips).

## Decisões de produto

| Tema | Decisão |
|---|---|
| O que o PDOR prevê | **Receita final da obra** (P50/P80/P95 de receita), com dados reais; previsão de custo deixa de ser exposta |
| Histórico PDOC | Ambiente dev: migration dropa e recria; sem retrocompatibilidade com strings/linhas antigas |
| Links "Mais Stavias" | Configuráveis via `VITE_STAVIAS_LINKS` (JSON); fallback apenas `https://www.stavias.com.br` |
| Chips de status | Derivados dinamicamente dos status distintos das obras locais + mapa de rótulos pt-BR configurável |
| Série "PDOR vs contrato" da Home | Continua da Previsão Financeira (`receitaPrevistaFinal ÷ valorContratual`); P50/P80 no gráfico fica para ciclo futuro |

## Seção 1 — PDOR: rename + remodelagem para receita (API)

- Pacote `com.projeto.cortex.pdoc` → `com.projeto.cortex.pdor`; classes
  `Pdoc*` → `Pdor*` (incl. `PdocEngine`/`PdocContextBuilder` em
  `intelligence`); endpoints `/api/obras/{id}/pdoc/{calcular,atual,historico}`
  → `/pdor/...`; testes renomeados junto.
- Versões: `PDOR-0.3.0` / `PDOR-ASSUMPTIONS-0.3.0` (bump maior — semântica muda).
- **Migration V25**: `DROP TABLE pdoc_snapshot` + `CREATE TABLE pdor_snapshot`
  com colunas de receita (`receita_estimada_final`, `p50/p80/p95` de receita,
  probabilidades de shortfall) e mesmas chaves (obra, idempotência, evento de
  origem).
- **Inputs remodelados** (`RealPdorInputLoader`), todos de dados reais:
  - orçamento de receita = **valor contratual** (SUM `item_contratual.valor_total`, status ATIVO);
  - realizado = **receita medida/validada acumulada** (execuções de RDO com
    `status_validacao IN ('REGISTRADA','VALIDADA')`, mesmas fontes da
    Previsão Financeira);
  - avanço físico = produção planejada × realizada (inalterado).
- **Saídas remodeladas** (`PdorResultadoResponse`): `receitaEstimadaFinal`,
  `racs` (análogo aos `eacs`), `p50/p80/p95` de receita e **risco com direção
  invertida**: `probabilidadeAbaixoContrato`, `probabilidadeAbaixo95Pct`,
  `probabilidadeAbaixo90Pct` (risco = não capturar o contrato, não "exceder").
- Snapshot registra objeto ontológico `PDOR` + relação `ANALISA` com a obra
  (rename do comportamento atual) e evento `PDOR_CALCULADO` /
  `PDOR_INSUFICIENTE` na memória operacional.

## Seção 2 — Stav.IA fala PDOR

**Servidor:** intent `CONSULTAR_PDOC` → `CONSULTAR_PDOR`; evidence type
`"PDOC"` → `"PDOR"`; pacote `stavia/knowledge/pdoc` → `stavia/knowledge/pdor`
(providers/sources renomeados); `StaviaVersions.PDOC_SOURCE` →
`PDOR_SOURCE` (bump); keywords do classificador e catálogo do interpretador
LLM ganham vocabulário de receita ("pdor", "receita prevista", "previsão de
receita", "vai bater o contrato"); labels/prompts de geração atualizados para
receita.

**Web (motor local offline):** topics/intents/evidências `PDOC*` → `PDOR*`
(`staviaLocalEngine.ts`, `stavia.types.ts`); campo `pdocs` → `pdors` no
snapshot local, **coordenado com o payload do `StaviaSnapshotService` do
servidor** (renomear lá também — dev-only, sem leitura retrocompatível);
textos visíveis em pt-BR falam de receita ("Snapshot PDOR salvo no
dispositivo", etc.).

## Seção 3 — Ontologia no ciclo da obra

- `ObraService.criarObra` e `ObraSeedImportService` passam a chamar
  `CortexOperationalMemoryService.registrarObjeto("OBRA", id, codigo, nome,
  status, fonte)` antes do evento já emitido — paridade com a Previsão
  Financeira. Import: um `registrarObjeto` por linha importada, dentro do
  mesmo tratamento por linha existente.
- Resultado: obra, previsão, PDOR e eventos conectados no grafo consultado
  pelas knowledge sources da Stav.IA (`ontology`, `finance`, `worksite`).

## Seção 4 — Home sem hardcode

- **Chips dinâmicos** (`homeFilters.ts`): lista de chips construída dos
  status distintos (normalizados) das obras locais; rótulos pt-BR vindos de
  `obraStatusLabels.ts` (mapa configurável; fallback = o próprio valor
  normalizado com capitalização); chip "Todas" sempre presente. Remove o
  mapa `CHIP_STATUSES` inventado.
- **Mais Stavias** (`MaisStaviasCard.tsx`): links de
  `import.meta.env.VITE_STAVIAS_LINKS` (JSON `[{label, href}]`, validado
  defensivamente); fallback `[{ "Portal Stavias", "https://www.stavias.com.br" }]`.
- Constantes restantes nomeadas/documentadas (LIMIT 200 do endpoint de
  obras relacionadas; page size 100 do histórico) — sem mudança de valor.

## Testes

- **API:** rename dos testes existentes do pdoc (incl. integração MySQL)
  com asserts adaptados a receita; teste novo da direção do risco
  (probabilidades de shortfall); teste do `registrarObjeto` em
  `ObraServiceTest`; teste do mapeamento de inputs de receita no loader.
- **Web (Vitest, puro):** chips dinâmicos (derivação + rótulos + fallback);
  parse defensivo de `VITE_STAVIAS_LINKS`; renames do motor local cobertos
  pelos testes existentes de `staviaLocalEngine`/`staviaPanelAnswer`
  atualizados.

## Fora de escopo

- Preservação/migração de dados históricos do PDOC (dev-only).
- Expor P50/P80 do PDOR no gráfico da Home (ciclo futuro).
- Sub-projetos Mensagens e Tarefas.
- Escopo de pull server-side e demais follow-ups do ledger da Home.
