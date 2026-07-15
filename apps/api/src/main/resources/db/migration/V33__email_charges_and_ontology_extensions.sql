-- Sender profiles reference external provider configuration by key. Provider
-- credentials and authenticated mailbox secrets never belong in this schema.
CREATE TABLE finance_email_perfil_remetente (
    id CHAR(36) NOT NULL,
    codigo VARCHAR(80) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    configuracao_remetente_chave VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_email_remetente_codigo UNIQUE (codigo),
    CONSTRAINT uq_fin_email_remetente_config
        UNIQUE (configuracao_remetente_chave),
    CONSTRAINT fk_fin_email_remetente_criado
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_email_remetente_atualizado
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_email_remetente_arquivado
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_email_remetente_status
        CHECK (status IN ('ATIVO', 'INATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_email_remetente_arquivo
        CHECK (
            (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL
                AND arquivado_por IS NOT NULL)
            OR (status <> 'ARQUIVADO' AND arquivado_em IS NULL
                AND arquivado_por IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE finance_email_reply_to_permitido (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NULL,
    nome VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_email_reply_obra UNIQUE (obra_id, email),
    CONSTRAINT uq_fin_email_reply_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_email_reply_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_email_reply_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_email_reply_criado
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_email_reply_atualizado
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_email_reply_arquivado
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_email_reply_status
        CHECK (status IN ('ATIVO', 'INATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_email_reply_formato
        CHECK (email LIKE '%_@_%._%' AND email NOT LIKE '% %'),
    CONSTRAINT chk_fin_email_reply_arquivo
        CHECK (
            (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL
                AND arquivado_por IS NOT NULL)
            OR (status <> 'ARQUIVADO' AND arquivado_em IS NULL
                AND arquivado_por IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_email_reply_obra_status
    ON finance_email_reply_to_permitido (obra_id, status, email);

CREATE TABLE finance_modelo_email (
    id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    codigo VARCHAR(80) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    versao INT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RASCUNHO',
    assunto_template VARCHAR(500) NOT NULL,
    corpo_texto_template TEXT NOT NULL,
    variaveis_permitidas_json JSON NOT NULL,
    previsualizacao_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    previsualizado_por CHAR(36) NULL,
    previsualizado_em DATETIME(6) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_modelo_email_versao UNIQUE (obra_id, codigo, versao),
    CONSTRAINT uq_fin_modelo_email_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_modelo_email_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_modelo_email_preview
        FOREIGN KEY (previsualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_modelo_email_criado
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_modelo_email_atualizado
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_modelo_email_arquivado
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_modelo_email_versao CHECK (versao > 0),
    CONSTRAINT chk_fin_modelo_email_status
        CHECK (status IN (
            'RASCUNHO', 'PREVISUALIZADO', 'ATIVO', 'INATIVO', 'ARQUIVADO'
        )),
    CONSTRAINT chk_fin_modelo_email_preview
        CHECK (
            (status IN ('ATIVO', 'PREVISUALIZADO')
                AND previsualizacao_hash IS NOT NULL
                AND previsualizado_por IS NOT NULL
                AND previsualizado_em IS NOT NULL)
            OR status NOT IN ('ATIVO', 'PREVISUALIZADO')
        ),
    CONSTRAINT chk_fin_modelo_email_arquivo
        CHECK (
            (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL
                AND arquivado_por IS NOT NULL)
            OR (status <> 'ARQUIVADO' AND arquivado_em IS NULL
                AND arquivado_por IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_modelo_email_obra_status
    ON finance_modelo_email (obra_id, status, atualizado_em);

CREATE TABLE finance_regra_cobranca (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    modelo_email_id CHAR(36) NOT NULL,
    perfil_remetente_id CHAR(36) NOT NULL,
    reply_to_permitido_id CHAR(36) NULL,
    nome VARCHAR(255) NOT NULL,
    modo VARCHAR(20) NOT NULL,
    referencia VARCHAR(30) NOT NULL DEFAULT 'VENCIMENTO',
    deslocamento_dias INT NOT NULL DEFAULT 0,
    horario_local TIME NULL,
    fuso_horario VARCHAR(80) NOT NULL,
    vigente_de DATE NULL,
    vigente_ate DATE NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RASCUNHO',
    previsualizacao_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    previsualizacao_confirmada_por CHAR(36) NULL,
    previsualizacao_confirmada_em DATETIME(6) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    arquivado_por CHAR(36) NULL,
    arquivado_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_regra_cobranca_nome UNIQUE (obra_id, nome),
    CONSTRAINT uq_fin_regra_cobranca_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT uq_fin_regra_cobranca_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_regra_cobranca_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_regra_cobranca_modelo
        FOREIGN KEY (modelo_email_id, obra_id)
        REFERENCES finance_modelo_email(id, obra_id),
    CONSTRAINT fk_fin_regra_cobranca_remetente
        FOREIGN KEY (perfil_remetente_id)
        REFERENCES finance_email_perfil_remetente(id),
    CONSTRAINT fk_fin_regra_cobranca_reply
        FOREIGN KEY (reply_to_permitido_id, obra_id)
        REFERENCES finance_email_reply_to_permitido(id, obra_id),
    CONSTRAINT fk_fin_regra_cobranca_preview
        FOREIGN KEY (previsualizacao_confirmada_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_regra_cobranca_criado
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_regra_cobranca_atualizado
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_regra_cobranca_arquivado
        FOREIGN KEY (arquivado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_regra_cobranca_modo
        CHECK (modo IN ('MANUAL', 'AGENDADA', 'AUTOMATICA')),
    CONSTRAINT chk_fin_regra_cobranca_referencia
        CHECK (referencia IN ('VENCIMENTO', 'EMISSAO', 'CRIACAO')),
    CONSTRAINT chk_fin_regra_cobranca_status
        CHECK (status IN (
            'RASCUNHO', 'PREVISUALIZADA', 'ATIVA', 'INATIVA', 'ARQUIVADA'
        )),
    CONSTRAINT chk_fin_regra_cobranca_vigencia
        CHECK (vigente_ate IS NULL OR vigente_de IS NULL
            OR vigente_ate >= vigente_de),
    CONSTRAINT chk_fin_regra_cobranca_ativacao
        CHECK (
            (status = 'ATIVA'
                AND previsualizacao_hash IS NOT NULL
                AND previsualizacao_confirmada_por IS NOT NULL
                AND previsualizacao_confirmada_em IS NOT NULL)
            OR status <> 'ATIVA'
        ),
    CONSTRAINT chk_fin_regra_cobranca_arquivo
        CHECK (
            (status = 'ARQUIVADA' AND arquivado_em IS NOT NULL
                AND arquivado_por IS NOT NULL)
            OR (status <> 'ARQUIVADA' AND arquivado_em IS NULL
                AND arquivado_por IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_regra_cobranca_obra_status
    ON finance_regra_cobranca (
        obra_id, status, modo, vigente_de, vigente_ate
    );

CREATE TABLE finance_cobranca_email (
    id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(200)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obra_id CHAR(36) NOT NULL,
    regra_id CHAR(36) NULL,
    modelo_email_id CHAR(36) NOT NULL,
    perfil_remetente_id CHAR(36) NOT NULL,
    reply_to_permitido_id CHAR(36) NULL,
    fornecedor_id CHAR(36) NOT NULL,
    compra_id CHAR(36) NULL,
    nota_fiscal_id CHAR(36) NULL,
    lancamento_id CHAR(36) NULL,
    responsavel_id CHAR(36) NOT NULL,
    modo VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RASCUNHO',
    destinatario VARCHAR(320) NOT NULL,
    vencimento_em DATE NULL,
    ocorrencia_prevista_em DATETIME(6) NOT NULL,
    agendada_para DATETIME(6) NULL,
    dados_renderizacao_json JSON NOT NULL,
    previsualizacao_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    previsualizacao_confirmada_por CHAR(36) NULL,
    previsualizacao_confirmada_em DATETIME(6) NULL,
    tentativas INT NOT NULL DEFAULT 0,
    proxima_tentativa_em DATETIME(6) NULL,
    lock_token CHAR(36) NULL,
    lock_ate DATETIME(6) NULL,
    provider VARCHAR(80) NULL,
    provider_message_id VARCHAR(255) NULL,
    enviada_em DATETIME(6) NULL,
    entregue_em DATETIME(6) NULL,
    erro_categoria VARCHAR(80) NULL,
    erro_sanitizado VARCHAR(1000) NULL,
    cancelada_por CHAR(36) NULL,
    cancelada_em DATETIME(6) NULL,
    motivo_cancelamento VARCHAR(1000) NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por CHAR(36) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    alvo_tipo VARCHAR(20)
        GENERATED ALWAYS AS (
            CASE
                WHEN compra_id IS NOT NULL THEN 'COMPRA'
                WHEN nota_fiscal_id IS NOT NULL THEN 'NOTA_FISCAL'
                ELSE 'LANCAMENTO'
            END
        ) STORED,
    alvo_id CHAR(36)
        GENERATED ALWAYS AS (
            COALESCE(compra_id, nota_fiscal_id, lancamento_id)
        ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_cobranca_email_idempotencia UNIQUE (idempotency_key),
    CONSTRAINT uq_fin_cobranca_email_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT uq_fin_cobranca_email_regra_ocorrencia
        UNIQUE (regra_id, alvo_tipo, alvo_id, ocorrencia_prevista_em),
    CONSTRAINT uq_fin_cobranca_email_provider
        UNIQUE (provider, provider_message_id),
    CONSTRAINT uq_fin_cobranca_email_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_cobranca_email_regra
        FOREIGN KEY (regra_id, obra_id)
        REFERENCES finance_regra_cobranca(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_modelo
        FOREIGN KEY (modelo_email_id, obra_id)
        REFERENCES finance_modelo_email(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_remetente
        FOREIGN KEY (perfil_remetente_id)
        REFERENCES finance_email_perfil_remetente(id),
    CONSTRAINT fk_fin_cobranca_email_reply
        FOREIGN KEY (reply_to_permitido_id, obra_id)
        REFERENCES finance_email_reply_to_permitido(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_fornecedor
        FOREIGN KEY (fornecedor_id, obra_id)
        REFERENCES finance_fornecedor_obra(fornecedor_id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_compra
        FOREIGN KEY (compra_id, obra_id)
        REFERENCES finance_compra(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_nf
        FOREIGN KEY (nota_fiscal_id, obra_id)
        REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_lancamento
        FOREIGN KEY (lancamento_id, obra_id)
        REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_responsavel
        FOREIGN KEY (responsavel_id) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_cobranca_email_preview
        FOREIGN KEY (previsualizacao_confirmada_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_cobranca_email_cancelada
        FOREIGN KEY (cancelada_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_cobranca_email_criado
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_fin_cobranca_email_atualizado
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_cobranca_email_modo
        CHECK (modo IN ('MANUAL', 'AGENDADA', 'AUTOMATICA')),
    CONSTRAINT chk_fin_cobranca_email_status
        CHECK (status IN (
            'RASCUNHO', 'PREVISUALIZADA', 'AGENDADA', 'NA_FILA',
            'ENVIANDO', 'ENVIADA', 'FALHOU', 'ENTREGUE', 'CANCELADA'
        )),
    CONSTRAINT chk_fin_cobranca_email_destinatario
        CHECK (destinatario LIKE '%_@_%._%'
            AND destinatario NOT LIKE '% %'),
    CONSTRAINT chk_fin_cobranca_email_alvo
        CHECK (
            (compra_id IS NOT NULL)
            + (nota_fiscal_id IS NOT NULL)
            + (lancamento_id IS NOT NULL) = 1
        ),
    CONSTRAINT chk_fin_cobranca_email_agendamento
        CHECK (modo <> 'AGENDADA' OR agendada_para IS NOT NULL),
    CONSTRAINT chk_fin_cobranca_email_automatica
        CHECK (
            modo <> 'AUTOMATICA'
            OR (regra_id IS NOT NULL
                AND previsualizacao_hash IS NOT NULL
                AND previsualizacao_confirmada_por IS NOT NULL
                AND previsualizacao_confirmada_em IS NOT NULL)
        ),
    CONSTRAINT chk_fin_cobranca_email_preview
        CHECK (
            status = 'RASCUNHO'
            OR (previsualizacao_hash IS NOT NULL
                AND previsualizacao_confirmada_por IS NOT NULL
                AND previsualizacao_confirmada_em IS NOT NULL)
        ),
    CONSTRAINT chk_fin_cobranca_email_lock
        CHECK (
            (status = 'ENVIANDO' AND lock_token IS NOT NULL
                AND lock_ate IS NOT NULL)
            OR status <> 'ENVIANDO'
        ),
    CONSTRAINT chk_fin_cobranca_email_envio
        CHECK (
            (status IN ('ENVIADA', 'ENTREGUE')
                AND provider IS NOT NULL
                AND provider_message_id IS NOT NULL
                AND enviada_em IS NOT NULL)
            OR status NOT IN ('ENVIADA', 'ENTREGUE')
        ),
    CONSTRAINT chk_fin_cobranca_email_entrega
        CHECK ((status = 'ENTREGUE' AND entregue_em IS NOT NULL)
            OR status <> 'ENTREGUE'),
    CONSTRAINT chk_fin_cobranca_email_falha
        CHECK (
            (status = 'FALHOU' AND erro_categoria IS NOT NULL
                AND erro_sanitizado IS NOT NULL)
            OR status <> 'FALHOU'
        ),
    CONSTRAINT chk_fin_cobranca_email_cancelamento
        CHECK (
            (status = 'CANCELADA' AND cancelada_por IS NOT NULL
                AND cancelada_em IS NOT NULL
                AND motivo_cancelamento IS NOT NULL)
            OR (status <> 'CANCELADA' AND cancelada_por IS NULL
                AND cancelada_em IS NULL)
        ),
    CONSTRAINT chk_fin_cobranca_email_tentativas CHECK (tentativas >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_cobranca_email_fila
    ON finance_cobranca_email (
        status, proxima_tentativa_em, agendada_para, lock_ate
    );
CREATE INDEX idx_fin_cobranca_email_obra_status
    ON finance_cobranca_email (obra_id, status, ocorrencia_prevista_em);
CREATE INDEX idx_fin_cobranca_email_fornecedor
    ON finance_cobranca_email (obra_id, fornecedor_id, status);
CREATE INDEX idx_fin_cobranca_email_alvo
    ON finance_cobranca_email (obra_id, alvo_tipo, alvo_id);

-- Attempts are append-only outcome records. Claim/lease state lives on the
-- charge so an attempt row never needs to be rewritten after insertion.
CREATE TABLE finance_cobranca_tentativa (
    id CHAR(36) NOT NULL,
    cobranca_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    numero_tentativa INT NOT NULL,
    idempotency_key VARCHAR(200)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resultado VARCHAR(20) NOT NULL,
    provider VARCHAR(80) NULL,
    provider_message_id VARCHAR(255) NULL,
    erro_categoria VARCHAR(80) NULL,
    erro_sanitizado VARCHAR(1000) NULL,
    proxima_tentativa_em DATETIME(6) NULL,
    iniciada_em DATETIME(6) NOT NULL,
    concluida_em DATETIME(6) NOT NULL,
    criado_por CHAR(36) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_cobranca_tentativa_numero
        UNIQUE (cobranca_id, numero_tentativa),
    CONSTRAINT uq_fin_cobranca_tentativa_idempotencia
        UNIQUE (idempotency_key),
    CONSTRAINT uq_fin_cobranca_tentativa_provider
        UNIQUE (provider, provider_message_id),
    CONSTRAINT fk_fin_cobranca_tentativa_cobranca
        FOREIGN KEY (cobranca_id, obra_id)
        REFERENCES finance_cobranca_email(id, obra_id),
    CONSTRAINT fk_fin_cobranca_tentativa_criado
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_cobranca_tentativa_numero
        CHECK (numero_tentativa > 0),
    CONSTRAINT chk_fin_cobranca_tentativa_resultado
        CHECK (resultado IN ('ENVIADA', 'FALHOU')),
    CONSTRAINT chk_fin_cobranca_tentativa_tempo
        CHECK (concluida_em >= iniciada_em),
    CONSTRAINT chk_fin_cobranca_tentativa_saida
        CHECK (
            (resultado = 'ENVIADA' AND provider IS NOT NULL
                AND provider_message_id IS NOT NULL
                AND erro_categoria IS NULL AND erro_sanitizado IS NULL)
            OR (resultado = 'FALHOU' AND provider_message_id IS NULL
                AND erro_categoria IS NOT NULL
                AND erro_sanitizado IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_cobranca_tentativa_cobranca
    ON finance_cobranca_tentativa (cobranca_id, numero_tentativa);
CREATE INDEX idx_fin_cobranca_tentativa_retry
    ON finance_cobranca_tentativa (resultado, proxima_tentativa_em);

-- Delivery events are optional and may only be inserted after an authenticated
-- provider callback. Only the callback hash is stored, never its raw payload.
CREATE TABLE finance_cobranca_evento_entrega (
    id CHAR(36) NOT NULL,
    cobranca_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    tentativa_id CHAR(36) NULL,
    provider VARCHAR(80) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    tipo_evento VARCHAR(30) NOT NULL,
    payload_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ocorrido_em DATETIME(6) NOT NULL,
    autenticado_em DATETIME(6) NOT NULL,
    recebido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_cobranca_evento_provider
        UNIQUE (provider, provider_event_id),
    CONSTRAINT fk_fin_cobranca_evento_cobranca
        FOREIGN KEY (cobranca_id, obra_id)
        REFERENCES finance_cobranca_email(id, obra_id),
    CONSTRAINT fk_fin_cobranca_evento_tentativa
        FOREIGN KEY (tentativa_id)
        REFERENCES finance_cobranca_tentativa(id),
    CONSTRAINT chk_fin_cobranca_evento_tipo
        CHECK (tipo_evento IN (
            'ACEITA', 'ENTREGUE', 'FALHOU', 'REJEITADA'
        )),
    CONSTRAINT chk_fin_cobranca_evento_autenticacao
        CHECK (autenticado_em <= recebido_em)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_cobranca_evento_mensagem
    ON finance_cobranca_evento_entrega (
        provider, provider_message_id, ocorrido_em
    );

CREATE TABLE finance_cobranca_email_historico (
    id CHAR(36) NOT NULL,
    cobranca_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    acao VARCHAR(40) NOT NULL,
    ator_id CHAR(36) NOT NULL,
    origem VARCHAR(30) NOT NULL,
    dispositivo_id VARCHAR(120) NULL,
    client_mutation_id VARCHAR(120) NULL,
    correlacao_id VARCHAR(120) NULL,
    estado_anterior_json JSON NULL,
    estado_novo_json JSON NOT NULL,
    resultado VARCHAR(30) NOT NULL,
    erro_sanitizado VARCHAR(1000) NULL,
    ocorrido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_cobranca_historico_mutacao
        UNIQUE (ator_id, client_mutation_id),
    CONSTRAINT fk_fin_cobranca_historico_cobranca
        FOREIGN KEY (cobranca_id, obra_id)
        REFERENCES finance_cobranca_email(id, obra_id),
    CONSTRAINT fk_fin_cobranca_historico_ator
        FOREIGN KEY (ator_id) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_cobranca_historico_origem
        CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')),
    CONSTRAINT chk_fin_cobranca_historico_resultado
        CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_cobranca_historico_obra
    ON finance_cobranca_email_historico (obra_id, ocorrido_em, id);

-- CORTEX object, relation and event types are intentionally open catalogs.
-- COBRANCA_EMAIL, MODELO_EMAIL, REGRA_COBRANCA and their related edges are
-- projected additively by the canonical domain projector; this migration does
-- not rewrite or seed legacy ontology tables.
