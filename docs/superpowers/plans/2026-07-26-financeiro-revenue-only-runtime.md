# Financeiro Revenue-Only Runtime Plan

**Goal:** Make the Córtex 3 finance runtime expose only revenue evidence, service-price management, and PDOR. The removal must cover live HTTP routes, not only navigation.

## Retained operational surface

- RDO-backed revenue trace: `RastreioReceitaController`.
- Revenue-only operational result: `ResultadoOperacionalFinanceiroController`.
- Service and price catalog: `ServicePriceCatalogController`.
- PDOR calculation/history: `PrevisaoFinanceiraController` and required contractual-item route.
- Financial authorization/grants needed by that retained surface.

## Disabled legacy surface

The default Cortex runtime must not map purchase, supplier/cost-center/catalog,
allocation, purchased-asset, invoice, ledger/settlement, report, charge,
fiscal-document extraction, or financial-unit HTTP routes. The same legacy
operations must not remain reachable through the automatic/offline sync handler
registry or a charge scheduler. Preserve existing data and implementation code
for an explicit, non-default legacy maintenance profile only; do not delete
operational history.

## Required proof

1. A real Spring mapping test under the normal profile proves representative
   legacy cost URLs have no handler while revenue/service-price/PDOR routes
   remain mapped.
2. Existing legacy controller tests declare the explicit legacy profile where
   necessary, so their historical behavior is still tested without making it
   part of the default runtime.
3. No frontend production import reintroduces legacy cost panels or calls.
4. The full relevant API suite and packaging pass.

## Constraints

- Do not change revenue calculation, RDO evidence, offline replay, or PDOR
  inputs merely to hide the old cost surface.
- Do not remove or mutate persisted financial history.
- Do not use a client-controlled flag to enable legacy routes.
