CREATE TABLE colaborador (
    id                  CHAR(36)        NOT NULL,

    banco_origem        VARCHAR(64)     NOT NULL,
    tabela_origem       VARCHAR(64)     NOT NULL,
    pk_origem           VARCHAR(128)    NOT NULL,

    codigo_colaborador  VARCHAR(120),
    nome                VARCHAR(255)    NOT NULL,
    email               VARCHAR(255),

    id_grupo_origem     VARCHAR(128),
    nome_grupo          VARCHAR(120),

    id_perfil_origem    VARCHAR(128),
    nome_perfil         VARCHAR(120),

    ativo               TINYINT(1)      NOT NULL DEFAULT 1,

    criado_em_origem    DATETIME(6),
    atualizado_em_origem DATETIME(6),
    hash_origem         CHAR(64),

    importado_em        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                         ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em         DATETIME(6),
    versao_linha        BIGINT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT uq_colaborador_registro_origem
        UNIQUE (banco_origem, tabela_origem, pk_origem)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_colaborador_nome
    ON colaborador (nome);

CREATE INDEX idx_colaborador_email
    ON colaborador (email);

CREATE INDEX idx_colaborador_nome_grupo
    ON colaborador (nome_grupo);

CREATE INDEX idx_colaborador_nome_perfil
    ON colaborador (nome_perfil);

CREATE INDEX idx_colaborador_atualizado_cursor
    ON colaborador (atualizado_em, id);
