-- Mensagens persistentes e autorizadas. O banco guarda somente metadados de
-- anexos: os bytes permanecem no ObjectStorage introduzido em V29.

CREATE TABLE equipe (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    nome VARCHAR(160) NOT NULL,
    descricao VARCHAR(500) NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ATIVA',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em DATETIME(6) NULL,
    deletado_por CHAR(36) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_equipe_obra_nome UNIQUE (obra_id, nome),
    CONSTRAINT fk_equipe_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_equipe_created_by
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_equipe_deleted_by
        FOREIGN KEY (deletado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_equipe_status
        CHECK (status IN ('ATIVA', 'ARQUIVADA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_equipe_obra_status
    ON equipe (obra_id, status, atualizado_em, id);

CREATE TABLE equipe_membro (
    id CHAR(36) NOT NULL,
    equipe_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    papel VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'MEMBRO',
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ATIVO',
    adicionado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    adicionado_por CHAR(36) NOT NULL,
    removido_em DATETIME(6) NULL,
    removido_por CHAR(36) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_equipe_membro UNIQUE (equipe_id, colaborador_id),
    CONSTRAINT fk_equipe_membro_equipe
        FOREIGN KEY (equipe_id) REFERENCES equipe(id),
    CONSTRAINT fk_equipe_membro_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_equipe_membro_added_by
        FOREIGN KEY (adicionado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_equipe_membro_removed_by
        FOREIGN KEY (removido_por) REFERENCES colaborador(id),
    CONSTRAINT chk_equipe_membro_papel
        CHECK (papel IN ('ADMIN', 'MEMBRO')),
    CONSTRAINT chk_equipe_membro_status
        CHECK (status IN ('ATIVO', 'REMOVIDO')),
    CONSTRAINT chk_equipe_membro_intervalo
        CHECK (
            (status = 'ATIVO' AND removido_em IS NULL)
            OR (status = 'REMOVIDO' AND removido_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_equipe_membro_colaborador
    ON equipe_membro (colaborador_id, status, atualizado_em, id);

CREATE TABLE conversa (
    id CHAR(36) NOT NULL,
    tipo VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obra_id CHAR(36) NULL,
    equipe_id CHAR(36) NULL,
    titulo VARCHAR(200) NULL,
    chave_participantes_direta CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ATIVA',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em DATETIME(6) NULL,
    deletado_por CHAR(36) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_conversa_direta_participantes
        UNIQUE (chave_participantes_direta),
    CONSTRAINT fk_conversa_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_conversa_equipe
        FOREIGN KEY (equipe_id) REFERENCES equipe(id),
    CONSTRAINT fk_conversa_created_by
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_conversa_deleted_by
        FOREIGN KEY (deletado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_conversa_tipo
        CHECK (tipo IN ('DIRETA', 'GRUPO', 'EQUIPE', 'OBRA')),
    CONSTRAINT chk_conversa_status
        CHECK (status IN ('ATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_conversa_escopo
        CHECK (
            (tipo = 'DIRETA'
                AND chave_participantes_direta IS NOT NULL
                AND obra_id IS NULL
                AND equipe_id IS NULL)
            OR (tipo = 'GRUPO'
                AND chave_participantes_direta IS NULL
                AND equipe_id IS NULL)
            OR (tipo = 'EQUIPE'
                AND chave_participantes_direta IS NULL
                AND obra_id IS NOT NULL
                AND equipe_id IS NOT NULL)
            OR (tipo = 'OBRA'
                AND chave_participantes_direta IS NULL
                AND obra_id IS NOT NULL
                AND equipe_id IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_conversa_obra_atualizado
    ON conversa (obra_id, status, atualizado_em, id);
CREATE INDEX idx_conversa_equipe_atualizado
    ON conversa (equipe_id, status, atualizado_em, id);

CREATE TABLE conversa_participante (
    id CHAR(36) NOT NULL,
    conversa_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    papel VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'MEMBRO',
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ATIVO',
    adicionado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    adicionado_por CHAR(36) NOT NULL,
    removido_em DATETIME(6) NULL,
    removido_por CHAR(36) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_conversa_participante
        UNIQUE (conversa_id, colaborador_id),
    CONSTRAINT fk_conversa_participante_conversa
        FOREIGN KEY (conversa_id) REFERENCES conversa(id),
    CONSTRAINT fk_conversa_participante_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_conversa_participante_added_by
        FOREIGN KEY (adicionado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_conversa_participante_removed_by
        FOREIGN KEY (removido_por) REFERENCES colaborador(id),
    CONSTRAINT chk_conversa_participante_papel
        CHECK (papel IN ('ADMIN', 'MEMBRO')),
    CONSTRAINT chk_conversa_participante_status
        CHECK (status IN ('ATIVO', 'REMOVIDO')),
    CONSTRAINT chk_conversa_participante_intervalo
        CHECK (
            (status = 'ATIVO' AND removido_em IS NULL)
            OR (status = 'REMOVIDO' AND removido_em IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_conversa_participante_colaborador
    ON conversa_participante (
        colaborador_id,
        status,
        atualizado_em,
        conversa_id
    );

CREATE TABLE mensagem (
    id CHAR(36) NOT NULL,
    conversa_id CHAR(36) NOT NULL,
    autor_id CHAR(36) NOT NULL,
    corpo TEXT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ATIVA',
    client_mutation_id VARCHAR(120)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    criado_cliente_em DATETIME(6) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    editado_em DATETIME(6) NULL,
    editado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em DATETIME(6) NULL,
    deletado_por CHAR(36) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_mensagem_autor_mutation
        UNIQUE (autor_id, client_mutation_id),
    CONSTRAINT fk_mensagem_conversa
        FOREIGN KEY (conversa_id) REFERENCES conversa(id),
    CONSTRAINT fk_mensagem_autor
        FOREIGN KEY (autor_id) REFERENCES colaborador(id),
    CONSTRAINT fk_mensagem_edited_by
        FOREIGN KEY (editado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_mensagem_deleted_by
        FOREIGN KEY (deletado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_mensagem_status
        CHECK (status IN ('ATIVA', 'EDITADA', 'EXCLUIDA')),
    CONSTRAINT chk_mensagem_conteudo
        CHECK (status = 'EXCLUIDA' OR corpo IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_mensagem_conversa_criado
    ON mensagem (conversa_id, criado_em DESC, id);
CREATE INDEX idx_mensagem_autor_criado
    ON mensagem (autor_id, criado_em DESC, id);
CREATE FULLTEXT INDEX ft_mensagem_corpo ON mensagem (corpo);

CREATE TABLE mensagem_anexo (
    id CHAR(36) NOT NULL,
    mensagem_id CHAR(36) NOT NULL,
    stored_object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nome_exibicao VARCHAR(255) NULL,
    ordem SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ATIVO',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    deletado_em DATETIME(6) NULL,
    deletado_por CHAR(36) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_mensagem_anexo_objeto
        UNIQUE (mensagem_id, stored_object_id),
    CONSTRAINT fk_mensagem_anexo_mensagem
        FOREIGN KEY (mensagem_id) REFERENCES mensagem(id),
    CONSTRAINT fk_mensagem_anexo_stored_object
        FOREIGN KEY (stored_object_id) REFERENCES stored_object(id),
    CONSTRAINT fk_mensagem_anexo_created_by
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_mensagem_anexo_deleted_by
        FOREIGN KEY (deletado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_mensagem_anexo_status
        CHECK (status IN ('ATIVO', 'REMOVIDO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_mensagem_anexo_mensagem_ordem
    ON mensagem_anexo (mensagem_id, status, ordem, id);
