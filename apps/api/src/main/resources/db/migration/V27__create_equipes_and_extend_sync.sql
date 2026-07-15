-- Domínio temporal de equipes e vocabulário de mutações offline.
--
-- Participação operacional é mantida separada do vínculo de autorização
-- colaborador ↔ obra. Encerrar uma participação preserva a linha histórica e
-- nunca concede ou revoga acesso implicitamente.

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
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_funcao_operacional_ativo_ordem
    ON funcao_operacional (ativo, ordem_exibicao, nome, id);


CREATE TABLE equipe (
    id CHAR(36) NOT NULL,

    obra_principal_id CHAR(36) NOT NULL,
    nome VARCHAR(160) NOT NULL,
    descricao TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    inicio_validade_em DATETIME(6) NOT NULL,
    fim_validade_em DATETIME(6),

    criado_por VARCHAR(120) NOT NULL,
    atualizado_por VARCHAR(120) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivada_em DATETIME(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT fk_equipe_obra_principal
        FOREIGN KEY (obra_principal_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_equipe_status
        CHECK (status IN ('ATIVA', 'INATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_equipe_validade
        CHECK (fim_validade_em IS NULL OR fim_validade_em >= inicio_validade_em)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_equipe_obra_status_nome
    ON equipe (obra_principal_id, status, nome, id);

CREATE INDEX idx_equipe_validade
    ON equipe (inicio_validade_em, fim_validade_em);


CREATE TABLE equipe_obra (
    id CHAR(36) NOT NULL,

    equipe_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    inicio_em DATETIME(6) NOT NULL,
    fim_em DATETIME(6),
    motivo_encerramento VARCHAR(500),

    atribuido_por VARCHAR(120) NOT NULL,
    encerrado_por VARCHAR(120),
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
    CONSTRAINT uq_equipe_obra_inicio
        UNIQUE (equipe_id, obra_id, inicio_em),
    CONSTRAINT uq_equipe_obra_ativa
        UNIQUE (equipe_id, obra_ativa_id),
    CONSTRAINT fk_equipe_obra_equipe
        FOREIGN KEY (equipe_id)
        REFERENCES equipe(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_equipe_obra_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_equipe_obra_status
        CHECK (status IN ('ATIVO', 'ENCERRADO')),
    CONSTRAINT chk_equipe_obra_periodo
        CHECK (fim_em IS NULL OR fim_em >= inicio_em),
    CONSTRAINT chk_equipe_obra_estado_temporal
        CHECK (
            (status = 'ATIVO' AND fim_em IS NULL)
            OR (status = 'ENCERRADO' AND fim_em IS NOT NULL)
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_equipe_obra_obra_status
    ON equipe_obra (obra_id, status, inicio_em, id);

CREATE INDEX idx_equipe_obra_equipe_periodo
    ON equipe_obra (equipe_id, inicio_em, fim_em, id);


CREATE TABLE equipe_membro (
    id CHAR(36) NOT NULL,

    equipe_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    funcao_operacional_id CHAR(36) NOT NULL,
    responsavel TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    inicio_em DATETIME(6) NOT NULL,
    fim_em DATETIME(6),
    motivo_encerramento VARCHAR(500),

    atribuido_por VARCHAR(120) NOT NULL,
    atualizado_por VARCHAR(120) NOT NULL,
    encerrado_por VARCHAR(120),
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    colaborador_ativo_id CHAR(36) GENERATED ALWAYS AS (
        CASE
            WHEN status = 'ATIVO' AND fim_em IS NULL THEN colaborador_id
            ELSE NULL
        END
    ) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uq_equipe_membro_inicio
        UNIQUE (equipe_id, colaborador_id, inicio_em),
    CONSTRAINT uq_equipe_membro_ativo
        UNIQUE (equipe_id, colaborador_ativo_id),
    CONSTRAINT fk_equipe_membro_equipe
        FOREIGN KEY (equipe_id)
        REFERENCES equipe(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_equipe_membro_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_equipe_membro_funcao
        FOREIGN KEY (funcao_operacional_id)
        REFERENCES funcao_operacional(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_equipe_membro_status
        CHECK (status IN ('ATIVO', 'ENCERRADO')),
    CONSTRAINT chk_equipe_membro_periodo
        CHECK (fim_em IS NULL OR fim_em >= inicio_em),
    CONSTRAINT chk_equipe_membro_estado_temporal
        CHECK (
            (status = 'ATIVO' AND fim_em IS NULL)
            OR (status = 'ENCERRADO' AND fim_em IS NOT NULL)
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_equipe_membro_equipe_status
    ON equipe_membro (equipe_id, status, inicio_em, id);

CREATE INDEX idx_equipe_membro_colaborador_status
    ON equipe_membro (colaborador_id, status, inicio_em, id);

CREATE INDEX idx_equipe_membro_funcao_status
    ON equipe_membro (funcao_operacional_id, status, inicio_em, id);


ALTER TABLE sync_mutacao_cliente
    DROP CHECK chk_sync_mutacao_operacao;

ALTER TABLE sync_mutacao_cliente
    ADD CONSTRAINT chk_sync_mutacao_operacao
        CHECK (operacao IN (
            'CRIAR_RDO',
            'ATUALIZAR_RDO_RASCUNHO',
            'ENVIAR_RDO',
            'CANCELAR_RDO',
            'CRIAR_OBRA',
            'ATUALIZAR_OBRA',
            'CRIAR_EQUIPE',
            'ATUALIZAR_EQUIPE',
            'ARQUIVAR_EQUIPE',
            'ADICIONAR_MEMBRO_EQUIPE',
            'ATUALIZAR_MEMBRO_EQUIPE',
            'ENCERRAR_MEMBRO_EQUIPE',
            'OUTRA'
        ));
