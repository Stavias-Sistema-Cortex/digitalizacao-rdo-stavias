CREATE TABLE asset (
    id                  CHAR(36)        NOT NULL,
    source_database     VARCHAR(64)     NOT NULL,
    source_table        VARCHAR(64)     NOT NULL,
    source_pk           VARCHAR(128)    NOT NULL,
    external_code       VARCHAR(120),
    name                VARCHAR(255)    NOT NULL,
    category            VARCHAR(120),
    active              TINYINT(1)      NOT NULL DEFAULT 1,
    source_updated_at   DATETIME(6),
    source_hash         CHAR(64),
    first_imported_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                         ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at          DATETIME(6),
    row_version         BIGINT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT uq_asset_source_record
        UNIQUE (source_database, source_table, source_pk)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_asset_external_code
    ON asset (external_code);

CREATE INDEX idx_asset_name
    ON asset (name);

CREATE INDEX idx_asset_updated_cursor
    ON asset (updated_at, id);

CREATE TABLE asset_alias (
    id                  CHAR(36)        NOT NULL,
    asset_id            CHAR(36)        NOT NULL,
    alias_system        VARCHAR(80)     NOT NULL,
    alias_code          VARCHAR(120)    NOT NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_asset_alias_asset
        FOREIGN KEY (asset_id)
        REFERENCES asset(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_asset_alias
        UNIQUE (alias_system, alias_code)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE TABLE source_sync_checkpoint (
    id                      CHAR(36)        NOT NULL,
    connector_name          VARCHAR(80)     NOT NULL,
    source_database         VARCHAR(64)     NOT NULL,
    source_table            VARCHAR(64)     NOT NULL,
    cursor_updated_at       DATETIME(6),
    cursor_pk               VARCHAR(128),
    last_full_scan_at       DATETIME(6),
    last_success_at         DATETIME(6),
    last_error_at           DATETIME(6),
    last_error_message      VARCHAR(1000),

    PRIMARY KEY (id),
    CONSTRAINT uq_source_sync_checkpoint
        UNIQUE (connector_name, source_database, source_table)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE TABLE source_sync_run (
    id                      CHAR(36)        NOT NULL,
    connector_name          VARCHAR(80)     NOT NULL,
    source_database         VARCHAR(64)     NOT NULL,
    source_table            VARCHAR(64)     NOT NULL,
    started_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at             DATETIME(6),
    status                  VARCHAR(20)     NOT NULL,
    records_read            INT             NOT NULL DEFAULT 0,
    records_inserted        INT             NOT NULL DEFAULT 0,
    records_updated         INT             NOT NULL DEFAULT 0,
    records_deactivated     INT             NOT NULL DEFAULT 0,
    error_message           VARCHAR(1000),

    PRIMARY KEY (id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_source_sync_run_started_at
    ON source_sync_run (started_at);

CREATE INDEX idx_source_sync_run_connector
    ON source_sync_run (connector_name, started_at);
