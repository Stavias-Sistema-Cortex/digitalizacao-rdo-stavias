# RDO final freshness receipt fix

Date: 2026-07-22

Base: `19937e96eb62c8f4bf63683675f8c33188485049`

Scope: the only Important finding in
`rdo-frontend-worksite-final-independent-review.md`.

## Outcome

The CREATE persistence boundary now accepts only the server-authoritative
`FRESH` receipt status emitted by `RdoContextService`. Offline staleness is
derived from the clock and the persisted `staleAfter`; the server snapshot is
not rewritten to a synthetic `STALE` status.

Both `freshness.generatedAt` and `freshness.staleAfter` must be semantically
valid UTC `Instant` strings. `provenance.generatedAt` must remain byte-identical
to the freshness timestamp, and the validity window must satisfy
`generatedAt < staleAfter`. `LOCAL_PENDING`, `PARTIAL`, arbitrary values,
persisted synthetic `STALE`, invalid ISO strings and equal/reversed windows are
rejected before any RDO, operational-event or outbox CREATE write.

The positive offline control keeps an authentic `FRESH` snapshot with
`generatedAt < staleAfter < now`, complete coverage and explicit
`NOT_CONFIGURED` optional catalogs. It creates successfully while offline.

## TDD evidence

- RED: `LOCAL_PENDING` with otherwise valid receipt/provenance/coverage created
  a push-ready canonical RDO.
- GREEN: seven invalid freshness/time variants fail with zero CREATE writes;
  the coherent clock-expired FRESH snapshot remains accepted offline.

## Final verification

- Focused matrix: `3 files / 44 tests passed`.
- Affected RDO/DB/sync matrix: `32 files / 168 tests passed`.
- Full web suite: `87 files / 456 tests passed`.
- `npm --prefix apps/web run lint`: passed.
- `npm --prefix apps/web run build`: passed, including TypeScript, Vite/PWA and
  the StavIA source/dist boundary.
- Real-browser geometry: `3/3 scenarios passed` at 1440x900, 1280x720 and
  390x844, with no overflow, clipping or overlap.
- `git diff --check 19937e9`: passed.

No unresolved finding remains in this review scope.
