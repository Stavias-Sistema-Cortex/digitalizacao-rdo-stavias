-- Canonical, race-safe receipts for equivalent RDO creation contexts.
--
-- Existing rows deliberately remain non-canonical (canonical_key IS NULL).
-- They may already be referenced by historical RDO creation provenance, and
-- deleting or rewriting them would invalidate those durable receipts. Every
-- context generated after V55 receives a deterministic key and is protected
-- by the partial unique index below.

ALTER TABLE rdo_creation_context_snapshot
    ADD COLUMN canonical_key varchar(64);

ALTER TABLE rdo_creation_context_snapshot
    ADD CONSTRAINT chk_rdo_context_canonical_key
        CHECK (
            canonical_key IS NULL
            OR canonical_key ~ '^[0-9a-f]{64}$'
        );

CREATE UNIQUE INDEX uq_rdo_context_snapshot_canonical_key
    ON rdo_creation_context_snapshot (canonical_key)
    WHERE canonical_key IS NOT NULL;
