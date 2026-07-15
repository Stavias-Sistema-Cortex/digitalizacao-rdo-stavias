-- Comunicação operacional escopada por conversa e integrada à memória Córtex.
-- IDs de mensagem/anexo vêm do cliente para que retries offline sejam
-- idempotentes. Participação e remoção são temporais: nenhuma FK usa cascade.

CREATE TABLE conversa (
    id CHAR(36) NOT NULL,

    tipo VARCHAR(20) NOT NULL,
    titulo VARCHAR(160),
    obra_id CHAR(36),
    equipe_id CHAR(36),
    criado_por CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    ultima_atividade_em DATETIME(6) NOT NULL,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivada_em DATETIME(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT fk_conversa_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversa_equipe
        FOREIGN KEY (equipe_id) REFERENCES equipe(id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversa_criador
        FOREIGN KEY (criado_por) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_conversa_tipo
        CHECK (tipo IN ('OBRA', 'EQUIPE', 'DIRETA', 'GRUPO')),
    CONSTRAINT chk_conversa_status
        CHECK (status IN ('ATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_conversa_contexto
        CHECK (
            (tipo = 'OBRA' AND obra_id IS NOT NULL)
            OR (tipo = 'EQUIPE' AND equipe_id IS NOT NULL)
            OR tipo IN ('DIRETA', 'GRUPO')
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_conversa_obra_atividade
    ON conversa (obra_id, status, ultima_atividade_em, id);

CREATE INDEX idx_conversa_equipe_atividade
    ON conversa (equipe_id, status, ultima_atividade_em, id);

CREATE INDEX idx_conversa_atividade
    ON conversa (status, ultima_atividade_em, id);


CREATE TABLE conversa_participante (
    id CHAR(36) NOT NULL,

    conversa_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    papel VARCHAR(20) NOT NULL DEFAULT 'MEMBRO',
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    entrou_em DATETIME(6) NOT NULL,
    saiu_em DATETIME(6),
    ultima_leitura_em DATETIME(6),

    adicionado_por CHAR(36) NOT NULL,
    removido_por CHAR(36),
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    participante_ativo_id CHAR(36) GENERATED ALWAYS AS (
        CASE
            WHEN status = 'ATIVO' AND saiu_em IS NULL THEN colaborador_id
            ELSE NULL
        END
    ) STORED,

    PRIMARY KEY (id),
    CONSTRAINT uq_conversa_participante_inicio
        UNIQUE (conversa_id, colaborador_id, entrou_em),
    CONSTRAINT uq_conversa_participante_ativo
        UNIQUE (conversa_id, participante_ativo_id),
    CONSTRAINT fk_conversa_participante_conversa
        FOREIGN KEY (conversa_id) REFERENCES conversa(id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversa_participante_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversa_participante_adicionador
        FOREIGN KEY (adicionado_por) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversa_participante_removedor
        FOREIGN KEY (removido_por) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_conversa_participante_papel
        CHECK (papel IN ('ADMINISTRADOR', 'MEMBRO')),
    CONSTRAINT chk_conversa_participante_status
        CHECK (status IN ('ATIVO', 'SAIU', 'REMOVIDO')),
    CONSTRAINT chk_conversa_participante_periodo
        CHECK (saiu_em IS NULL OR saiu_em >= entrou_em),
    CONSTRAINT chk_conversa_participante_estado
        CHECK (
            (status = 'ATIVO' AND saiu_em IS NULL)
            OR (status IN ('SAIU', 'REMOVIDO') AND saiu_em IS NOT NULL)
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_conversa_participante_usuario
    ON conversa_participante (colaborador_id, status, conversa_id);

CREATE INDEX idx_conversa_participante_conversa
    ON conversa_participante (conversa_id, status, entrou_em, id);


CREATE TABLE mensagem (
    id CHAR(36) NOT NULL,

    conversa_id CHAR(36) NOT NULL,
    remetente_id CHAR(36) NOT NULL,
    client_message_id CHAR(36) NOT NULL,
    texto TEXT,
    estado VARCHAR(20) NOT NULL DEFAULT 'ENVIADA',
    enviada_cliente_em DATETIME(6) NOT NULL,
    criada_servidor_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizada_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    editada_em DATETIME(6),
    removida_em DATETIME(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT uq_mensagem_cliente
        UNIQUE (remetente_id, client_message_id),
    CONSTRAINT fk_mensagem_conversa
        FOREIGN KEY (conversa_id) REFERENCES conversa(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mensagem_remetente
        FOREIGN KEY (remetente_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_mensagem_estado
        CHECK (estado IN ('ENVIADA', 'EDITADA', 'REMOVIDA')),
    CONSTRAINT chk_mensagem_texto
        CHECK (texto IS NULL OR CHAR_LENGTH(TRIM(texto)) > 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_mensagem_conversa_ordem
    ON mensagem (conversa_id, enviada_cliente_em, criada_servidor_em, id);

CREATE INDEX idx_mensagem_remetente
    ON mensagem (remetente_id, criada_servidor_em, id);


CREATE TABLE mensagem_referencia (
    id CHAR(36) NOT NULL,

    mensagem_id CHAR(36) NOT NULL,
    tipo_objeto VARCHAR(80) NOT NULL,
    objeto_id CHAR(36) NOT NULL,
    obra_id CHAR(36),
    criada_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uq_mensagem_referencia
        UNIQUE (mensagem_id, tipo_objeto, objeto_id),
    CONSTRAINT fk_mensagem_referencia_mensagem
        FOREIGN KEY (mensagem_id) REFERENCES mensagem(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mensagem_referencia_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mensagem_referencia_criador
        FOREIGN KEY (criada_por) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_mensagem_referencia_tipo
        CHECK (CHAR_LENGTH(TRIM(tipo_objeto)) > 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_mensagem_referencia_objeto
    ON mensagem_referencia (tipo_objeto, objeto_id, mensagem_id);

CREATE INDEX idx_mensagem_referencia_obra
    ON mensagem_referencia (obra_id, criado_em, mensagem_id);


CREATE TABLE mensagem_anexo (
    id CHAR(36) NOT NULL,

    mensagem_id CHAR(36) NOT NULL,
    client_attachment_id CHAR(36) NOT NULL,
    enviado_por CHAR(36) NOT NULL,
    nome_original VARCHAR(255) NOT NULL,
    nome_seguro VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    tamanho_bytes BIGINT NOT NULL,
    hash_sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    ultimo_erro VARCHAR(1000),

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    disponivel_em DATETIME(6),
    removido_em DATETIME(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT uq_mensagem_anexo_cliente
        UNIQUE (mensagem_id, client_attachment_id),
    CONSTRAINT uq_mensagem_anexo_storage
        UNIQUE (storage_key),
    CONSTRAINT fk_mensagem_anexo_mensagem
        FOREIGN KEY (mensagem_id) REFERENCES mensagem(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mensagem_anexo_remetente
        FOREIGN KEY (enviado_por) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_mensagem_anexo_status
        CHECK (status IN ('PENDENTE', 'DISPONIVEL', 'FALHOU', 'REMOVIDO')),
    CONSTRAINT chk_mensagem_anexo_tamanho
        CHECK (tamanho_bytes > 0),
    CONSTRAINT chk_mensagem_anexo_disponibilidade
        CHECK (
            status <> 'DISPONIVEL'
            OR (storage_key IS NOT NULL AND disponivel_em IS NOT NULL)
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_mensagem_anexo_mensagem
    ON mensagem_anexo (mensagem_id, status, criado_em, id);

CREATE INDEX idx_mensagem_anexo_hash
    ON mensagem_anexo (hash_sha256, tamanho_bytes);


CREATE TABLE mensagem_recibo (
    id CHAR(36) NOT NULL,

    mensagem_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    entregue_em DATETIME(6),
    lida_em DATETIME(6),
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uq_mensagem_recibo
        UNIQUE (mensagem_id, colaborador_id),
    CONSTRAINT fk_mensagem_recibo_mensagem
        FOREIGN KEY (mensagem_id) REFERENCES mensagem(id) ON DELETE RESTRICT,
    CONSTRAINT fk_mensagem_recibo_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_mensagem_recibo_ordem
        CHECK (lida_em IS NULL OR entregue_em IS NULL OR lida_em >= entregue_em)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_mensagem_recibo_usuario
    ON mensagem_recibo (colaborador_id, lida_em, mensagem_id);


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
            'CRIAR_MENSAGEM',
            'OUTRA'
        ));
