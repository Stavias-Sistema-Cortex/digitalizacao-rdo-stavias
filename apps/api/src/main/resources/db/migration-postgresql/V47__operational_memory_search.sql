-- Searchable operational history. The generated fields deliberately index only
-- non-PII identifiers/codes plus the explicitly allowed business labels below.
-- Arbitrary payload JSON, actor names, collaborator identifiers, messages and
-- document bodies never enter either search column.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE cortex_evento_operacional
    ADD COLUMN search_text text GENERATED ALWAYS AS (
        lower(
            coalesce(id, '') || ' ' ||
            coalesce(tipo_evento, '') || ' ' ||
            coalesce(tipo_entidade, '') || ' ' ||
            CASE
                WHEN upper(coalesce(tipo_entidade, '')) IN (
                    'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
                    'PESSOA', 'USER', 'USUARIO'
                ) THEN ''
                ELSE coalesce(entidade_id, '')
            END || ' ' ||
            coalesce(obra_id, '') || ' ' ||
            coalesce(rdo_id, '') || ' ' ||
            coalesce(fonte, '') || ' ' ||
            coalesce(origem, '') || ' ' ||
            coalesce(resultado, '') || ' ' ||
            coalesce(erro_categoria, '') || ' ' ||
            left(coalesce(payload_json ->> 'numeroRdo', ''), 255) || ' ' ||
            left(coalesce(payload_json ->> 'obraNome', ''), 255) || ' ' ||
            left(coalesce(payload_json ->> 'servicoNome', ''), 255) || ' ' ||
            left(coalesce(payload_json ->> 'serviceName', ''), 255) || ' ' ||
            left(coalesce(payload_json ->> 'nomeServico', ''), 255)
        )
    ) STORED,
    ADD COLUMN search_document tsvector GENERATED ALWAYS AS (
        setweight(
            to_tsvector(
                'simple'::regconfig,
                coalesce(id, '') || ' ' ||
                coalesce(tipo_evento, '') || ' ' ||
                coalesce(tipo_entidade, '') || ' ' ||
                CASE
                    WHEN upper(coalesce(tipo_entidade, '')) IN (
                        'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
                        'PESSOA', 'USER', 'USUARIO'
                    ) THEN ''
                    ELSE coalesce(entidade_id, '')
                END || ' ' ||
                coalesce(obra_id, '') || ' ' ||
                coalesce(rdo_id, '')
            ),
            'A'
        ) ||
        setweight(
            to_tsvector(
                'portuguese'::regconfig,
                coalesce(tipo_evento, '') || ' ' ||
                coalesce(fonte, '') || ' ' ||
                coalesce(origem, '') || ' ' ||
                coalesce(resultado, '') || ' ' ||
                coalesce(erro_categoria, '') || ' ' ||
                left(coalesce(payload_json ->> 'numeroRdo', ''), 255) || ' ' ||
                left(coalesce(payload_json ->> 'obraNome', ''), 255) || ' ' ||
                left(coalesce(payload_json ->> 'servicoNome', ''), 255) || ' ' ||
                left(coalesce(payload_json ->> 'serviceName', ''), 255) || ' ' ||
                left(coalesce(payload_json ->> 'nomeServico', ''), 255)
            ),
            'B'
        )
    ) STORED;

CREATE INDEX idx_cortex_evento_memory_search_document
    ON cortex_evento_operacional USING gin (search_document);
CREATE INDEX idx_cortex_evento_memory_search_text
    ON cortex_evento_operacional USING gin (search_text gin_trgm_ops);
CREATE INDEX idx_cortex_evento_memory_cursor
    ON cortex_evento_operacional (commit_seq DESC, id DESC);
CREATE INDEX idx_cortex_evento_memory_worksite_cursor
    ON cortex_evento_operacional (obra_id, commit_seq DESC, id DESC);
CREATE INDEX idx_cortex_evento_memory_actor_global_cursor
    ON cortex_evento_operacional (usuario_id, commit_seq DESC, id DESC)
    WHERE obra_id IS NULL;

-- These are the only joined names used by Memory search. No collaborator,
-- supplier, message or fiscal-document text is indexed.
CREATE INDEX idx_obra_memory_name_trgm
    ON obra USING gin (lower(nome) gin_trgm_ops);
CREATE INDEX idx_rdo_memory_number_trgm
    ON rdo USING gin (lower(numero_rdo) gin_trgm_ops)
    WHERE numero_rdo IS NOT NULL;
CREATE INDEX idx_ontology_entity_memory_name_trgm
    ON ontology_entities USING gin (lower(canonical_name) gin_trgm_ops)
    WHERE upper(entity_type) NOT IN (
        'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
        'PESSOA', 'USER', 'USUARIO'
    );
CREATE INDEX idx_ontology_entity_memory_external_trgm
    ON ontology_entities USING gin (lower(external_ref_id) gin_trgm_ops)
    WHERE external_ref_id IS NOT NULL
      AND upper(entity_type) NOT IN (
          'ACTOR', 'COLABORADOR', 'COLLABORATOR', 'PERSON',
          'PESSOA', 'USER', 'USUARIO'
      );
