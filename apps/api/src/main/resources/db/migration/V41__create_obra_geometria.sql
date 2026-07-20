-- Camada geoespacial temporal reconciliada após a sequência V1-V40. GeoJSON preserva a geometria de
-- origem sem fabricar shapes e permite servir o mesmo contrato aos providers.
CREATE TABLE obra_geometria (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    categoria VARCHAR(40) NOT NULL,
    objeto_tipo VARCHAR(40),
    objeto_id CHAR(36),
    tipo_geometria VARCHAR(24) NOT NULL,
    geometria_json JSON NOT NULL,
    propriedades_json JSON NOT NULL,
    fonte VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    valido_desde DATETIME(6) NOT NULL,
    valido_ate DATETIME(6),
    motivo_encerramento VARCHAR(500),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    criado_por CHAR(36) NOT NULL,
    atualizado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_obra_geometria_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_obra_geometria_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_obra_geometria_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_obra_geometria_categoria CHECK (categoria IN (
        'LOCALIZACAO_OBRA',
        'PERIMETRO_OBRA',
        'TRECHO',
        'PONTO_OPERACIONAL',
        'FRENTE_TRABALHO',
        'EQUIPAMENTO',
        'EVENTO',
        'RDO',
        'OCORRENCIA',
        'PROGRAMACAO'
    )),
    CONSTRAINT chk_obra_geometria_tipo CHECK (tipo_geometria IN (
        'POINT', 'MULTIPOINT', 'LINESTRING', 'MULTILINESTRING',
        'POLYGON', 'MULTIPOLYGON'
    )),
    CONSTRAINT chk_obra_geometria_status
        CHECK (status IN ('ATIVA', 'ENCERRADA')),
    CONSTRAINT chk_obra_geometria_vigencia
        CHECK (valido_ate IS NULL OR valido_ate >= valido_desde),
    CONSTRAINT chk_obra_geometria_objeto
        CHECK (
            (objeto_tipo IS NULL AND objeto_id IS NULL)
            OR (objeto_tipo IS NOT NULL AND objeto_id IS NOT NULL)
        )
);

CREATE INDEX idx_obra_geometria_obra_status_categoria
    ON obra_geometria (obra_id, status, categoria, valido_desde, id);

CREATE INDEX idx_obra_geometria_objeto
    ON obra_geometria (objeto_tipo, objeto_id, status);

CREATE INDEX idx_obra_geometria_atualizacao
    ON obra_geometria (atualizado_em, id);
