CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE catalogo_servico (
    id varchar(36) PRIMARY KEY,
    codigo varchar(80) NOT NULL,
    nome varchar(160) NOT NULL,
    descricao varchar(500),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    obra_autorizadora_id varchar(36) NOT NULL REFERENCES obra(id) ON DELETE RESTRICT,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id) ON DELETE RESTRICT,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_catalogo_servico_codigo
        CHECK (codigo ~ '^[A-Z0-9][A-Z0-9._/-]{0,79}$'),
    CONSTRAINT chk_catalogo_servico_status CHECK (status = 'ACTIVE')
);

CREATE UNIQUE INDEX uq_catalogo_servico_codigo_normalizado
    ON catalogo_servico (lower(codigo));
CREATE INDEX idx_catalogo_servico_codigo_busca
    ON catalogo_servico USING gin (lower(codigo) gin_trgm_ops);
CREATE INDEX idx_catalogo_servico_nome_busca
    ON catalogo_servico USING gin (lower(nome) gin_trgm_ops);

CREATE TABLE service_catalog_mutation (
    id varchar(36) PRIMARY KEY,
    ator_id varchar(36) NOT NULL REFERENCES colaborador(id) ON DELETE RESTRICT,
    client_mutation_id varchar(120) NOT NULL,
    operation_type varchar(60) NOT NULL,
    entity_id varchar(36) NOT NULL,
    request_hash char(64) NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_catalog_mutation UNIQUE (ator_id, client_mutation_id),
    CONSTRAINT chk_service_catalog_mutation_id
        CHECK (client_mutation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CONSTRAINT chk_service_catalog_mutation_operation CHECK (operation_type IN (
        'SERVICE_CREATED',
        'SERVICE_PRICE_VERSION_CREATED',
        'SERVICE_PRICE_VERSION_SUPERSEDED',
        'SERVICE_PRICE_VERSION_CANCELLED'
    )),
    CONSTRAINT chk_service_catalog_mutation_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_service_catalog_mutation_entity
    ON service_catalog_mutation (operation_type, entity_id);

CREATE TABLE service_price_version (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL REFERENCES obra(id) ON DELETE RESTRICT,
    service_id varchar(36) NOT NULL REFERENCES catalogo_servico(id) ON DELETE RESTRICT,
    unidade varchar(30) NOT NULL,
    moeda char(3) NOT NULL,
    versao integer NOT NULL,
    valor_unitario numeric(18,4) NOT NULL,
    vigencia_inicio date NOT NULL,
    vigencia_fim date,
    fonte varchar(80) NOT NULL,
    supersedes_id varchar(36),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id) ON DELETE RESTRICT,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_price_version_scope_version
        UNIQUE (obra_id, service_id, unidade, moeda, versao),
    CONSTRAINT uq_service_price_version_id_worksite UNIQUE (id, obra_id),
    CONSTRAINT uq_service_price_version_supersedes UNIQUE (supersedes_id),
    CONSTRAINT fk_service_price_version_supersedes
        FOREIGN KEY (supersedes_id) REFERENCES service_price_version(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_service_price_version_unit
        CHECK (unidade ~ '^[A-Z0-9][A-Z0-9._/-]{0,29}$'),
    CONSTRAINT chk_service_price_version_currency CHECK (moeda ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_service_price_version_number CHECK (versao > 0),
    CONSTRAINT chk_service_price_version_value CHECK (valor_unitario >= 0),
    CONSTRAINT chk_service_price_version_validity
        CHECK (vigencia_fim IS NULL OR vigencia_fim >= vigencia_inicio),
    CONSTRAINT chk_service_price_version_source
        CHECK (fonte ~ '^[A-Z0-9][A-Z0-9._:-]{0,79}$'),
    CONSTRAINT chk_service_price_version_not_self
        CHECK (supersedes_id IS NULL OR supersedes_id <> id)
);

CREATE INDEX idx_service_price_version_scope
    ON service_price_version (
        obra_id, service_id, unidade, moeda, vigencia_inicio, versao
    );
CREATE INDEX idx_service_price_version_service
    ON service_price_version (service_id, obra_id, criado_em, id);

CREATE TABLE service_price_version_cancellation (
    id varchar(36) PRIMARY KEY,
    price_version_id varchar(36) NOT NULL,
    obra_id varchar(36) NOT NULL,
    vigencia_cancelamento date NOT NULL,
    motivo varchar(500) NOT NULL,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id) ON DELETE RESTRICT,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_price_version_cancellation UNIQUE (price_version_id),
    CONSTRAINT fk_service_price_version_cancellation_price
        FOREIGN KEY (price_version_id, obra_id)
        REFERENCES service_price_version(id, obra_id) ON DELETE RESTRICT
);

CREATE INDEX idx_service_price_version_cancellation_worksite
    ON service_price_version_cancellation (obra_id, criado_em, id);

CREATE OR REPLACE FUNCTION cortex_price_effective_valid_to(price_id varchar)
RETURNS date
LANGUAGE sql
STABLE
AS $$
    SELECT CASE
        WHEN price.vigencia_fim IS NULL
             AND successor.vigencia_inicio IS NULL
             AND cancellation.vigencia_cancelamento IS NULL
            THEN NULL
        ELSE LEAST(
            COALESCE(price.vigencia_fim, 'infinity'::date),
            COALESCE(successor.vigencia_inicio - 1, 'infinity'::date),
            COALESCE(cancellation.vigencia_cancelamento - 1, 'infinity'::date)
        )
    END
    FROM service_price_version price
    LEFT JOIN service_price_version successor
      ON successor.supersedes_id = price.id
    LEFT JOIN service_price_version_cancellation cancellation
      ON cancellation.price_version_id = price.id
    WHERE price.id = price_id
$$;

CREATE OR REPLACE FUNCTION cortex_validate_service_price_version_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    predecessor service_price_version%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.obra_id || ':' || NEW.service_id || ':' || NEW.unidade || ':' || NEW.moeda,
        0
    ));

    IF NEW.supersedes_id IS NOT NULL THEN
        SELECT * INTO predecessor
        FROM service_price_version
        WHERE id = NEW.supersedes_id;

        IF NOT FOUND
           OR predecessor.obra_id <> NEW.obra_id
           OR predecessor.service_id <> NEW.service_id
           OR predecessor.unidade <> NEW.unidade
           OR predecessor.moeda <> NEW.moeda
           OR NEW.vigencia_inicio <= predecessor.vigencia_inicio THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SERVICE_PRICE_SUPERSESSION_INVALID';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM service_price_version_cancellation cancellation
            WHERE cancellation.price_version_id = predecessor.id
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SERVICE_PRICE_ALREADY_TERMINATED';
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM service_price_version existing
        WHERE existing.obra_id = NEW.obra_id
          AND existing.service_id = NEW.service_id
          AND existing.unidade = NEW.unidade
          AND existing.moeda = NEW.moeda
          AND existing.id <> COALESCE(NEW.supersedes_id, '')
          AND daterange(
                existing.vigencia_inicio,
                COALESCE(
                    cortex_price_effective_valid_to(existing.id) + 1,
                    'infinity'::date
                ),
                '[)'
              ) && daterange(
                NEW.vigencia_inicio,
                COALESCE(NEW.vigencia_fim + 1, 'infinity'::date),
                '[)'
              )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_VALIDITY_OVERLAP';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_service_price_version_validate_insert
BEFORE INSERT ON service_price_version
FOR EACH ROW EXECUTE FUNCTION cortex_validate_service_price_version_insert();

CREATE OR REPLACE FUNCTION cortex_validate_service_price_cancellation_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    price service_price_version%ROWTYPE;
BEGIN
    SELECT * INTO price
    FROM service_price_version
    WHERE id = NEW.price_version_id
      AND obra_id = NEW.obra_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_VERSION_NOT_FOUND';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        price.obra_id || ':' || price.service_id || ':' || price.unidade || ':' || price.moeda,
        0
    ));

    IF NEW.vigencia_cancelamento < price.vigencia_inicio
       OR (price.vigencia_fim IS NOT NULL
           AND NEW.vigencia_cancelamento > price.vigencia_fim)
       OR EXISTS (
           SELECT 1 FROM service_price_version successor
           WHERE successor.supersedes_id = price.id
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_CANCELLATION_INVALID';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_service_price_cancellation_validate_insert
BEFORE INSERT ON service_price_version_cancellation
FOR EACH ROW EXECUTE FUNCTION cortex_validate_service_price_cancellation_insert();

CREATE OR REPLACE FUNCTION cortex_reject_service_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = 'P0001',
        MESSAGE = TG_TABLE_NAME || '_IMMUTABLE';
END;
$$;

CREATE TRIGGER trg_catalogo_servico_immutable
BEFORE UPDATE OR DELETE ON catalogo_servico
FOR EACH ROW EXECUTE FUNCTION cortex_reject_service_catalog_mutation();

CREATE TRIGGER trg_service_catalog_mutation_immutable
BEFORE UPDATE OR DELETE ON service_catalog_mutation
FOR EACH ROW EXECUTE FUNCTION cortex_reject_service_catalog_mutation();

CREATE TRIGGER trg_service_price_version_immutable
BEFORE UPDATE OR DELETE ON service_price_version
FOR EACH ROW EXECUTE FUNCTION cortex_reject_service_catalog_mutation();

CREATE TRIGGER trg_service_price_cancellation_immutable
BEFORE UPDATE OR DELETE ON service_price_version_cancellation
FOR EACH ROW EXECUTE FUNCTION cortex_reject_service_catalog_mutation();
