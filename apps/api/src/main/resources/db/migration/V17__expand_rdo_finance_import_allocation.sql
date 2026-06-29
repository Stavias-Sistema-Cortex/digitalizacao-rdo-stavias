CREATE TABLE item_contratual (
    id CHAR(36) NOT NULL,

    obra_id CHAR(36) NOT NULL,
    contrato VARCHAR(80) NOT NULL,
    codigo_item VARCHAR(120) NOT NULL,
    descricao VARCHAR(500) NOT NULL,
    unidade_medida VARCHAR(30) NOT NULL,

    quantidade_contratada DECIMAL(18,3) NOT NULL,
    preco_unitario DECIMAL(18,4) NOT NULL,
    valor_total DECIMAL(18,2) NOT NULL,

    vigencia_inicio DATE,
    vigencia_fim DATE,

    versao INT NOT NULL DEFAULT 1,
    status VARCHAR(40) NOT NULL DEFAULT 'ATIVO',
    fonte VARCHAR(80) NOT NULL DEFAULT 'MANUAL',

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_item_contratual_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_item_contratual_versao
        UNIQUE (obra_id, contrato, codigo_item, versao),

    CONSTRAINT chk_item_contratual_status
        CHECK (status IN ('ATIVO', 'INATIVO', 'SUBSTITUIDO', 'CANCELADO')),

    CONSTRAINT chk_item_contratual_quantidade
        CHECK (quantidade_contratada >= 0),

    CONSTRAINT chk_item_contratual_preco
        CHECK (preco_unitario >= 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_item_contratual_obra_status
    ON item_contratual (obra_id, status);

CREATE INDEX idx_item_contratual_codigo
    ON item_contratual (codigo_item);

CREATE INDEX idx_item_contratual_vigencia
    ON item_contratual (obra_id, vigencia_inicio, vigencia_fim);


CREATE TABLE importacao_rdo (
    id CHAR(36) NOT NULL,

    nome_arquivo VARCHAR(255) NOT NULL,
    hash_arquivo CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tamanho_bytes BIGINT NOT NULL,
    content_type VARCHAR(120),

    layout_detectado VARCHAR(120),
    status VARCHAR(40) NOT NULL DEFAULT 'ANALISADA',
    estrategia_duplicidade VARCHAR(40) NOT NULL DEFAULT 'IGNORAR_DUPLICADO',
    versao_mapeamento VARCHAR(80) NOT NULL DEFAULT 'RDO_IMPORT_V1',

    usuario_id VARCHAR(120),

    quantidade_processada INT NOT NULL DEFAULT 0,
    quantidade_importada INT NOT NULL DEFAULT 0,
    quantidade_rejeitada INT NOT NULL DEFAULT 0,
    quantidade_duplicada INT NOT NULL DEFAULT 0,

    arquivo_bytes LONGBLOB,
    relatorio_json JSON,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    confirmado_em DATETIME(6),

    PRIMARY KEY (id),

    CONSTRAINT chk_importacao_rdo_status
        CHECK (status IN (
            'ANALISADA',
            'AGUARDANDO_CORRECAO',
            'VALIDADA',
            'SIMULADA',
            'CONFIRMADA',
            'CONFIRMADA_COM_ERROS',
            'REJEITADA',
            'ERRO'
        )),

    CONSTRAINT chk_importacao_rdo_estrategia
        CHECK (estrategia_duplicidade IN (
            'IGNORAR_DUPLICADO',
            'ATUALIZAR_EXISTENTE',
            'CRIAR_NOVA_VERSAO',
            'REJEITAR'
        ))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_importacao_rdo_hash
    ON importacao_rdo (hash_arquivo);

CREATE INDEX idx_importacao_rdo_status
    ON importacao_rdo (status, criado_em);


CREATE TABLE importacao_rdo_linha (
    id CHAR(36) NOT NULL,
    importacao_id CHAR(36) NOT NULL,

    aba VARCHAR(120),
    numero_linha INT NOT NULL,

    payload_original_json JSON NOT NULL,
    payload_normalizado_json JSON NOT NULL,

    layout_detectado VARCHAR(120),
    chave_negocio CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,

    status VARCHAR(40) NOT NULL DEFAULT 'VALIDA',

    obra_id CHAR(36),
    numero_rdo VARCHAR(80),
    data_rdo DATE,
    rdo_id CHAR(36),

    quantidade_erros INT NOT NULL DEFAULT 0,
    quantidade_warnings INT NOT NULL DEFAULT 0,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_importacao_rdo_linha_importacao
        FOREIGN KEY (importacao_id)
        REFERENCES importacao_rdo(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_importacao_rdo_linha_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_importacao_rdo_linha_rdo
        FOREIGN KEY (rdo_id)
        REFERENCES rdo(id)
        ON DELETE SET NULL,

    CONSTRAINT uq_importacao_rdo_linha
        UNIQUE (importacao_id, aba, numero_linha),

    CONSTRAINT chk_importacao_rdo_linha_status
        CHECK (status IN (
            'VALIDA',
            'INVALIDA',
            'IMPORTADA',
            'REJEITADA',
            'DUPLICADA',
            'ATUALIZADA'
        ))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_importacao_rdo_linha_status
    ON importacao_rdo_linha (importacao_id, status);

CREATE INDEX idx_importacao_rdo_linha_chave
    ON importacao_rdo_linha (chave_negocio);

CREATE INDEX idx_importacao_rdo_linha_rdo
    ON importacao_rdo_linha (rdo_id);


CREATE TABLE importacao_rdo_erro (
    id CHAR(36) NOT NULL,
    importacao_id CHAR(36) NOT NULL,
    linha_id CHAR(36),

    aba VARCHAR(120),
    numero_linha INT,
    campo VARCHAR(120) NOT NULL,
    valor_recebido VARCHAR(1000),
    valor_esperado VARCHAR(1000),
    mensagem VARCHAR(1000) NOT NULL,
    sugestao VARCHAR(1000),
    severidade VARCHAR(20) NOT NULL DEFAULT 'ERRO',

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_importacao_rdo_erro_importacao
        FOREIGN KEY (importacao_id)
        REFERENCES importacao_rdo(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_importacao_rdo_erro_linha
        FOREIGN KEY (linha_id)
        REFERENCES importacao_rdo_linha(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_importacao_rdo_erro_severidade
        CHECK (severidade IN ('ERRO', 'WARNING'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_importacao_rdo_erro_linha
    ON importacao_rdo_erro (linha_id, severidade);


CREATE TABLE importacao_rdo_mapeamento (
    id CHAR(36) NOT NULL,
    importacao_id CHAR(36) NOT NULL,

    versao_mapeamento VARCHAR(80) NOT NULL,
    layout_detectado VARCHAR(120),
    coluna_origem VARCHAR(255) NOT NULL,
    campo_destino VARCHAR(120) NOT NULL,
    obrigatorio TINYINT(1) NOT NULL DEFAULT 0,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_importacao_rdo_mapeamento_importacao
        FOREIGN KEY (importacao_id)
        REFERENCES importacao_rdo(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_importacao_rdo_mapeamento
        UNIQUE (importacao_id, coluna_origem, campo_destino)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


ALTER TABLE rdo
    ADD COLUMN fonte_criacao VARCHAR(40) NOT NULL DEFAULT 'MANUAL'
        AFTER status,
    ADD COLUMN importacao_rdo_id CHAR(36) NULL
        AFTER fonte_criacao,
    ADD COLUMN fonte_arquivo VARCHAR(255) NULL
        AFTER importacao_rdo_id,
    ADD COLUMN aba_origem VARCHAR(120) NULL
        AFTER fonte_arquivo,
    ADD COLUMN linha_origem INT NULL
        AFTER aba_origem,
    ADD COLUMN data_original DATE NULL
        AFTER linha_origem,
    ADD COLUMN data_importacao DATETIME(6) NULL
        AFTER data_original,
    ADD COLUMN usuario_importacao VARCHAR(120) NULL
        AFTER data_importacao,
    ADD COLUMN versao_mapeamento VARCHAR(80) NULL
        AFTER usuario_importacao,
    ADD COLUMN chave_negocio_importacao CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER versao_mapeamento,
    ADD COLUMN estado_receita VARCHAR(40) NOT NULL DEFAULT 'PRODUCAO_REGISTRADA'
        AFTER chave_negocio_importacao;

ALTER TABLE rdo
    ADD CONSTRAINT fk_rdo_importacao_rdo
        FOREIGN KEY (importacao_rdo_id)
        REFERENCES importacao_rdo(id)
        ON DELETE RESTRICT;

ALTER TABLE rdo
    ADD CONSTRAINT chk_rdo_fonte_criacao
        CHECK (fonte_criacao IN (
            'MANUAL',
            'OFFLINE_SYNC',
            'IMPORTACAO_HISTORICA',
            'SISTEMA'
        ));

ALTER TABLE rdo
    ADD CONSTRAINT chk_rdo_estado_receita
        CHECK (estado_receita IN (
            'PRODUCAO_REGISTRADA',
            'PRODUCAO_VALIDADA',
            'RECEITA_ESTIMADA',
            'RECEITA_MEDIDA',
            'RECEITA_APROVADA',
            'RECEITA_FATURADA',
            'RECEITA_RECEBIDA'
        ));

CREATE INDEX idx_rdo_importacao
    ON rdo (importacao_rdo_id);

CREATE INDEX idx_rdo_chave_importacao
    ON rdo (chave_negocio_importacao);

CREATE INDEX idx_rdo_fonte_criacao
    ON rdo (fonte_criacao, data_rdo);


CREATE TABLE execucao_servico_rdo (
    id CHAR(36) NOT NULL,

    rdo_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    programacao_id CHAR(36),

    servico_nome VARCHAR(255) NOT NULL,
    item_contratual_id CHAR(36),

    quantidade_executada DECIMAL(18,3) NOT NULL,
    unidade_medida VARCHAR(30) NOT NULL,

    trecho_inicial VARCHAR(80),
    trecho_final VARCHAR(80),
    localizacao VARCHAR(255),

    data_execucao DATE NOT NULL,
    turno VARCHAR(40),

    status_validacao VARCHAR(40) NOT NULL DEFAULT 'REGISTRADA',
    estado_receita VARCHAR(40) NOT NULL DEFAULT 'PRODUCAO_REGISTRADA',

    receita_operacional_estimativa DECIMAL(18,2),
    custo_realizado DECIMAL(18,2),

    retrabalho TINYINT(1) NOT NULL DEFAULT 0,
    producao_rejeitada TINYINT(1) NOT NULL DEFAULT 0,
    cancelada TINYINT(1) NOT NULL DEFAULT 0,

    fonte VARCHAR(80) NOT NULL DEFAULT 'RDO',
    chave_execucao CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    observacoes TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT fk_execucao_servico_rdo_rdo
        FOREIGN KEY (rdo_id)
        REFERENCES rdo(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_execucao_servico_rdo_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_execucao_servico_rdo_programacao
        FOREIGN KEY (programacao_id)
        REFERENCES programacao_operacional(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_execucao_servico_rdo_item
        FOREIGN KEY (item_contratual_id)
        REFERENCES item_contratual(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_execucao_servico_rdo_chave
        UNIQUE (chave_execucao),

    CONSTRAINT chk_execucao_servico_rdo_status
        CHECK (status_validacao IN (
            'REGISTRADA',
            'VALIDADA',
            'REJEITADA',
            'CANCELADA'
        )),

    CONSTRAINT chk_execucao_servico_rdo_estado_receita
        CHECK (estado_receita IN (
            'PRODUCAO_REGISTRADA',
            'PRODUCAO_VALIDADA',
            'RECEITA_ESTIMADA',
            'RECEITA_MEDIDA',
            'RECEITA_APROVADA',
            'RECEITA_FATURADA',
            'RECEITA_RECEBIDA'
        )),

    CONSTRAINT chk_execucao_servico_rdo_quantidade
        CHECK (quantidade_executada >= 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_execucao_servico_obra_data
    ON execucao_servico_rdo (obra_id, data_execucao);

CREATE INDEX idx_execucao_servico_rdo
    ON execucao_servico_rdo (rdo_id);

CREATE INDEX idx_execucao_servico_item
    ON execucao_servico_rdo (item_contratual_id);

CREATE INDEX idx_execucao_servico_status
    ON execucao_servico_rdo (status_validacao, estado_receita);


CREATE TABLE alocacao_colaborador (
    id CHAR(36) NOT NULL,

    colaborador_id CHAR(36) NOT NULL,
    data_alocacao DATE NOT NULL,
    hora_inicio TIME,
    hora_fim TIME,
    minutos INT NOT NULL,
    percentual_dia DECIMAL(9,6),

    obra_id CHAR(36) NOT NULL,
    equipe VARCHAR(120),
    servico_nome VARCHAR(255),
    rdo_id CHAR(36),
    programacao_id CHAR(36),

    turno VARCHAR(40),
    funcao VARCHAR(120),
    centro_custo VARCHAR(120),
    tipo_alocacao VARCHAR(40) NOT NULL DEFAULT 'TRABALHO',
    fonte VARCHAR(80) NOT NULL DEFAULT 'RDO',
    status VARCHAR(40) NOT NULL DEFAULT 'REGISTRADA',

    custo_hora DECIMAL(18,4),
    custo_total DECIMAL(18,2),

    observacoes TEXT,
    criado_por VARCHAR(120),
    validado_por VARCHAR(120),
    validado_em DATETIME(6),

    chave_alocacao CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT fk_alocacao_colaborador_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_alocacao_colaborador_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_alocacao_colaborador_rdo
        FOREIGN KEY (rdo_id)
        REFERENCES rdo(id)
        ON DELETE SET NULL,

    CONSTRAINT fk_alocacao_colaborador_programacao
        FOREIGN KEY (programacao_id)
        REFERENCES programacao_operacional(id)
        ON DELETE SET NULL,

    CONSTRAINT uq_alocacao_colaborador_chave
        UNIQUE (chave_alocacao),

    CONSTRAINT chk_alocacao_colaborador_tipo
        CHECK (tipo_alocacao IN (
            'TRABALHO',
            'DESLOCAMENTO',
            'TREINAMENTO',
            'MANUTENCAO',
            'APOIO',
            'ADMINISTRATIVO',
            'AFASTAMENTO',
            'OUTRO'
        )),

    CONSTRAINT chk_alocacao_colaborador_status
        CHECK (status IN (
            'REGISTRADA',
            'VALIDADA',
            'CONFLITO',
            'CANCELADA'
        )),

    CONSTRAINT chk_alocacao_colaborador_minutos
        CHECK (minutos >= 0 AND minutos <= 1440),

    CONSTRAINT chk_alocacao_colaborador_percentual
        CHECK (percentual_dia IS NULL OR (percentual_dia >= 0 AND percentual_dia <= 1))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_alocacao_colaborador_data
    ON alocacao_colaborador (colaborador_id, data_alocacao);

CREATE INDEX idx_alocacao_obra_data
    ON alocacao_colaborador (obra_id, data_alocacao);

CREATE INDEX idx_alocacao_rdo
    ON alocacao_colaborador (rdo_id);

CREATE INDEX idx_alocacao_status
    ON alocacao_colaborador (status, data_alocacao);


CREATE TABLE conflito_alocacao_colaborador (
    id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    data_referencia DATE NOT NULL,
    tipo_conflito VARCHAR(80) NOT NULL,
    descricao TEXT NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'ABERTO',
    fontes_json JSON NOT NULL,
    decisao_json JSON,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolvido_em DATETIME(6),
    resolvido_por VARCHAR(120),

    PRIMARY KEY (id),

    CONSTRAINT fk_conflito_alocacao_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_conflito_alocacao_status
        CHECK (status IN ('ABERTO', 'RESOLVIDO', 'IGNORADO'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_conflito_alocacao_colaborador_data
    ON conflito_alocacao_colaborador (colaborador_id, data_referencia, status);


CREATE TABLE jornada_planejada (
    id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    data_jornada DATE NOT NULL,
    minutos_previstos INT NOT NULL,
    hora_inicio TIME,
    hora_fim TIME,
    origem VARCHAR(80) NOT NULL DEFAULT 'MANUAL',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_jornada_planejada_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_jornada_planejada_colaborador_data
        UNIQUE (colaborador_id, data_jornada),

    CONSTRAINT chk_jornada_planejada_minutos
        CHECK (minutos_previstos >= 0 AND minutos_previstos <= 1440)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


CREATE TABLE registro_presenca (
    id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    data_presenca DATE NOT NULL,
    minutos_registrados INT NOT NULL DEFAULT 0,
    origem VARCHAR(80) NOT NULL DEFAULT 'OPERACIONAL',
    status VARCHAR(40) NOT NULL DEFAULT 'REGISTRADA',
    rdo_id CHAR(36),
    observacoes TEXT,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_registro_presenca_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_registro_presenca_rdo
        FOREIGN KEY (rdo_id)
        REFERENCES rdo(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_registro_presenca_status
        CHECK (status IN (
            'REGISTRADA',
            'VALIDADA',
            'DIVERGENTE',
            'CANCELADA'
        )),

    CONSTRAINT chk_registro_presenca_minutos
        CHECK (minutos_registrados >= 0 AND minutos_registrados <= 1440)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_registro_presenca_colaborador_data
    ON registro_presenca (colaborador_id, data_presenca);


CREATE TABLE ocorrencia_frequencia (
    id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    data_ocorrencia DATE NOT NULL,
    tipo VARCHAR(40) NOT NULL,
    minutos INT NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'REGISTRADA',
    origem VARCHAR(80) NOT NULL DEFAULT 'OPERACIONAL',
    rdo_id CHAR(36),
    justificativa TEXT,
    criado_por VARCHAR(120),
    validado_por VARCHAR(120),
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    validado_em DATETIME(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_ocorrencia_frequencia_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_ocorrencia_frequencia_rdo
        FOREIGN KEY (rdo_id)
        REFERENCES rdo(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_ocorrencia_frequencia_tipo
        CHECK (tipo IN (
            'PRESENTE',
            'FALTA',
            'FALTA_JUSTIFICADA',
            'ATRASO',
            'SAIDA_ANTECIPADA',
            'HORA_EXTRA',
            'FOLGA',
            'FERIAS',
            'ATESTADO',
            'AFASTAMENTO',
            'TREINAMENTO',
            'SEM_REGISTRO',
            'DIVERGENCIA'
        )),

    CONSTRAINT chk_ocorrencia_frequencia_status
        CHECK (status IN (
            'REGISTRADA',
            'JUSTIFICADA',
            'VALIDADA',
            'CANCELADA'
        )),

    CONSTRAINT chk_ocorrencia_frequencia_minutos
        CHECK (minutos >= 0 AND minutos <= 1440)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_ocorrencia_frequencia_colaborador_data
    ON ocorrencia_frequencia (colaborador_id, data_ocorrencia, tipo);


CREATE TABLE politica_banco_horas (
    id CHAR(36) NOT NULL,
    empresa VARCHAR(120),
    contrato VARCHAR(80),
    categoria VARCHAR(120),
    colaborador_id CHAR(36),
    periodo_inicio DATE NOT NULL,
    periodo_fim DATE,
    regra_json JSON NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'ATIVA',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_politica_banco_horas_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_politica_banco_horas_status
        CHECK (status IN ('ATIVA', 'INATIVA'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


CREATE TABLE saldo_banco_horas (
    id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    periodo_inicio DATE NOT NULL,
    periodo_fim DATE NOT NULL,
    minutos_saldo_anterior INT NOT NULL DEFAULT 0,
    minutos_credito INT NOT NULL DEFAULT 0,
    minutos_debito INT NOT NULL DEFAULT 0,
    minutos_ajuste INT NOT NULL DEFAULT 0,
    minutos_saldo_atual INT NOT NULL DEFAULT 0,
    politica_id CHAR(36),
    calculado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_saldo_banco_horas_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_saldo_banco_horas_politica
        FOREIGN KEY (politica_id)
        REFERENCES politica_banco_horas(id)
        ON DELETE SET NULL,

    CONSTRAINT uq_saldo_banco_horas_periodo
        UNIQUE (colaborador_id, periodo_inicio, periodo_fim)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


CREATE TABLE ajuste_banco_horas (
    id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    saldo_id CHAR(36),
    data_ajuste DATE NOT NULL,
    minutos_anterior INT NOT NULL,
    minutos_novo INT NOT NULL,
    minutos_delta INT NOT NULL,
    motivo TEXT NOT NULL,
    usuario_id VARCHAR(120) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDENTE',
    aprovado_por VARCHAR(120),
    aprovado_em DATETIME(6),
    evento_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_ajuste_banco_horas_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_ajuste_banco_horas_saldo
        FOREIGN KEY (saldo_id)
        REFERENCES saldo_banco_horas(id)
        ON DELETE SET NULL,

    CONSTRAINT fk_ajuste_banco_horas_evento
        FOREIGN KEY (evento_id)
        REFERENCES cortex_evento_operacional(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_ajuste_banco_horas_status
        CHECK (status IN ('PENDENTE', 'APROVADO', 'REJEITADO', 'CANCELADO'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_ajuste_banco_horas_colaborador_data
    ON ajuste_banco_horas (colaborador_id, data_ajuste);


CREATE TABLE previsao_financeira_snapshot (
    id CHAR(36) NOT NULL,

    obra_id CHAR(36) NOT NULL,
    codigo_obra VARCHAR(80) NOT NULL,
    data_referencia DATE NOT NULL,
    executado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    versao_modelo VARCHAR(40) NOT NULL,
    versao_premissas VARCHAR(80) NOT NULL,
    status_execucao VARCHAR(40) NOT NULL,
    status_calculo VARCHAR(40) NOT NULL,
    tipo_disparo VARCHAR(40) NOT NULL DEFAULT 'MANUAL',
    evento_origem_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    chave_idempotencia CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    receita_estimada_dia DECIMAL(18,2),
    receita_estimada_acumulada DECIMAL(18,2),
    receita_validada_acumulada DECIMAL(18,2),
    receita_medida DECIMAL(18,2),
    receita_aprovada DECIMAL(18,2),
    receita_faturada DECIMAL(18,2),
    receita_recebida DECIMAL(18,2),
    receita_prevista_final DECIMAL(18,2),

    custo_realizado DECIMAL(18,2),
    custo_comprometido DECIMAL(18,2),
    custo_previsto_final DECIMAL(18,2),

    margem_atual DECIMAL(18,2),
    margem_prevista DECIMAL(18,2),
    margem_percentual DECIMAL(12,6),

    producao_planejada DECIMAL(18,3),
    producao_realizada DECIMAL(18,3),
    backlog_contratual DECIMAL(18,2),
    receita_em_risco DECIMAL(18,2),

    confianca DECIMAL(9,6),
    qualidade_dados DECIMAL(9,6),

    warnings_json JSON NOT NULL,
    inputs_json JSON NOT NULL,
    fontes_json JSON NOT NULL,
    erro_execucao TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT fk_previsao_financeira_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_previsao_financeira_evento_origem
        FOREIGN KEY (evento_origem_id)
        REFERENCES cortex_evento_operacional(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_previsao_financeira_idempotencia
        UNIQUE (chave_idempotencia),

    CONSTRAINT chk_previsao_financeira_status_execucao
        CHECK (status_execucao IN (
            'NAO_EXECUTADO',
            'SEM_SNAPSHOT',
            'DADOS_INSUFICIENTES',
            'CALCULADO',
            'ERRO_DE_EXECUCAO'
        )),

    CONSTRAINT chk_previsao_financeira_status_calculo
        CHECK (status_calculo IN (
            'NAO_CALIBRADO',
            'CALIBRADO',
            'DADOS_INSUFICIENTES'
        )),

    CONSTRAINT chk_previsao_financeira_tipo_disparo
        CHECK (tipo_disparo IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_previsao_financeira_obra_execucao
    ON previsao_financeira_snapshot (obra_id, executado_em, criado_em, id);

CREATE INDEX idx_previsao_financeira_obra_referencia
    ON previsao_financeira_snapshot (obra_id, data_referencia);

CREATE INDEX idx_previsao_financeira_status
    ON previsao_financeira_snapshot (status_execucao, executado_em);


ALTER TABLE pdoc_snapshot
    ADD COLUMN receita_estimada_acumulada DECIMAL(18,2) NULL
        AFTER nivel_risco,
    ADD COLUMN receita_validada_acumulada DECIMAL(18,2) NULL
        AFTER receita_estimada_acumulada,
    ADD COLUMN receita_medida DECIMAL(18,2) NULL
        AFTER receita_validada_acumulada,
    ADD COLUMN receita_faturada DECIMAL(18,2) NULL
        AFTER receita_medida,
    ADD COLUMN receita_prevista_final DECIMAL(18,2) NULL
        AFTER receita_faturada,
    ADD COLUMN custo_realizado_financeiro DECIMAL(18,2) NULL
        AFTER receita_prevista_final,
    ADD COLUMN custo_comprometido_financeiro DECIMAL(18,2) NULL
        AFTER custo_realizado_financeiro,
    ADD COLUMN custo_previsto_final DECIMAL(18,2) NULL
        AFTER custo_comprometido_financeiro,
    ADD COLUMN margem_atual DECIMAL(18,2) NULL
        AFTER custo_previsto_final,
    ADD COLUMN margem_prevista DECIMAL(18,2) NULL
        AFTER margem_atual,
    ADD COLUMN margem_percentual DECIMAL(12,6) NULL
        AFTER margem_prevista,
    ADD COLUMN receita_em_risco DECIMAL(18,2) NULL
        AFTER margem_percentual,
    ADD COLUMN qualidade_dados DECIMAL(9,6) NULL
        AFTER receita_em_risco,
    ADD COLUMN status_modelo VARCHAR(40) NULL
        AFTER qualidade_dados;

CREATE INDEX idx_pdoc_snapshot_margem
    ON pdoc_snapshot (obra_id, margem_percentual);
