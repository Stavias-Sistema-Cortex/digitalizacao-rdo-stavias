CREATE TABLE cortex_release_marker (
    revision VARCHAR(40) PRIMARY KEY,
    marker VARCHAR(43) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_cortex_release_marker_revision
        CHECK (revision ~ '^[0-9a-f]{40}$'),
    CONSTRAINT chk_cortex_release_marker_value
        CHECK (marker ~ '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$'),
    CONSTRAINT uq_cortex_release_marker_value
        UNIQUE (marker)
);

REVOKE ALL ON TABLE cortex_release_marker FROM PUBLIC;

ALTER TABLE cortex_release_marker ENABLE ROW LEVEL SECURITY;

CREATE POLICY cortex_release_marker_select
    ON cortex_release_marker
    FOR SELECT
    USING (TRUE);

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'cortex_runtime'
    ) THEN
        REVOKE ALL ON TABLE cortex_release_marker FROM cortex_runtime;
        GRANT SELECT ON TABLE cortex_release_marker TO cortex_runtime;
    END IF;
END
$migration$;
