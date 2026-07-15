CREATE TABLE finance_documento_extracao_job (
    id CHAR(36) NOT NULL,
    stored_object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    obra_id CHAR(36) NOT NULL,
    client_mutation_id VARCHAR(120) NOT NULL,
    formato VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
    extrator VARCHAR(80) NULL,
    extrator_versao VARCHAR(40) NULL,
    sha256_cliente CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sha256_servidor CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resultado_json JSON NULL,
    avisos_json JSON NULL,
    autorizacao_fiscal_status VARCHAR(30) NOT NULL DEFAULT 'NAO_VERIFICADA',
    erro_sanitizado VARCHAR(1000) NULL,
    criado_por CHAR(36) NOT NULL,
    dispositivo_id VARCHAR(120) NULL,
    correlacao_id VARCHAR(120) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    iniciado_em DATETIME(6) NULL,
    concluido_em DATETIME(6) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_doc_extracao_mutacao
        UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT uq_fin_doc_extracao_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_doc_extracao_objeto
        FOREIGN KEY (stored_object_id, obra_id)
        REFERENCES stored_object(id, obra_id),
    CONSTRAINT fk_fin_doc_extracao_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_fin_doc_extracao_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_fin_doc_extracao_formato
        CHECK (formato IN ('XML', 'PDF', 'IMAGEM')),
    CONSTRAINT chk_fin_doc_extracao_status
        CHECK (status IN (
            'PENDENTE', 'PROCESSANDO', 'EXTRAIDO',
            'REVISAO_NECESSARIA', 'FALHA'
        )),
    CONSTRAINT chk_fin_doc_extracao_autorizacao
        CHECK (autorizacao_fiscal_status IN ('NAO_VERIFICADA')),
    CONSTRAINT chk_fin_doc_extracao_hash_cliente
        CHECK (
            sha256_cliente IS NULL
            OR sha256_cliente REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_fin_doc_extracao_hash_servidor
        CHECK (sha256_servidor REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_fin_doc_extracao_tempos
        CHECK (
            (status = 'PENDENTE' AND iniciado_em IS NULL AND concluido_em IS NULL)
            OR (status = 'PROCESSANDO' AND iniciado_em IS NOT NULL AND concluido_em IS NULL)
            OR (
                status IN ('EXTRAIDO', 'REVISAO_NECESSARIA', 'FALHA')
                AND iniciado_em IS NOT NULL
                AND concluido_em IS NOT NULL
            )
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_doc_extracao_objeto
    ON finance_documento_extracao_job (
        stored_object_id, status, criado_em, id
    );
CREATE INDEX idx_fin_doc_extracao_obra
    ON finance_documento_extracao_job (obra_id, criado_em, id);

CREATE TABLE finance_documento_extracao_candidato (
    id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    campo VARCHAR(50) NOT NULL,
    ordem SMALLINT NOT NULL DEFAULT 0,
    valor_normalizado_json JSON NOT NULL,
    texto_original VARCHAR(2000) NULL,
    extrator VARCHAR(80) NOT NULL,
    extrator_versao VARCHAR(40) NOT NULL,
    localizacao VARCHAR(500) NULL,
    confianca DECIMAL(7,6) NOT NULL,
    validacao_status VARCHAR(30) NOT NULL,
    validacao_detalhe VARCHAR(1000) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_fin_doc_extracao_candidato
        UNIQUE (job_id, campo, ordem),
    CONSTRAINT fk_fin_doc_extracao_candidato_job
        FOREIGN KEY (job_id)
        REFERENCES finance_documento_extracao_job(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_fin_doc_extracao_campo
        CHECK (campo IN (
            'numero', 'serie', 'chaveAcesso', 'tipoDocumento',
            'dataEmissao', 'dataCompetencia', 'vencimentoEm', 'moeda',
            'valorBruto', 'desconto', 'acrescimo', 'retencoes',
            'valorLiquido', 'fornecedorCnpj', 'fornecedorNome'
        )),
    CONSTRAINT chk_fin_doc_extracao_ordem CHECK (ordem >= 0),
    CONSTRAINT chk_fin_doc_extracao_confianca
        CHECK (confianca >= 0 AND confianca <= 1),
    CONSTRAINT chk_fin_doc_extracao_validacao
        CHECK (validacao_status IN (
            'VALIDO', 'DIVERGENTE', 'NAO_VERIFICADO'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_fin_doc_extracao_candidato_job
    ON finance_documento_extracao_candidato (job_id, campo, ordem);

ALTER TABLE finance_nota_fiscal_documento
    ADD COLUMN enviado_por CHAR(36) NULL AFTER criado_por,
    ADD COLUMN enviado_em DATETIME(6) NULL AFTER enviado_por,
    ADD COLUMN dispositivo_id VARCHAR(120) NULL AFTER enviado_em,
    ADD COLUMN client_mutation_id VARCHAR(120) NULL AFTER dispositivo_id,
    ADD COLUMN sha256_cliente CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER client_mutation_id,
    ADD COLUMN sha256_servidor CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER sha256_cliente,
    ADD COLUMN inspecao_status VARCHAR(30) NULL AFTER sha256_servidor,
    ADD COLUMN extracao_job_id CHAR(36) NULL AFTER inspecao_status,
    ADD COLUMN extrator_versao VARCHAR(40) NULL AFTER extracao_job_id,
    ADD COLUMN confirmado_por CHAR(36) NULL AFTER extrator_versao,
    ADD COLUMN confirmado_em DATETIME(6) NULL AFTER confirmado_por;

UPDATE finance_nota_fiscal_documento d
JOIN stored_object o ON o.id = d.stored_object_id
SET d.enviado_por = d.criado_por,
    enviado_em = d.criado_em,
    sha256_servidor = o.sha256,
    inspecao_status = 'APROVADO',
    confirmado_por = d.criado_por,
    confirmado_em = d.criado_em
WHERE d.enviado_por IS NULL;

ALTER TABLE finance_nota_fiscal_documento
    MODIFY enviado_por CHAR(36) NOT NULL,
    MODIFY enviado_em DATETIME(6) NOT NULL,
    MODIFY sha256_servidor CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY inspecao_status VARCHAR(30) NOT NULL,
    MODIFY confirmado_por CHAR(36) NOT NULL,
    MODIFY confirmado_em DATETIME(6) NOT NULL,
    ADD CONSTRAINT uq_fin_nf_documento_mutacao
        UNIQUE (enviado_por, client_mutation_id),
    ADD CONSTRAINT fk_fin_nf_documento_enviado_por
        FOREIGN KEY (enviado_por) REFERENCES colaborador(id),
    ADD CONSTRAINT fk_fin_nf_documento_extracao
        FOREIGN KEY (extracao_job_id, obra_id)
        REFERENCES finance_documento_extracao_job(id, obra_id),
    ADD CONSTRAINT fk_fin_nf_documento_confirmado_por
        FOREIGN KEY (confirmado_por) REFERENCES colaborador(id),
    ADD CONSTRAINT chk_fin_nf_documento_hash_cliente
        CHECK (
            sha256_cliente IS NULL
            OR sha256_cliente REGEXP '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT chk_fin_nf_documento_hash_servidor
        CHECK (sha256_servidor REGEXP '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_fin_nf_documento_inspecao
        CHECK (inspecao_status IN ('APROVADO', 'QUARENTENA'));

CREATE INDEX idx_fin_nf_documento_extracao
    ON finance_nota_fiscal_documento (extracao_job_id, nota_fiscal_id);
