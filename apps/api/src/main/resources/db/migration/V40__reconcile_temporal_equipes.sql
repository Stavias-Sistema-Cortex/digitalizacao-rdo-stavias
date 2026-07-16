-- The merged teams branch originally reused V27 while the active auth
-- history already owns V27-V39. V30 created the messaging team tables, so
-- this forward-only migration extends those tables instead of recreating or
-- replacing them. Existing rows retain their known timestamps and actors;
-- no operational role is inferred for legacy memberships.

CREATE TABLE funcao_operacional (
    id CHAR(36) NOT NULL,
    codigo VARCHAR(80) NOT NULL,
    nome VARCHAR(160) NOT NULL,
    descricao TEXT,
    ativo TINYINT(1) NOT NULL DEFAULT 1,
    ordem_exibicao INT NOT NULL DEFAULT 0,
    criado_por VARCHAR(120) NOT NULL,
    atualizado_por VARCHAR(120) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_funcao_operacional_codigo UNIQUE (codigo),
    CONSTRAINT chk_funcao_operacional_ordem CHECK (ordem_exibicao >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_funcao_operacional_ativo_ordem
    ON funcao_operacional (ativo, ordem_exibicao, nome, id);

ALTER TABLE equipe
    ADD COLUMN obra_principal_id CHAR(36) NULL AFTER obra_id,
    ADD COLUMN inicio_validade_em DATETIME(6) NULL AFTER status,
    ADD COLUMN fim_validade_em DATETIME(6) NULL AFTER inicio_validade_em,
    ADD COLUMN arquivada_em DATETIME(6) NULL AFTER deletado_em,
    ADD COLUMN atualizado_por CHAR(36) NULL AFTER criado_por;

UPDATE equipe
SET obra_principal_id = obra_id,
    inicio_validade_em = criado_em,
    fim_validade_em = CASE
        WHEN status = 'ARQUIVADA' THEN COALESCE(deletado_em, atualizado_em)
        ELSE NULL
    END,
    arquivada_em = CASE
        WHEN status = 'ARQUIVADA' THEN deletado_em
        ELSE NULL
    END,
    atualizado_por = criado_por
WHERE obra_principal_id IS NULL
   OR inicio_validade_em IS NULL
   OR atualizado_por IS NULL;

ALTER TABLE equipe
    MODIFY obra_principal_id CHAR(36) NOT NULL,
    MODIFY inicio_validade_em DATETIME(6) NOT NULL,
    MODIFY atualizado_por CHAR(36) NOT NULL,
    DROP CHECK chk_equipe_status,
    ADD CONSTRAINT fk_equipe_obra_principal
        FOREIGN KEY (obra_principal_id) REFERENCES obra(id),
    ADD CONSTRAINT chk_equipe_status
        CHECK (status IN ('ATIVA', 'INATIVA', 'ARQUIVADA')),
    ADD CONSTRAINT chk_equipe_validade
        CHECK (
            fim_validade_em IS NULL
            OR fim_validade_em >= inicio_validade_em
        );

ALTER TABLE equipe_membro
    ADD COLUMN funcao_operacional_id CHAR(36) NULL AFTER colaborador_id,
    ADD COLUMN responsavel TINYINT(1) NOT NULL DEFAULT 0
        AFTER funcao_operacional_id,
    ADD COLUMN inicio_em DATETIME(6) NULL AFTER status,
    ADD COLUMN fim_em DATETIME(6) NULL AFTER removido_em,
    ADD COLUMN motivo_encerramento VARCHAR(500) NULL AFTER fim_em,
    ADD COLUMN atribuido_por CHAR(36) NULL AFTER adicionado_por,
    ADD COLUMN atualizado_por CHAR(36) NULL AFTER atribuido_por,
    ADD COLUMN encerrado_por CHAR(36) NULL AFTER removido_por;

UPDATE equipe_membro
SET inicio_em = adicionado_em,
    fim_em = removido_em,
    atribuido_por = adicionado_por,
    atualizado_por = adicionado_por,
    encerrado_por = removido_por
WHERE inicio_em IS NULL
   OR atribuido_por IS NULL
   OR atualizado_por IS NULL;

ALTER TABLE equipe_membro
    MODIFY inicio_em DATETIME(6) NOT NULL,
    MODIFY atribuido_por CHAR(36) NOT NULL,
    MODIFY atualizado_por CHAR(36) NOT NULL,
    DROP INDEX uq_equipe_membro,
    DROP CHECK chk_equipe_membro_status,
    ADD COLUMN colaborador_ativo_id CHAR(36) GENERATED ALWAYS AS (
        CASE
            WHEN status = 'ATIVO' AND fim_em IS NULL THEN colaborador_id
            ELSE NULL
        END
    ) STORED AFTER versao_linha,
    ADD CONSTRAINT uq_equipe_membro_inicio
        UNIQUE (equipe_id, colaborador_id, inicio_em),
    ADD CONSTRAINT uq_equipe_membro_ativo
        UNIQUE (equipe_id, colaborador_ativo_id),
    ADD CONSTRAINT fk_equipe_membro_funcao_operacional
        FOREIGN KEY (funcao_operacional_id)
        REFERENCES funcao_operacional(id),
    ADD CONSTRAINT chk_equipe_membro_status
        CHECK (status IN ('ATIVO', 'REMOVIDO', 'ENCERRADO')),
    ADD CONSTRAINT chk_equipe_membro_periodo
        CHECK (fim_em IS NULL OR fim_em >= inicio_em);

CREATE INDEX idx_equipe_membro_equipe_status
    ON equipe_membro (equipe_id, status, inicio_em, id);
CREATE INDEX idx_equipe_membro_colaborador_status
    ON equipe_membro (colaborador_id, status, inicio_em, id);
CREATE INDEX idx_equipe_membro_funcao_status
    ON equipe_membro (funcao_operacional_id, status, inicio_em, id);

CREATE TABLE equipe_obra (
    id CHAR(36) NOT NULL,
    equipe_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    inicio_em DATETIME(6) NOT NULL,
    fim_em DATETIME(6) NULL,
    motivo_encerramento VARCHAR(500) NULL,
    atribuido_por VARCHAR(120) NOT NULL,
    encerrado_por VARCHAR(120) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    obra_ativa_id CHAR(36) GENERATED ALWAYS AS (
        CASE
            WHEN status = 'ATIVO' AND fim_em IS NULL THEN obra_id
            ELSE NULL
        END
    ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_equipe_obra_inicio UNIQUE (equipe_id, obra_id, inicio_em),
    CONSTRAINT uq_equipe_obra_ativa UNIQUE (equipe_id, obra_ativa_id),
    CONSTRAINT fk_equipe_obra_equipe
        FOREIGN KEY (equipe_id) REFERENCES equipe(id) ON DELETE RESTRICT,
    CONSTRAINT fk_equipe_obra_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT chk_equipe_obra_status
        CHECK (status IN ('ATIVO', 'ENCERRADO')),
    CONSTRAINT chk_equipe_obra_periodo
        CHECK (fim_em IS NULL OR fim_em >= inicio_em),
    CONSTRAINT chk_equipe_obra_estado_temporal
        CHECK (
            (status = 'ATIVO' AND fim_em IS NULL)
            OR (status = 'ENCERRADO' AND fim_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO equipe_obra (
    id,
    equipe_id,
    obra_id,
    status,
    inicio_em,
    fim_em,
    motivo_encerramento,
    atribuido_por,
    encerrado_por,
    versao_linha
)
SELECT
    UUID(),
    e.id,
    e.obra_principal_id,
    CASE WHEN e.status = 'ARQUIVADA' THEN 'ENCERRADO' ELSE 'ATIVO' END,
    e.inicio_validade_em,
    CASE
        WHEN e.status = 'ARQUIVADA'
            THEN COALESCE(e.fim_validade_em, e.atualizado_em)
        ELSE NULL
    END,
    NULL,
    e.criado_por,
    NULL,
    e.versao_linha
FROM equipe e;

CREATE INDEX idx_equipe_obra_obra_status
    ON equipe_obra (obra_id, status, inicio_em, id);
CREATE INDEX idx_equipe_obra_equipe_periodo
    ON equipe_obra (equipe_id, inicio_em, fim_em, id);
