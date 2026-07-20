# Financeiro: Rastreio Ontológico de Receita Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o Financeiro legado por uma superfície de produção, receita e evidências ontológicas entre obras.

**Architecture:** Um endpoint consolidado aplica autorização por obra e retorna agregados por obra e por tipo de serviço, preservando unidade e referências de RDO. Um endpoint de evidências compõe a timeline ontológica e os registros canônicos de produção. O frontend usa uma página única, com drill-down por obra/serviço e painel lateral de origem.

**Tech Stack:** Spring Boot/JdbcTemplate/JUnit 5; React/TypeScript/Vitest; CSS atual do Financeiro.

## Global Constraints

- Não exibir compras, notas, pagamentos, cobranças, rateios ou centros de custo.
- Não inventar receita para serviço sem preço contratual ou fonte canônica.
- Diferenciar estimada, medida, aprovada, faturada, recebida e projeção PDOR.
- Não agrupar quantidades de unidades diferentes.
- Exigir `FINANCEIRO_VISUALIZAR` para cada obra devolvida.

---

### Task 1: Consolidado canônico por obra e tipo de serviço

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioReceitaController.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioReceitaServiceTest.java`

**Interfaces:** `GET /api/financeiro/rastreio-receita?de=&ate=&obraId=` returns `consolidado`, `obras[]`, `tiposServico[]`; each type row includes `nome`, `unidade`, `obras[]`, production/cost/revenue/margin, and distinct `rdoIds`.

- [ ] Write a failing JDBC-backed test containing Fresagem in two obras, Recape in one obra, and Fresagem with a second unit; assert the service returns three service/unit rows, never merges units, and only sums authorized works.
- [ ] Run `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RastreioReceitaServiceTest test`; expect the missing service failure.
- [ ] Implement aggregation over `execucao_servico_rdo` using `obra_id`, `servico_nome`, `unidade_medida`, `COUNT(DISTINCT rdo_id)`, optional `data_execucao` bounds, canonical revenue-state sums, and a per-work authorization filter.
- [ ] Add tests for missing contractual revenue (`null`, not zero) and inverted dates (400 domain error); rerun the same command and expect PASS.
- [ ] Commit: `feat(financeiro): consolidate production revenue by service`.

### Task 2: Evidências ontológicas de uma métrica

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioEvidenciaService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/RastreioEvidenciaController.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/RastreioEvidenciaControllerTest.java`

**Interfaces:** `GET /api/financeiro/rastreio-receita/evidencias?obraId=&servico=&unidade=&de=&ate=` returns RDO identity/date, item contratual, values, revenue state, PDOR snapshot reference, and `OperationalTimelineEventResponse` rows.

- [ ] Write a failing controller test asserting `FINANCEIRO_VISUALIZAR` happens before evidence lookup and that a service/unit query yields only its RDOs and ontology events.
- [ ] Run `cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RastreioEvidenciaControllerTest test`; expect failure.
- [ ] Implement the read-only service by joining canonical RDO execution/item-contractual data and reusing `OperationalTimelineService.timeline(...)`; include explicit source and observed date for every evidence item.
- [ ] Re-run the controller test and commit `feat(financeiro): expose revenue trace evidence`.

### Task 3: Substituir a navegação financeira legada

**Files:**
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.tsx`
- Modify: `apps/web/src/features/financeiro/useFinanceiroData.ts`
- Modify: `apps/web/src/features/financeiro/financeiroApi.ts`
- Modify: `apps/web/src/features/financeiro/financeiro.types.ts`
- Create: `apps/web/src/features/financeiro/FinanceiroRastreioPage.tsx`
- Test: `apps/web/src/features/financeiro/financeiroApi.test.ts`

- [ ] Add failing Vitest expectations that `buscarRastreioReceita` sends only period/optional worksite and `buscarEvidenciasRastreio` sends the selected service/unit without ledger filters.
- [ ] Run `cd apps/web && npm test -- financeiroApi.test.ts`; expect failure.
- [ ] Implement `FinanceRevenueTrace`, `FinanceServiceTrace`, and `FinanceEvidence` types plus both API functions. Replace the legacy section navigation with only period filters, worksite drill-down, and `FinanceiroRastreioPage`.
- [ ] Assert legacy labels (`Compras`, `Notas fiscais`, `Pagamentos`, `Rateios`, `Centros de custo`) do not render; rerun tests and commit `feat(financeiro): replace legacy workspace with revenue trace`.

### Task 4: Painel de evidências e visualização

**Files:**
- Create: `apps/web/src/features/financeiro/FinanceEvidenceDrawer.tsx`
- Create: `apps/web/src/features/financeiro/FinanceEvidenceDrawer.test.tsx`
- Modify: `apps/web/src/features/financeiro/FinanceiroPage.css`

- [ ] Write a failing render test: opening a service row renders source RDO/date, item contratual, revenue state, PDOR reference, and timeline event; `null` revenue renders `Indisponível`.
- [ ] Run `cd apps/web && npm test -- FinanceEvidenceDrawer.test.tsx`; expect failure.
- [ ] Implement an accessible drawer, opening from consolidated worksite/service rows and loading only after selection; use no client-side recalculation.
- [ ] Run `npm test -- FinanceEvidenceDrawer.test.tsx && npm run lint && npm run build`; expect all pass. Commit `feat(financeiro): add ontology evidence drawer`.

### Task 5: Integration proof

- [ ] Run backend focused tests with JDK 21 and frontend tests/lint/build.
- [ ] Start `scripts/dev/run-api.sh` and `npm run dev:local`; log in, verify consolidated rows, worksite/service drill-down, date filtering, unavailable revenue, and evidence drawer against real RDO data.
- [ ] Commit only demonstrated fixes; do not merge or publish without user direction.
