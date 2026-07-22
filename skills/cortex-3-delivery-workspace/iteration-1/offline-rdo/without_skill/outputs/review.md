# Offline RDO continuation review

## Verdict

`Novo RDO` and sync are **not ready for acceptance**. The supplied evidence
only establishes navigation to the creation route. The implementation shown
creates a production RDO with fabricated worksite, identifier, and worker
data, while the claimed sync has no demonstrated durable offline queue,
reconciliation behavior, or automatic recovery.

## Findings

### P0 — Creating an RDO writes fabricated production data

`handleCreate` assigns `obra-demo`, `RDO-001`, and two named workers directly
in production code. Both worker references have `colaboradorId: null`.
Consequently, a user can create an RDO whose worksite, sequential identifier,
and labour records have no demonstrated connection to real domain entities or
user input. This is a data-integrity failure, not a presentational placeholder.

### P0 — Offline creation is not proven durable

The fixture supplies no IndexedDB transaction test or equivalent persistence
evidence. `saveRdo(draft)` alone does not establish that the RDO and the
associated pending mutation survive a reload, application restart, or loss of
network. The current claim cannot support offline field use.

### P0 — Sync can falsely report success and has no reliable retry path

`syncNow` pushes `memoryQueue` and unconditionally marks the state `SYNCED`
after the awaited call. The evidence does not show per-operation acknowledgement,
atomic removal of acknowledged items, error/conflict handling, retained
failures, idempotency, or a reconnect-triggered retry. A manual-only action is
also insufficient for a resilient offline workflow. The application could
present a synchronized state without proving that every locally created RDO was
accepted and persisted remotely.

### P1 — The RDO lacks previous-record provenance

No previous-RDO query or provenance is supplied. There is therefore no
evidence that a newly opened RDO is linked to the correct preceding RDO for
the selected worksite, derives valid continuity data, or represents a clean
start when none exists.

### P1 — The supplied test verifies routing, not creation

The only test checks that clicking `Novo RDO` routes to `/rdos/novo`; the
fixture implementation navigates to `/rdos/${draft.id}` after saving. Neither
test nor evidence confirms that the creation form saves a valid draft, that
the resulting detail route is valid, or that an offline-created RDO can later
be synchronized. The route evidence is internally disconnected from the
claimed behavior.

### P2 — Manual sync is the only known trigger

The fixture explicitly says synchronization occurs only after the user presses
`Sincronizar`. At minimum, connectivity restoration and startup must be
considered. Without retry scheduling or a clear pending state, unsynced records
are easy to leave indefinitely on a device.

## Functional acceptance criteria

The continuation is acceptable only when all of the following are demonstrated:

1. Selecting `Novo RDO` opens the creation flow and persists a new RDO only
   after required real inputs are provided. The worksite and workers must come
   from persisted/selectable domain records; no production defaults such as
   `obra-demo`, `RDO-001`, names, or null foreign keys may be written.
2. The RDO number is allocated or validated by the authoritative domain rule,
   including concurrent/offline behavior, rather than hardcoded in the client.
3. The new RDO and its pending sync mutation are committed durably in one
   local transaction. A browser-level test proves they remain after reload
   while offline and are visible in the local RDO list/detail view.
4. The flow determines the prior RDO for the selected worksite and records
   verifiable provenance (or explicitly records that no predecessor exists).
   A test covers both cases.
5. Synchronization processes durable pending mutations and changes a record to
   synced only after the server acknowledges that specific operation. Failed or
   conflicted operations remain visible and retryable; acknowledged operations
   are not resent as duplicate records.
6. A browser-level offline → create → reload → reconnect scenario proves that
   the same RDO reaches the server exactly once and that local and remote
   identity/status converge.
7. Sync resumes automatically on relevant lifecycle/connectivity events (and
   remains manually invokable). The UI distinguishes pending, syncing, synced,
   failed, and conflict states without treating a failed request as synced.

