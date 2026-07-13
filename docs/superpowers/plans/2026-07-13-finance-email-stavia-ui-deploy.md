# Finance, E-mail, StavIA, UI, and Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: implement task-by-task with `superpowers:subagent-driven-development`; every task starts with a focused failing test and ends with fresh verification and a commit.

**Goal:** Deliver a real-data financial workspace—purchases, invoices, payments/collections, cost centers, reports and traceable e-mail charges—fully authorized, offline-capable through the existing sync engine, native to the Córtex ontology/StavIA, responsive, and production-configurable.

**Architecture:** New normalized finance aggregates live beside, not inferred from, legacy RDO free text. Money uses fixed decimal values and explicit currency; status transitions and approval rules are persisted/configurable. Every controller/service/sync event/knowledge source calls `FinancialAccessService`. Attachments reuse shared `ObjectStorage`; offline writes reuse `SyncMutationHandler` and owner-partitioned outbox; all mail reuses the auth plan's `EmailGateway`. `FinanceOntologyProjector` is the single projection path into canonical `cortex_*` operational memory; StavIA reads typed, scope-filtered knowledge rather than raw global ontology history.

**Migrations:** only `V31__finance_core.sql`, `V32__finance_invoices_and_payments.sql`, and `V33__email_charges_and_ontology_extensions.sql`; never edit V1-V30.

**UI direction:** preserve the existing shell/tokens and attached reference hierarchy without copying assets. Use calm off-white surfaces, navy text, restrained orange actions/status, dense but legible tables, useful Portuguese empty states, and responsive table/card/kanban adaptations. No fake charts, totals, suppliers or transactions.

## Task 1: V31 finance core and configurable approvals

**Files:**
- Create `apps/api/src/main/resources/db/migration/V31__finance_core.sql`
- Create migration contract/MySQL integration tests.

**RED:** assert normalized `finance_fornecedor`, `finance_centro_custo`, `finance_categoria`, `finance_solicitacao_compra`, `finance_compra`, line items, status history, approval rule/step/decision tables; exact DECIMAL/currency fields; worksite/responsible/audit/version/soft-delete columns; no production seeds and no inference from `rdo_material.fornecedor/nota_fiscal`.

**GREEN:** indexes for worksite/date/status/supplier/responsible/category/cost center; configurable approval thresholds scoped by worksite/cost center and effective dates; idempotent clientMutationId; constraints preserving audit. Apply V1-V31 from scratch with JDK21 disposable MySQL.

**Commit:** `feat(financeiro): add purchase and approval schema`

## Task 2: Suppliers, cost centers, purchases, and approval API

**Files:**
- Create packages `financeiro/fornecedor`, `centrocusto`, `compras`, `aprovacao`
- Create entities/repositories/services/controllers/DTOs and tests
- Register finance sync handlers and ontology projector.

**RED:** validation of CNPJ/email/money/date/worksite; ALFA full access; BETA requires active worksite plus the exact V28 capabilities (`FINANCEIRO_VISUALIZAR`, `FINANCEIRO_OPERAR`, `FINANCEIRO_APROVAR`, `FINANCEIRO_ADMINISTRAR`); configurable rule selects persisted approval steps; invalid transition/over-approval denied; filters and pagination compose correctly; soft delete/history/audit; duplicate offline mutation is idempotent.

**GREEN:** transactional aggregate services; persisted status machine configurable through allowed-transition data/config rather than hardcoded value thresholds; filters by worksite/period/responsible/supplier/status/category/cost center/priority; table and kanban projection endpoints from the same query; every change projected with actor/device/correlation/before/after/result.

**Endpoints:** `/api/financeiro/fornecedores`, `/centros-custo`, `/categorias`, `/regras-aprovacao`, `/solicitacoes`, `/compras`, `/compras/{id}/decisoes`.

**Commit:** `feat(financeiro): implement purchase workflow`

## Task 3: V32 invoices, documents, ledger entries, and allocations

**Files:**
- Create `apps/api/src/main/resources/db/migration/V32__finance_invoices_and_payments.sql`
- Create migration contract/MySQL tests.

**RED:** assert `finance_nota_fiscal`, document relation to `stored_object`, invoice history, `finance_lancamento`, payment/allocation/settlement and budget link tables; supplier/worksite/purchase/cost-center/responsible/status/dates; immutable history/soft archive; idempotency; no binary/base64/OCR result simulation.

**GREEN:** unique invoice identity scoped to supplier/number/series; DECIMAL/currency; partial payments and multiple cost allocations sum constraints enforced transactionally; attachment references shared storage; future OCR state remains `NAO_CONFIGURADO` unless a real provider is configured.

**Commit:** `feat(financeiro): add invoices payments and documents schema`

## Task 4: Invoice, payment/collection, document, and reporting API

**Files:**
- Create packages `financeiro/notafiscal`, `lancamento`, `relatorio`
- Reuse `ObjectStorage`, finance access, sync handlers, ontology projector
- Add unit/MockMvc/MySQL/query tests.

**RED:** CRUD/archive/search invoices; filters by NF number/supplier/CNPJ/worksite/period/responsible/status; authorized document view; payment/collection create/edit/settle/cancel with immutable history; due/overdue calculation from real dates; report totals exactly match transactional rows and filters; budget comparison only appears when real budget linkage exists; export uses same scoped query; BETA cross-scope and capability leaks fail.

**GREEN:** paginated endpoints and aggregation queries for forecast/committed/paid/open/overdue, costs by worksite/supplier/category/responsible/cost center, alerts for due/invoice pending/unassigned purchases; CSV export streams scoped real rows; no synthetic zero series beyond explicit empty response metadata.

**Endpoints:** `/api/financeiro/notas-fiscais`, `/notas-fiscais/{id}/anexos`, `/lancamentos`, `/visao-geral`, `/relatorios`, `/relatorios/exportacoes`.

**Commit:** `feat(financeiro): implement invoices payments and reports`

## Task 5: V33 idempotent e-mail charge model

**Files:**
- Create `apps/api/src/main/resources/db/migration/V33__email_charges_and_ontology_extensions.sql`
- Create migration contract/MySQL tests.

**RED:** assert configurable template/rule/charge/attempt/delivery-event tables; manual/scheduled/automatic mode; preview/activation status; authenticated sender reference, allowed reply-to, recipient, scheduling/due linkage, idempotency key, attempt/provider message ID/error category; no credentials or message body secrets; ontology relation/catalog additions are additive.

**GREEN:** indexes/uniques prevent duplicate send per charge+rule+occurrence; persisted rule effective window and preview approval; immutable attempts; provider delivery status optional.

**Commit:** `feat(financeiro): add traceable charge email schema`

## Task 6: Charge preview, scheduling, and EmailGateway delivery

**Files:**
- Create `financeiro/cobranca` domain/API/scheduler/template renderer
- Reuse `com.projeto.cortex.email.EmailGateway`
- Modify provider configuration only where shared contract needs delivery metadata
- Add service/scheduler/provider/authorization/idempotency tests.

**RED:** preview interpolates allowlisted real fields and rejects unknown/missing variables; automatic rule cannot activate before explicit preview confirmation; From is configured authenticated Stavias mailbox, never request input; reply-to only from authorized allowlist; duplicate scheduler/retry/reconnect sends once; fake provider captures without network; failures persist sanitized reason and retry schedule; every attempt emits ontology event.

**GREEN:** claim due sends with transactional/skip-locked idempotency; manual/scheduled/automatic flows; configurable templates/rules by worksite/due offset; exponential bounded retry; SMTP provider and Graph-ready adapter interface boundary; delivery webhook capability optional and authenticated when implemented, never fabricated.

**Endpoints:** `/api/financeiro/cobrancas`, `/cobrancas/{id}/previsualizacao`, `/cobrancas/{id}/agendar`, `/cobrancas/{id}/enviar`, `/regras-cobranca`, `/modelos-email`.

**Commit:** `feat(financeiro): send idempotent provider charges`

## Task 7: Canonical ontology projection and permissioned StavIA finance/messages knowledge

**Files:**
- Create finance/messaging semantic catalog constants and typed knowledge readers/sources
- Modify `CortexOperationalMemoryService`, `StaviaKnowledgeSourceRegistry`, planner/intent/catalog/access policy/prompt evidence
- Add golden, wiring, grounding and cross-worksite authorization tests.

**RED:** actual questions resolve from persisted entities/events: overdue invoices by worksite, purchase creator/history, purchased total this month, pending local/sync message documents where server has evidence, suppliers with pending charges; ALFA can query all; BETA requires worksite plus appropriate finance/message capability; no raw JSON/global ontology history; no answer without verifiable source IDs; no ad-hoc dual write to legacy `ontology_*` tables.

**GREEN:** one projector emits canonical object/attribute/relation/evidence/event data with actor/time/origin/device/correlation/previous/new/result and related IDs; typed JDBC readers scope before selecting; StavIA evidence includes traceable entity IDs and data freshness; unavailable/offline-local-only facts are reported as unavailable rather than invented.

**Commit:** `feat(stavia): ground finance and messaging answers`

## Task 8: Financeiro web data layer and complete responsive UI

**Files:**
- Create `apps/web/src/features/financeiro/**` pages, API/repositories/hooks/components/styles/tests
- Modify `App.tsx`, `CortexShell.tsx`, Home financial hydration/cards, sync entity types/handlers
- Reuse shared attachment staging and offline outbox.

**RED:** tests for `Visão geral`, `Compras`, `Notas fiscais`, `Pagamentos e cobranças`, `Centro de custos`, `Relatórios`; all values/filter options from API/IndexedDB; filter changes alter actual query/result; create/edit/archive/audit workflows; purchase table/kanban; invoice document preview; payment/charge preview; capability-aware navigation/action states; Portuguese validation/empty/loading/error/sync labels; offline optimistic create and retry; keyboard/focus/responsive behavior.

**GREEN:** lazy-loaded `/financeiro`; URL-backed filters; shared query model for cards/table/chart/export; render charts only with returned series and an honest empty state otherwise; desktop tables and side panels, tablet condensed grid, mobile cards/drill-in; no third-party copied assets, arbitrary glow, mock metrics or hardcoded business rows; fix the two pre-existing `ObrasPage` lint errors and split major routes to eliminate eager main-bundle growth.

**Commit:** `feat(web): deliver real data financeiro workspace`

## Task 9: Configuration, Docker, operations, and full deployment gate

**Files:**
- Update `.env.example`, API/web Dockerfiles as needed, compose local/production examples, health/readiness, runbooks, authorization architecture, deployment checklist and smoke scripts.

**Production-required configuration:** database; auth HMAC/OTP/session/provisioning secret files; exact HTTPS origins/cookies/WebAuthn RP; SMTP authenticated Stavias From mailbox/password file (or future Graph adapter); reply-to allowlist; persistent local volume or S3 bucket/region/endpoint/credentials; upload limits/types; charge scheduler/retry; no dev-admin/import/fake provider.

**Verification (sequential where local process limits require):**

1. JDK21 full Maven suite and fresh MySQL V1-V33 from scratch plus representative upgrade.
2. Web Vitest, lint and production build; route chunks and no new bundle warning caused by these modules.
3. Docker builds, compose health/readiness, Flyway, local fake mail, persistent attachment restart, API smoke.
4. Browser desktop/tablet/mobile: OTP/session/passkey states, ALFA full access, BETA scoped denial, offline message+attachment reconnect, purchase/invoice CRUD/filter/audit, cards/reports react to real data, fake charge exactly once, StavIA grounded answers.
5. Security scans: no real CPF/e-mail/secret/token, no JWT/Bloom/raw CPF storage, no base64/blob in MySQL, no wildcard credentialed CORS, no public storage key, no fake/demo provider in production profile.

Actual SMTP/S3/Microsoft-provider delivery may remain externally unverified without credentials; readiness must fail closed and the handoff must state this precisely rather than claim provider proof.

**Commit:** `docs(deploy): add finance messaging production runbook`

## Completion Gate

- Existing explicit ALFA retains full access; BETA requires exact server-side scope/capability everywhere.
- Purchases, invoices, payments, centers, reports and charges operate on persisted real data and preserve immutable audit.
- All offline finance/message mutations share the same idempotent outbox and storage dependencies.
- E-mail cannot spoof From or duplicate delivery and fake provider never sends externally.
- StavIA answers only from typed, permissioned, traceable evidence.
- UI is responsive, Portuguese, brand-consistent and honest in empty/unavailable states.
- Fresh tests, migration, build, Docker, security and browser evidence are recorded; external credential dependencies remain explicit.
