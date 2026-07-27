# RDO frontend/worksite re-review fixes

Date: 2026-07-22

Base: `22055784a0d5dce8de85048ad19c61def514455b`

Scope: the three Important findings in `rdo-frontend-worksite-independent-rereview.md`.

## Outcome

- **I1 closed:** every `SUPERSEDED_BY` hop now requires two canonical v13 RDO
  CREATE/`CRIAR_RDO`/`SYNC_PUSH` envelopes for the same logical entity and the
  exact causal link `replacement.causationId === current.clientMutationId`.
  Missing, cyclic, wrong-entity, wrong-cause, wrong-operation and
  wrong-transport chains remain blocked and are diagnosed without mutating the
  dependent envelope.
- **I3 closed:** worksite identity is always rebound from the selected creation
  context. This includes obra/date, client, contract, road, city, state and the
  server number suggestion. Raw imported identity, including the spreadsheet
  RDO number, is persisted only as local `importEvidence`; it is deliberately
  absent from the canonical sync payload.
- **I6 closed:** before any RDO/event/outbox CREATE write, the persistence
  boundary reads the exact owner/worksite/date cache row and validates matching
  receipt, source version, coherent provenance, complete mandatory coverage,
  explicit optional-catalog coverage and canonical context identity. Complete
  stale contexts remain valid offline. Missing, forged, mismatched, partial or
  out-of-scope contexts fail with zero CREATE writes. Exact session identity
  and fingerprint are guarded through the coordinated IndexedDB transaction.
- The narrow exception for an already server-versioned legacy UPDATE remains
  unchanged.

## TDD evidence

- I1 RED: direct and repeated forged aliases released dependents before causal
  validation; the new negative tests failed before production changes.
- I3 RED: all imported identity fields and `numeroRdo` overwrote the selected
  context and no auditable raw evidence existed.
- I6 RED: forged positive receipts, absent/wrong-owner cache, incoherent
  provenance/partial coverage and revoked scope reached the old presence-only
  boundary. The stale complete offline control already passed.
- GREEN adds wrong worksite/date, receipt/source mismatch, mandatory and
  optional coverage, explicit `NOT_CONFIGURED` controls and same-profile auth
  rotation coverage.

## Final verification

- Focused matrix: `3 files / 43 tests passed`.
- Affected RDO/DB/sync matrix: `32 files / 167 tests passed`.
- Full web suite: `87 files / 455 tests passed`.
- `npm --prefix apps/web run lint`: passed.
- `npm --prefix apps/web run build`: passed, including TypeScript, Vite/PWA and
  the StavIA source/dist boundary.
- Real browser geometry: `3/3 scenarios passed` at 1440x900, 1280x720 and
  390x844; no overflow, clipping, overlap or interactive global control.
- `git diff --check 2205578`: passed.
- `npm --prefix apps/web audit --audit-level=high`: unchanged dependency
  baseline (`brace-expansion` high, `fast-uri` high, `dompurify` low). No audit
  fix or dependency-manifest change was applied.

No unresolved finding remains in this re-review scope.
