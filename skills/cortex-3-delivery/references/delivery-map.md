# Cortex 3 Delivery Map

Read the approved design first, then the one plan owning the current task.

| Slice | Owning plan | Primary runtime areas |
|---|---|---|
| Program sequencing | `docs/superpowers/plans/2026-07-21-cortex-3-0-program.md` | all |
| Runtime foundation | `docs/superpowers/plans/2026-07-21-cortex-3-0-runtime-foundation.md` | `apps/api/.../ontology`, `apps/api/.../intelligence/stavia`, `apps/web/.../stavia`, PostgreSQL profiles |
| Offline ontology/Memory | `docs/superpowers/plans/2026-07-21-cortex-3-0-offline-memory.md` | `apps/web/src/lib/sync`, IndexedDB, `apps/api/.../sync`, `apps/api/.../ontology`, Home/Memory |
| RDO workflow/export | `docs/superpowers/plans/2026-07-21-cortex-3-0-rdo.md` | `apps/api/.../rdos`, `apps/web/src/features/rdos`, POI/XLSX template |
| Revenue/PDOR | `docs/superpowers/plans/2026-07-21-cortex-3-0-revenue-pdor.md` | `financeiro`, `pdor`, RDO service executions, graph revenue evidence |
| Institutional UI | `docs/superpowers/plans/2026-07-21-cortex-3-0-ui.md` | shared workspace shell and authenticated tabs |
| Security/completion | `docs/superpowers/plans/2026-07-21-cortex-3-0-security-validation.md` | cross-cutting authorization, secrets, resource limits, runtime/browser proof |

## Reference branches

- `feat/cortex-2-1-memory-ui`: inspect individual Memory/offline/revenue capabilities; never merge wholesale over current `develop`.
- `feat/financeiro-producao-receita`: inspect revenue trace files and tests; adapt them to immutable Cortex 3 price evidence.
- `feat/cortex-2-1-rdo-ui`: visual/reference material only unless the owning task names a concrete file.

Record any ported source commit in the implementer report. Current tests and PostgreSQL behavior remain the authority.

