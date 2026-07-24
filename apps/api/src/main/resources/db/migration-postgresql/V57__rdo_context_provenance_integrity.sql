-- Durable RDO creation provenance and actor-scoped receipt admission.
--
-- V48 allowed creation_context_version before the snapshot table existed, so
-- a deployed database may contain historical dangling values. The NOT VALID
-- foreign key preserves those rows while enforcing every new INSERT/UPDATE.
-- Clean databases are validated immediately.

ALTER TABLE rdo_creation_context_snapshot
    ADD COLUMN issued_by_id varchar(120);

CREATE INDEX idx_rdo_context_snapshot_actor_capacity
    ON rdo_creation_context_snapshot (
        obra_id,
        issued_by_id,
        generated_at,
        receipt_version
    )
    WHERE canonical_key IS NOT NULL
      AND issued_by_id IS NOT NULL;

CREATE INDEX idx_rdo_creation_context_version
    ON rdo (creation_context_version)
    WHERE creation_context_version IS NOT NULL;

ALTER TABLE rdo
    ADD CONSTRAINT fk_rdo_creation_context_receipt
        FOREIGN KEY (creation_context_version)
        REFERENCES rdo_creation_context_snapshot (receipt_version)
        ON DELETE RESTRICT
        NOT VALID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM rdo
        LEFT JOIN rdo_creation_context_snapshot snapshot
          ON snapshot.receipt_version = rdo.creation_context_version
        WHERE rdo.creation_context_version IS NOT NULL
          AND snapshot.receipt_version IS NULL
    ) THEN
        ALTER TABLE rdo
            VALIDATE CONSTRAINT fk_rdo_creation_context_receipt;
    END IF;
END
$$;
