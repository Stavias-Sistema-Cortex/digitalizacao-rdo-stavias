-- Clean PostgreSQL 18 baseline for Córtex. This file describes the final
-- schema directly; it intentionally contains no legacy business-data replay.

CREATE OR REPLACE FUNCTION cortex_touch_atualizado_em()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.atualizado_em = CURRENT_TIMESTAMP(6);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION cortex_touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP(6);
    RETURN NEW;
END;
$$;

CREATE TABLE cortex_evento_commit_sequence (
    id smallint PRIMARY KEY,
    ultima_commit_seq bigint NOT NULL,
    CONSTRAINT chk_cortex_evento_commit_sequence_singleton CHECK (id = 1)
);

INSERT INTO cortex_evento_commit_sequence (id, ultima_commit_seq)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE asset (
    id varchar(36) PRIMARY KEY,
    source_database varchar(64) NOT NULL,
    source_table varchar(64) NOT NULL,
    source_pk varchar(128) NOT NULL,
    external_code varchar(120),
    name varchar(255) NOT NULL,
    category varchar(120),
    active boolean NOT NULL DEFAULT true,
    source_updated_at timestamp(6) without time zone,
    source_hash char(64),
    first_imported_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at timestamp(6) without time zone,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_asset_source_record UNIQUE (source_database, source_table, source_pk)
);

CREATE INDEX idx_asset_external_code ON asset (external_code);
CREATE INDEX idx_asset_name ON asset (name);
CREATE INDEX idx_asset_updated_cursor ON asset (updated_at, id);
CREATE TRIGGER trg_asset_touch_updated_at
BEFORE UPDATE ON asset
FOR EACH ROW EXECUTE FUNCTION cortex_touch_updated_at();

CREATE TABLE asset_alias (
    id varchar(36) PRIMARY KEY,
    asset_id varchar(36) NOT NULL,
    alias_system varchar(80) NOT NULL,
    alias_code varchar(120) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_asset_alias_asset
        FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
    CONSTRAINT uq_asset_alias UNIQUE (alias_system, alias_code)
);

CREATE INDEX idx_asset_alias_asset ON asset_alias (asset_id);

CREATE TABLE source_sync_checkpoint (
    id varchar(36) PRIMARY KEY,
    connector_name varchar(80) NOT NULL,
    source_database varchar(64) NOT NULL,
    source_table varchar(64) NOT NULL,
    cursor_updated_at timestamp(6) without time zone,
    cursor_pk varchar(128),
    last_full_scan_at timestamp(6) without time zone,
    last_success_at timestamp(6) without time zone,
    last_error_at timestamp(6) without time zone,
    last_error_message varchar(1000),
    CONSTRAINT uq_source_sync_checkpoint
        UNIQUE (connector_name, source_database, source_table)
);

CREATE TABLE source_sync_run (
    id varchar(36) PRIMARY KEY,
    connector_name varchar(80) NOT NULL,
    source_database varchar(64) NOT NULL,
    source_table varchar(64) NOT NULL,
    started_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at timestamp(6) without time zone,
    status varchar(20) NOT NULL,
    records_read integer NOT NULL DEFAULT 0,
    records_inserted integer NOT NULL DEFAULT 0,
    records_updated integer NOT NULL DEFAULT 0,
    records_deactivated integer NOT NULL DEFAULT 0,
    error_message varchar(1000)
);

CREATE INDEX idx_source_sync_run_started_at ON source_sync_run (started_at);
CREATE INDEX idx_source_sync_run_connector ON source_sync_run (connector_name, started_at);

CREATE TABLE colaborador (
    id varchar(36) PRIMARY KEY,
    banco_origem varchar(64) NOT NULL,
    tabela_origem varchar(64) NOT NULL,
    pk_origem varchar(128) NOT NULL,
    codigo_colaborador varchar(120),
    cpf_hash char(64),
    cpf_mascarado varchar(20),
    nome varchar(255) NOT NULL,
    email varchar(255),
    id_grupo_origem varchar(128),
    nome_grupo varchar(120),
    id_perfil_origem varchar(128),
    nome_perfil varchar(120),
    papel_acesso varchar(20) NOT NULL DEFAULT 'BETA',
    ativo boolean NOT NULL DEFAULT true,
    criado_em_origem timestamp(6) without time zone,
    atualizado_em_origem timestamp(6) without time zone,
    hash_origem char(64),
    importado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_colaborador_registro_origem UNIQUE (banco_origem, tabela_origem, pk_origem),
    CONSTRAINT chk_colaborador_papel_acesso CHECK (papel_acesso IN ('ALFA', 'BETA'))
);

CREATE INDEX idx_colaborador_nome ON colaborador (nome);
CREATE INDEX idx_colaborador_email ON colaborador (email);
CREATE INDEX idx_colaborador_cpf_hash ON colaborador (cpf_hash);
CREATE INDEX idx_colaborador_nome_grupo ON colaborador (nome_grupo);
CREATE INDEX idx_colaborador_nome_perfil ON colaborador (nome_perfil);
CREATE INDEX idx_colaborador_papel_acesso ON colaborador (papel_acesso);
CREATE INDEX idx_colaborador_atualizado_cursor ON colaborador (atualizado_em, id);
CREATE TRIGGER trg_colaborador_touch_atualizado_em
BEFORE UPDATE ON colaborador
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

-- Authentication identity and credential are declared here because the clean
-- baseline is single-file and these are the only binary fields stored in SQL.
CREATE TABLE auth_identity (
    colaborador_id varchar(36) PRIMARY KEY,
    cpf_lookup_hmac char(64),
    cpf_lookup_key_id varchar(32),
    email_autenticacao varchar(320),
    email_verificado_em timestamp(6) without time zone,
    email_fonte varchar(32),
    status varchar(20) NOT NULL DEFAULT 'PENDENTE',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_auth_identity_cpf_lookup UNIQUE (cpf_lookup_key_id, cpf_lookup_hmac),
    CONSTRAINT fk_auth_identity_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_identity_status CHECK (status IN ('PENDENTE', 'ATIVA', 'BLOQUEADA'))
);

CREATE UNIQUE INDEX uq_auth_identity_email_normalized
    ON auth_identity (lower(email_autenticacao))
    WHERE email_autenticacao IS NOT NULL;
CREATE TRIGGER trg_auth_identity_touch_atualizado_em
BEFORE UPDATE ON auth_identity
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE auth_webauthn_credential (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    credential_id_hash char(64) NOT NULL,
    credential_id bytea NOT NULL,
    user_handle bytea NOT NULL,
    public_key_cose bytea NOT NULL,
    signature_count bigint NOT NULL DEFAULT 0,
    transports_json jsonb NOT NULL,
    aaguid varchar(36),
    discoverable boolean NOT NULL DEFAULT true,
    backed_up boolean NOT NULL DEFAULT false,
    nome varchar(120),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    usado_em timestamp(6) without time zone,
    revogado_em timestamp(6) without time zone,
    CONSTRAINT uq_auth_webauthn_credential_hash UNIQUE (credential_id_hash),
    CONSTRAINT fk_auth_webauthn_credential_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
);

CREATE INDEX idx_auth_webauthn_credential_user
    ON auth_webauthn_credential (colaborador_id, revogado_em);

CREATE TABLE obra (
    id varchar(36) PRIMARY KEY,
    codigo_contrato varchar(80) NOT NULL,
    codigo_cw varchar(40),
    codigo_interno varchar(80),
    nome varchar(255) NOT NULL,
    cliente varchar(255),
    descricao text,
    cidade varchar(120),
    uf char(2),
    rodovia varchar(120),
    latitude numeric(10,7),
    longitude numeric(11,7),
    status varchar(40) NOT NULL DEFAULT 'ATIVA',
    fonte_criacao varchar(40) NOT NULL DEFAULT 'MANUAL',
    fonte_arquivo varchar(255),
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_obra_codigo_contrato UNIQUE (codigo_contrato)
);

CREATE INDEX idx_obra_codigo_cw ON obra (codigo_cw);
CREATE INDEX idx_obra_cliente ON obra (cliente);
CREATE INDEX idx_obra_cidade ON obra (cidade);
CREATE INDEX idx_obra_status ON obra (status);
CREATE INDEX idx_obra_atualizado_cursor ON obra (atualizado_em, id);
CREATE TRIGGER trg_obra_touch_atualizado_em
BEFORE UPDATE ON obra
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE programacao_operacional (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    codigo_contrato_origem varchar(80),
    obra_nome_origem varchar(255),
    data_programacao date NOT NULL,
    equipe varchar(120),
    fechamento varchar(40),
    encarregado varchar(255),
    encarregado_colaborador_id varchar(36),
    engenheiro varchar(255),
    cliente varchar(255),
    servico varchar(255),
    tipo_servico varchar(120),
    cidade varchar(120),
    uf char(2),
    rodovia varchar(120),
    sentido varchar(80),
    periodo varchar(40),
    faixa varchar(80),
    km_inicial varchar(50),
    km_final varchar(50),
    extensao_m numeric(14,3),
    largura_m numeric(14,3),
    espessura_cm numeric(14,3),
    area_m2 numeric(14,3),
    volume_m3 numeric(14,3),
    tonelada_massa numeric(14,3),
    tipo_cap varchar(80),
    teor_cap_projeto numeric(10,4),
    cap numeric(14,3),
    status varchar(40) NOT NULL DEFAULT 'PLANEJADA',
    fonte_criacao varchar(40) NOT NULL DEFAULT 'MANUAL',
    fonte_arquivo varchar(255),
    linha_origem integer,
    hash_origem char(64),
    chave_negocio varchar(700),
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_programacao_operacional_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_programacao_encarregado_colaborador
        FOREIGN KEY (encarregado_colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT uq_programacao_chave_negocio UNIQUE (chave_negocio)
);

CREATE INDEX idx_programacao_obra_data ON programacao_operacional (obra_id, data_programacao);
CREATE INDEX idx_programacao_data ON programacao_operacional (data_programacao);
CREATE INDEX idx_programacao_status ON programacao_operacional (status);
CREATE INDEX idx_programacao_servico ON programacao_operacional (servico);
CREATE INDEX idx_programacao_tipo_servico ON programacao_operacional (tipo_servico);
CREATE INDEX idx_programacao_cidade ON programacao_operacional (cidade);
CREATE INDEX idx_programacao_rodovia ON programacao_operacional (rodovia);
CREATE INDEX idx_programacao_encarregado_colaborador
    ON programacao_operacional (encarregado_colaborador_id);
CREATE INDEX idx_programacao_fechamento ON programacao_operacional (fechamento);
CREATE INDEX idx_programacao_atualizado_cursor ON programacao_operacional (atualizado_em, id);
CREATE TRIGGER trg_programacao_operacional_touch_atualizado_em
BEFORE UPDATE ON programacao_operacional
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE importacao_rdo (
    id varchar(36) PRIMARY KEY,
    nome_arquivo varchar(255) NOT NULL,
    hash_arquivo char(64) NOT NULL,
    tamanho_bytes bigint NOT NULL,
    content_type varchar(120),
    storage_key varchar(512),
    layout_detectado varchar(120),
    status varchar(40) NOT NULL DEFAULT 'ANALISADA',
    estrategia_duplicidade varchar(40) NOT NULL DEFAULT 'IGNORAR_DUPLICADO',
    versao_mapeamento varchar(80) NOT NULL DEFAULT 'RDO_IMPORT_V1',
    usuario_id varchar(120),
    quantidade_processada integer NOT NULL DEFAULT 0,
    quantidade_importada integer NOT NULL DEFAULT 0,
    quantidade_rejeitada integer NOT NULL DEFAULT 0,
    quantidade_duplicada integer NOT NULL DEFAULT 0,
    relatorio_json jsonb,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    confirmado_em timestamp(6) without time zone,
    CONSTRAINT chk_importacao_rdo_status CHECK (status IN (
        'ANALISADA', 'AGUARDANDO_CORRECAO', 'VALIDADA', 'SIMULADA',
        'CONFIRMADA', 'CONFIRMADA_COM_ERROS', 'REJEITADA', 'ERRO'
    )),
    CONSTRAINT chk_importacao_rdo_estrategia CHECK (estrategia_duplicidade IN (
        'IGNORAR_DUPLICADO', 'ATUALIZAR_EXISTENTE', 'CRIAR_NOVA_VERSAO', 'REJEITAR'
    ))
);

CREATE INDEX idx_importacao_rdo_hash ON importacao_rdo (hash_arquivo);
CREATE INDEX idx_importacao_rdo_status ON importacao_rdo (status, criado_em);
CREATE TRIGGER trg_importacao_rdo_touch_atualizado_em
BEFORE UPDATE ON importacao_rdo
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE rdo (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    programacao_id varchar(36),
    numero_rdo varchar(80),
    data_rdo date NOT NULL,
    dia_semana varchar(30),
    cliente varchar(255),
    contrato varchar(80),
    rodovia varchar(120),
    cidade varchar(120),
    uf char(2),
    km_inicial_programado varchar(40),
    km_final_programado varchar(40),
    km_inicial_interditado varchar(40),
    km_final_interditado varchar(40),
    turno varchar(40),
    hora_inicio time without time zone,
    hora_fim time without time zone,
    condicao_manha varchar(80),
    condicao_tarde varchar(80),
    condicao_noite varchar(80),
    pluviometria_mm numeric(10,3),
    status varchar(40) NOT NULL DEFAULT 'RASCUNHO',
    fonte_criacao varchar(40) NOT NULL DEFAULT 'MANUAL',
    importacao_rdo_id varchar(36),
    fonte_arquivo varchar(255),
    aba_origem varchar(120),
    linha_origem integer,
    data_original date,
    data_importacao timestamp(6) without time zone,
    usuario_importacao varchar(120),
    preenchido_por varchar(255),
    apontador_rdo varchar(255),
    encarregado_obra varchar(255),
    fiscalizacao_campo varchar(255),
    versao_mapeamento varchar(80),
    chave_negocio_importacao char(64),
    estado_receita varchar(40) NOT NULL DEFAULT 'PRODUCAO_REGISTRADA',
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    enviado_em timestamp(6) without time zone,
    aprovado_em timestamp(6) without time zone,
    cancelado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_rdo_obra FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_rdo_programacao
        FOREIGN KEY (programacao_id) REFERENCES programacao_operacional(id),
    CONSTRAINT fk_rdo_importacao_rdo
        FOREIGN KEY (importacao_rdo_id) REFERENCES importacao_rdo(id) ON DELETE RESTRICT,
    CONSTRAINT chk_rdo_fonte_criacao CHECK (fonte_criacao IN (
        'MANUAL', 'OFFLINE_SYNC', 'IMPORTACAO_HISTORICA', 'SISTEMA'
    )),
    CONSTRAINT chk_rdo_estado_receita CHECK (estado_receita IN (
        'PRODUCAO_REGISTRADA', 'PRODUCAO_VALIDADA', 'RECEITA_ESTIMADA',
        'RECEITA_MEDIDA', 'RECEITA_APROVADA', 'RECEITA_FATURADA', 'RECEITA_RECEBIDA'
    ))
);

CREATE INDEX idx_rdo_obra_data ON rdo (obra_id, data_rdo);
CREATE INDEX idx_rdo_programacao ON rdo (programacao_id);
CREATE INDEX idx_rdo_status ON rdo (status);
CREATE INDEX idx_rdo_data ON rdo (data_rdo);
CREATE INDEX idx_rdo_importacao ON rdo (importacao_rdo_id);
CREATE INDEX idx_rdo_chave_importacao ON rdo (chave_negocio_importacao);
CREATE INDEX idx_rdo_fonte_criacao ON rdo (fonte_criacao, data_rdo);
CREATE TRIGGER trg_rdo_touch_atualizado_em
BEFORE UPDATE ON rdo
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE rdo_mao_obra (
    id varchar(36) PRIMARY KEY,
    rdo_id varchar(36) NOT NULL,
    colaborador_id varchar(36),
    nome_colaborador varchar(255),
    cargo varchar(120),
    tipo_vinculo varchar(40) NOT NULL DEFAULT 'CONTRATADO',
    quantidade numeric(10,3) NOT NULL DEFAULT 1,
    hora_inicio time without time zone,
    hora_fim time without time zone,
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_rdo_mao_obra_rdo FOREIGN KEY (rdo_id) REFERENCES rdo(id),
    CONSTRAINT fk_rdo_mao_obra_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
);

CREATE INDEX idx_rdo_mao_obra_rdo ON rdo_mao_obra (rdo_id);
CREATE INDEX idx_rdo_mao_obra_colaborador ON rdo_mao_obra (colaborador_id);
CREATE TRIGGER trg_rdo_mao_obra_touch_atualizado_em
BEFORE UPDATE ON rdo_mao_obra
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE rdo_equipamento (
    id varchar(36) PRIMARY KEY,
    rdo_id varchar(36) NOT NULL,
    asset_id varchar(36),
    prefixo varchar(80),
    descricao varchar(255),
    tipo_equipamento varchar(120),
    tipo_vinculo varchar(40) NOT NULL DEFAULT 'PROPRIO',
    quantidade numeric(10,3) NOT NULL DEFAULT 1,
    hora_inicio time without time zone,
    hora_fim time without time zone,
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_rdo_equipamento_rdo FOREIGN KEY (rdo_id) REFERENCES rdo(id),
    CONSTRAINT fk_rdo_equipamento_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE INDEX idx_rdo_equipamento_rdo ON rdo_equipamento (rdo_id);
CREATE INDEX idx_rdo_equipamento_asset ON rdo_equipamento (asset_id);
CREATE TRIGGER trg_rdo_equipamento_touch_atualizado_em
BEFORE UPDATE ON rdo_equipamento
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE rdo_material (
    id varchar(36) PRIMARY KEY,
    rdo_id varchar(36) NOT NULL,
    material_nome varchar(255) NOT NULL,
    unidade varchar(30),
    quantidade_prevista numeric(14,3),
    quantidade_usinada numeric(14,3),
    quantidade_aplicada numeric(14,3),
    quantidade_sobra numeric(14,3),
    nota_fiscal varchar(120),
    fornecedor varchar(255),
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_rdo_material_rdo FOREIGN KEY (rdo_id) REFERENCES rdo(id)
);

CREATE INDEX idx_rdo_material_rdo ON rdo_material (rdo_id);
CREATE INDEX idx_rdo_material_nome ON rdo_material (material_nome);
CREATE TRIGGER trg_rdo_material_touch_atualizado_em
BEFORE UPDATE ON rdo_material
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE rdo_controle_geometrico (
    id varchar(36) PRIMARY KEY,
    rdo_id varchar(36) NOT NULL,
    subtrecho varchar(120),
    numero varchar(80),
    estaca_inicial varchar(80),
    estaca_final varchar(80),
    km_inicial varchar(40),
    km_final varchar(40),
    pista varchar(80),
    faixa varchar(80),
    ordem_servico varchar(120),
    atividade_observacoes text,
    comprimento_m numeric(14,3),
    largura_m numeric(14,3),
    espessura_1_cm numeric(14,3),
    espessura_2_cm numeric(14,3),
    espessura_3_cm numeric(14,3),
    espessura_media_cm numeric(14,3),
    area_m2 numeric(14,3),
    volume_m3 numeric(14,3),
    densidade numeric(14,6),
    massa_tonelada numeric(14,3),
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_rdo_controle_geometrico_rdo FOREIGN KEY (rdo_id) REFERENCES rdo(id)
);

CREATE INDEX idx_rdo_controle_geometrico_rdo ON rdo_controle_geometrico (rdo_id);
CREATE TRIGGER trg_rdo_controle_geometrico_touch_atualizado_em
BEFORE UPDATE ON rdo_controle_geometrico
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE importacao_rdo_linha (
    id varchar(36) PRIMARY KEY,
    importacao_id varchar(36) NOT NULL,
    aba varchar(120),
    numero_linha integer NOT NULL,
    payload_original_json jsonb NOT NULL,
    payload_normalizado_json jsonb NOT NULL,
    layout_detectado varchar(120),
    chave_negocio char(64),
    status varchar(40) NOT NULL DEFAULT 'VALIDA',
    obra_id varchar(36),
    numero_rdo varchar(80),
    data_rdo date,
    rdo_id varchar(36),
    quantidade_erros integer NOT NULL DEFAULT 0,
    quantidade_warnings integer NOT NULL DEFAULT 0,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_importacao_rdo_linha_importacao
        FOREIGN KEY (importacao_id) REFERENCES importacao_rdo(id) ON DELETE RESTRICT,
    CONSTRAINT fk_importacao_rdo_linha_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_importacao_rdo_linha_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id) ON DELETE SET NULL,
    CONSTRAINT uq_importacao_rdo_linha UNIQUE (importacao_id, aba, numero_linha),
    CONSTRAINT chk_importacao_rdo_linha_status CHECK (status IN (
        'VALIDA', 'INVALIDA', 'IMPORTADA', 'REJEITADA', 'DUPLICADA', 'ATUALIZADA'
    ))
);

CREATE INDEX idx_importacao_rdo_linha_status
    ON importacao_rdo_linha (importacao_id, status);
CREATE INDEX idx_importacao_rdo_linha_chave ON importacao_rdo_linha (chave_negocio);
CREATE INDEX idx_importacao_rdo_linha_rdo ON importacao_rdo_linha (rdo_id);
CREATE INDEX idx_importacao_rdo_linha_obra ON importacao_rdo_linha (obra_id);
CREATE TRIGGER trg_importacao_rdo_linha_touch_atualizado_em
BEFORE UPDATE ON importacao_rdo_linha
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE importacao_rdo_erro (
    id varchar(36) PRIMARY KEY,
    importacao_id varchar(36) NOT NULL,
    linha_id varchar(36),
    aba varchar(120),
    numero_linha integer,
    campo varchar(120) NOT NULL,
    valor_recebido varchar(1000),
    valor_esperado varchar(1000),
    mensagem varchar(1000) NOT NULL,
    sugestao varchar(1000),
    severidade varchar(20) NOT NULL DEFAULT 'ERRO',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_importacao_rdo_erro_importacao
        FOREIGN KEY (importacao_id) REFERENCES importacao_rdo(id) ON DELETE RESTRICT,
    CONSTRAINT fk_importacao_rdo_erro_linha
        FOREIGN KEY (linha_id) REFERENCES importacao_rdo_linha(id) ON DELETE SET NULL,
    CONSTRAINT chk_importacao_rdo_erro_severidade CHECK (severidade IN ('ERRO', 'WARNING'))
);

CREATE INDEX idx_importacao_rdo_erro_importacao ON importacao_rdo_erro (importacao_id);
CREATE INDEX idx_importacao_rdo_erro_linha ON importacao_rdo_erro (linha_id, severidade);

CREATE TABLE importacao_rdo_mapeamento (
    id varchar(36) PRIMARY KEY,
    importacao_id varchar(36) NOT NULL,
    versao_mapeamento varchar(80) NOT NULL,
    layout_detectado varchar(120),
    coluna_origem varchar(255) NOT NULL,
    campo_destino varchar(120) NOT NULL,
    obrigatorio boolean NOT NULL DEFAULT false,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_importacao_rdo_mapeamento_importacao
        FOREIGN KEY (importacao_id) REFERENCES importacao_rdo(id) ON DELETE RESTRICT,
    CONSTRAINT uq_importacao_rdo_mapeamento
        UNIQUE (importacao_id, coluna_origem, campo_destino)
);

CREATE INDEX idx_importacao_rdo_mapeamento_importacao
    ON importacao_rdo_mapeamento (importacao_id);

CREATE TABLE item_contratual (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    contrato varchar(80) NOT NULL,
    codigo_item varchar(120) NOT NULL,
    descricao varchar(500) NOT NULL,
    unidade_medida varchar(30) NOT NULL,
    quantidade_contratada numeric(18,3) NOT NULL,
    preco_unitario numeric(18,4) NOT NULL,
    valor_total numeric(18,2) NOT NULL,
    vigencia_inicio date,
    vigencia_fim date,
    versao integer NOT NULL DEFAULT 1,
    status varchar(40) NOT NULL DEFAULT 'ATIVO',
    fonte varchar(80) NOT NULL DEFAULT 'MANUAL',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_item_contratual_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT uq_item_contratual_versao UNIQUE (obra_id, contrato, codigo_item, versao),
    CONSTRAINT chk_item_contratual_status
        CHECK (status IN ('ATIVO', 'INATIVO', 'SUBSTITUIDO', 'CANCELADO')),
    CONSTRAINT chk_item_contratual_quantidade CHECK (quantidade_contratada >= 0),
    CONSTRAINT chk_item_contratual_preco CHECK (preco_unitario >= 0)
);

CREATE INDEX idx_item_contratual_obra_status ON item_contratual (obra_id, status);
CREATE INDEX idx_item_contratual_codigo ON item_contratual (codigo_item);
CREATE INDEX idx_item_contratual_vigencia
    ON item_contratual (obra_id, vigencia_inicio, vigencia_fim);
CREATE TRIGGER trg_item_contratual_touch_atualizado_em
BEFORE UPDATE ON item_contratual
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE execucao_servico_rdo (
    id varchar(36) PRIMARY KEY,
    rdo_id varchar(36) NOT NULL,
    obra_id varchar(36) NOT NULL,
    programacao_id varchar(36),
    servico_nome varchar(255) NOT NULL,
    item_contratual_id varchar(36),
    quantidade_executada numeric(18,3) NOT NULL,
    unidade_medida varchar(30) NOT NULL,
    trecho_inicial varchar(80),
    trecho_final varchar(80),
    localizacao varchar(255),
    data_execucao date NOT NULL,
    turno varchar(40),
    status_validacao varchar(40) NOT NULL DEFAULT 'REGISTRADA',
    estado_receita varchar(40) NOT NULL DEFAULT 'PRODUCAO_REGISTRADA',
    receita_operacional_estimativa numeric(18,2),
    custo_realizado numeric(18,2),
    retrabalho boolean NOT NULL DEFAULT false,
    producao_rejeitada boolean NOT NULL DEFAULT false,
    cancelada boolean NOT NULL DEFAULT false,
    fonte varchar(80) NOT NULL DEFAULT 'RDO',
    chave_execucao char(64) NOT NULL,
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_execucao_servico_rdo_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id) ON DELETE RESTRICT,
    CONSTRAINT fk_execucao_servico_rdo_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_execucao_servico_rdo_programacao
        FOREIGN KEY (programacao_id) REFERENCES programacao_operacional(id) ON DELETE RESTRICT,
    CONSTRAINT fk_execucao_servico_rdo_item
        FOREIGN KEY (item_contratual_id) REFERENCES item_contratual(id) ON DELETE RESTRICT,
    CONSTRAINT uq_execucao_servico_rdo_chave UNIQUE (chave_execucao),
    CONSTRAINT chk_execucao_servico_rdo_status
        CHECK (status_validacao IN ('REGISTRADA', 'VALIDADA', 'REJEITADA', 'CANCELADA')),
    CONSTRAINT chk_execucao_servico_rdo_estado_receita CHECK (estado_receita IN (
        'PRODUCAO_REGISTRADA', 'PRODUCAO_VALIDADA', 'RECEITA_ESTIMADA',
        'RECEITA_MEDIDA', 'RECEITA_APROVADA', 'RECEITA_FATURADA', 'RECEITA_RECEBIDA'
    )),
    CONSTRAINT chk_execucao_servico_rdo_quantidade CHECK (quantidade_executada >= 0)
);

CREATE INDEX idx_execucao_servico_obra_data ON execucao_servico_rdo (obra_id, data_execucao);
CREATE INDEX idx_execucao_servico_rdo ON execucao_servico_rdo (rdo_id);
CREATE INDEX idx_execucao_servico_programacao ON execucao_servico_rdo (programacao_id);
CREATE INDEX idx_execucao_servico_item ON execucao_servico_rdo (item_contratual_id);
CREATE INDEX idx_execucao_servico_status
    ON execucao_servico_rdo (status_validacao, estado_receita);
CREATE TRIGGER trg_execucao_servico_rdo_touch_atualizado_em
BEFORE UPDATE ON execucao_servico_rdo
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE alocacao_colaborador (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    data_alocacao date NOT NULL,
    hora_inicio time without time zone,
    hora_fim time without time zone,
    minutos integer NOT NULL,
    percentual_dia numeric(9,6),
    obra_id varchar(36) NOT NULL,
    equipe varchar(120),
    servico_nome varchar(255),
    rdo_id varchar(36),
    programacao_id varchar(36),
    turno varchar(40),
    funcao varchar(120),
    centro_custo varchar(120),
    tipo_alocacao varchar(40) NOT NULL DEFAULT 'TRABALHO',
    fonte varchar(80) NOT NULL DEFAULT 'RDO',
    status varchar(40) NOT NULL DEFAULT 'REGISTRADA',
    custo_hora numeric(18,4),
    custo_total numeric(18,2),
    observacoes text,
    criado_por varchar(120),
    validado_por varchar(120),
    validado_em timestamp(6) without time zone,
    chave_alocacao char(64) NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_alocacao_colaborador_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_alocacao_colaborador_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_alocacao_colaborador_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id) ON DELETE SET NULL,
    CONSTRAINT fk_alocacao_colaborador_programacao
        FOREIGN KEY (programacao_id) REFERENCES programacao_operacional(id) ON DELETE SET NULL,
    CONSTRAINT uq_alocacao_colaborador_chave UNIQUE (chave_alocacao),
    CONSTRAINT chk_alocacao_colaborador_tipo CHECK (tipo_alocacao IN (
        'TRABALHO', 'DESLOCAMENTO', 'TREINAMENTO', 'MANUTENCAO', 'APOIO',
        'ADMINISTRATIVO', 'AFASTAMENTO', 'OUTRO'
    )),
    CONSTRAINT chk_alocacao_colaborador_status
        CHECK (status IN ('REGISTRADA', 'VALIDADA', 'CONFLITO', 'CANCELADA')),
    CONSTRAINT chk_alocacao_colaborador_minutos CHECK (minutos >= 0 AND minutos <= 1440),
    CONSTRAINT chk_alocacao_colaborador_percentual
        CHECK (percentual_dia IS NULL OR (percentual_dia >= 0 AND percentual_dia <= 1))
);

CREATE INDEX idx_alocacao_colaborador_data
    ON alocacao_colaborador (colaborador_id, data_alocacao);
CREATE INDEX idx_alocacao_obra_data ON alocacao_colaborador (obra_id, data_alocacao);
CREATE INDEX idx_alocacao_rdo ON alocacao_colaborador (rdo_id);
CREATE INDEX idx_alocacao_programacao ON alocacao_colaborador (programacao_id);
CREATE INDEX idx_alocacao_status ON alocacao_colaborador (status, data_alocacao);
CREATE TRIGGER trg_alocacao_colaborador_touch_atualizado_em
BEFORE UPDATE ON alocacao_colaborador
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE conflito_alocacao_colaborador (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    data_referencia date NOT NULL,
    tipo_conflito varchar(80) NOT NULL,
    descricao text NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'ABERTO',
    fontes_json jsonb NOT NULL,
    decisao_json jsonb,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolvido_em timestamp(6) without time zone,
    resolvido_por varchar(120),
    CONSTRAINT fk_conflito_alocacao_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_conflito_alocacao_status CHECK (status IN ('ABERTO', 'RESOLVIDO', 'IGNORADO'))
);

CREATE INDEX idx_conflito_alocacao_colaborador_data
    ON conflito_alocacao_colaborador (colaborador_id, data_referencia, status);

CREATE TABLE jornada_planejada (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    data_jornada date NOT NULL,
    minutos_previstos integer NOT NULL,
    hora_inicio time without time zone,
    hora_fim time without time zone,
    origem varchar(80) NOT NULL DEFAULT 'MANUAL',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_jornada_planejada_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT uq_jornada_planejada_colaborador_data UNIQUE (colaborador_id, data_jornada),
    CONSTRAINT chk_jornada_planejada_minutos
        CHECK (minutos_previstos >= 0 AND minutos_previstos <= 1440)
);

CREATE TRIGGER trg_jornada_planejada_touch_atualizado_em
BEFORE UPDATE ON jornada_planejada
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE registro_presenca (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    data_presenca date NOT NULL,
    minutos_registrados integer NOT NULL DEFAULT 0,
    origem varchar(80) NOT NULL DEFAULT 'OPERACIONAL',
    status varchar(40) NOT NULL DEFAULT 'REGISTRADA',
    rdo_id varchar(36),
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_registro_presenca_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_registro_presenca_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id) ON DELETE SET NULL,
    CONSTRAINT chk_registro_presenca_status
        CHECK (status IN ('REGISTRADA', 'VALIDADA', 'DIVERGENTE', 'CANCELADA')),
    CONSTRAINT chk_registro_presenca_minutos
        CHECK (minutos_registrados >= 0 AND minutos_registrados <= 1440)
);

CREATE INDEX idx_registro_presenca_colaborador_data
    ON registro_presenca (colaborador_id, data_presenca);
CREATE INDEX idx_registro_presenca_rdo ON registro_presenca (rdo_id);
CREATE TRIGGER trg_registro_presenca_touch_atualizado_em
BEFORE UPDATE ON registro_presenca
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE ocorrencia_frequencia (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    data_ocorrencia date NOT NULL,
    tipo varchar(40) NOT NULL,
    minutos integer NOT NULL DEFAULT 0,
    status varchar(40) NOT NULL DEFAULT 'REGISTRADA',
    origem varchar(80) NOT NULL DEFAULT 'OPERACIONAL',
    rdo_id varchar(36),
    justificativa text,
    criado_por varchar(120),
    validado_por varchar(120),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    validado_em timestamp(6) without time zone,
    CONSTRAINT fk_ocorrencia_frequencia_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ocorrencia_frequencia_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id) ON DELETE SET NULL,
    CONSTRAINT chk_ocorrencia_frequencia_tipo CHECK (tipo IN (
        'PRESENTE', 'FALTA', 'FALTA_JUSTIFICADA', 'ATRASO', 'SAIDA_ANTECIPADA',
        'HORA_EXTRA', 'FOLGA', 'FERIAS', 'ATESTADO', 'AFASTAMENTO',
        'TREINAMENTO', 'SEM_REGISTRO', 'DIVERGENCIA'
    )),
    CONSTRAINT chk_ocorrencia_frequencia_status
        CHECK (status IN ('REGISTRADA', 'JUSTIFICADA', 'VALIDADA', 'CANCELADA')),
    CONSTRAINT chk_ocorrencia_frequencia_minutos CHECK (minutos >= 0 AND minutos <= 1440)
);

CREATE INDEX idx_ocorrencia_frequencia_colaborador_data
    ON ocorrencia_frequencia (colaborador_id, data_ocorrencia, tipo);
CREATE INDEX idx_ocorrencia_frequencia_rdo ON ocorrencia_frequencia (rdo_id);

CREATE TABLE politica_banco_horas (
    id varchar(36) PRIMARY KEY,
    empresa varchar(120),
    contrato varchar(80),
    categoria varchar(120),
    colaborador_id varchar(36),
    periodo_inicio date NOT NULL,
    periodo_fim date,
    regra_json jsonb NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'ATIVA',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_politica_banco_horas_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT chk_politica_banco_horas_status CHECK (status IN ('ATIVA', 'INATIVA'))
);

CREATE INDEX idx_politica_banco_horas_colaborador ON politica_banco_horas (colaborador_id);
CREATE TRIGGER trg_politica_banco_horas_touch_atualizado_em
BEFORE UPDATE ON politica_banco_horas
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE saldo_banco_horas (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    periodo_inicio date NOT NULL,
    periodo_fim date NOT NULL,
    minutos_saldo_anterior integer NOT NULL DEFAULT 0,
    minutos_credito integer NOT NULL DEFAULT 0,
    minutos_debito integer NOT NULL DEFAULT 0,
    minutos_ajuste integer NOT NULL DEFAULT 0,
    minutos_saldo_atual integer NOT NULL DEFAULT 0,
    politica_id varchar(36),
    calculado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_saldo_banco_horas_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_saldo_banco_horas_politica
        FOREIGN KEY (politica_id) REFERENCES politica_banco_horas(id) ON DELETE SET NULL,
    CONSTRAINT uq_saldo_banco_horas_periodo
        UNIQUE (colaborador_id, periodo_inicio, periodo_fim)
);

CREATE INDEX idx_saldo_banco_horas_politica ON saldo_banco_horas (politica_id);

CREATE TABLE cortex_objeto (
    id varchar(36) PRIMARY KEY,
    tipo_entidade varchar(80) NOT NULL,
    tabela_entidade varchar(120),
    entidade_id varchar(36) NOT NULL,
    codigo_externo varchar(120),
    chave_canonica varchar(255),
    nome varchar(255),
    status varchar(80),
    fonte varchar(80) NOT NULL DEFAULT 'SISTEMA',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    metadados_json jsonb,
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_cortex_objeto_entidade UNIQUE (tipo_entidade, entidade_id)
);

CREATE INDEX idx_cortex_objeto_tipo_status ON cortex_objeto (tipo_entidade, status);
CREATE INDEX idx_cortex_objeto_codigo_externo ON cortex_objeto (codigo_externo);
CREATE INDEX idx_cortex_objeto_chave_canonica ON cortex_objeto (tipo_entidade, chave_canonica);
CREATE INDEX idx_cortex_objeto_visto_por_ultimo ON cortex_objeto (visto_por_ultimo_em);
CREATE TRIGGER trg_cortex_objeto_touch_atualizado_em
BEFORE UPDATE ON cortex_objeto
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE cortex_relacao (
    id varchar(36) PRIMARY KEY,
    origem_tipo varchar(80) NOT NULL,
    origem_id varchar(36) NOT NULL,
    destino_tipo varchar(80) NOT NULL,
    destino_id varchar(36) NOT NULL,
    tipo_relacao varchar(120) NOT NULL,
    ativa boolean NOT NULL DEFAULT true,
    fonte varchar(80) NOT NULL DEFAULT 'SISTEMA',
    observacoes text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    encerrado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_cortex_relacao_ativa
    ON cortex_relacao (origem_tipo, origem_id, destino_tipo, destino_id, tipo_relacao)
    WHERE ativa IS true;
CREATE INDEX idx_cortex_relacao_origem_ativa
    ON cortex_relacao (origem_tipo, origem_id, ativa);
CREATE INDEX idx_cortex_relacao_destino_ativa
    ON cortex_relacao (destino_tipo, destino_id, ativa);
CREATE INDEX idx_cortex_relacao_tipo ON cortex_relacao (tipo_relacao);
CREATE TRIGGER trg_cortex_relacao_touch_atualizado_em
BEFORE UPDATE ON cortex_relacao
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE cortex_evento_operacional (
    sequencia bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    id varchar(36) NOT NULL UNIQUE,
    commit_seq bigint NOT NULL UNIQUE,
    tipo_entidade varchar(80) NOT NULL,
    entidade_id varchar(36) NOT NULL,
    obra_id varchar(36),
    rdo_id varchar(36),
    colaborador_id varchar(36),
    tipo_evento varchar(120) NOT NULL,
    fonte varchar(80) NOT NULL DEFAULT 'SISTEMA',
    origem varchar(30) NOT NULL DEFAULT 'ONLINE',
    sync_status varchar(40) NOT NULL DEFAULT 'SYNCED',
    sincronizado_em timestamp(6) without time zone,
    usuario_id varchar(36),
    dispositivo_id varchar(36),
    correlacao_id varchar(120),
    causacao_id varchar(120),
    entidades_relacionadas_json jsonb,
    estado_anterior_json jsonb,
    estado_novo_json jsonb,
    resultado varchar(20) NOT NULL DEFAULT 'SUCESSO',
    erro_categoria varchar(80),
    versao_entidade bigint,
    schema_version integer NOT NULL DEFAULT 1,
    payload_json jsonb,
    ocorrido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_cortex_evento_resultado CHECK (resultado IN (
        'SUCESSO', 'FALHA', 'PARCIAL', 'CONFLITO', 'REJEITADA', 'CONCILIADA'
    ))
);

CREATE INDEX idx_cortex_evento_entidade
    ON cortex_evento_operacional (tipo_entidade, entidade_id);
CREATE INDEX idx_cortex_evento_tipo_commit
    ON cortex_evento_operacional (tipo_evento, commit_seq);
CREATE INDEX idx_cortex_evento_ocorrido ON cortex_evento_operacional (ocorrido_em);
CREATE INDEX idx_cortex_evento_obra_rdo
    ON cortex_evento_operacional (obra_id, rdo_id, ocorrido_em);
CREATE INDEX idx_cortex_evento_colaborador
    ON cortex_evento_operacional (colaborador_id, ocorrido_em);
CREATE INDEX idx_cortex_evento_sync_status
    ON cortex_evento_operacional (sync_status, ocorrido_em);
CREATE INDEX idx_cortex_evento_correlation
    ON cortex_evento_operacional (correlacao_id, commit_seq);
CREATE INDEX idx_cortex_evento_device_commit
    ON cortex_evento_operacional (dispositivo_id, commit_seq);

CREATE TABLE cortex_estado_entidade (
    tipo_entidade varchar(80) NOT NULL,
    entidade_id varchar(36) NOT NULL,
    versao_entidade bigint NOT NULL DEFAULT 0,
    ultimo_evento_seq bigint,
    hash_estado char(64),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tipo_entidade, entidade_id),
    CONSTRAINT fk_cortex_estado_ultimo_evento
        FOREIGN KEY (ultimo_evento_seq) REFERENCES cortex_evento_operacional(sequencia)
        ON DELETE RESTRICT
);

CREATE INDEX idx_cortex_estado_ultimo_evento ON cortex_estado_entidade (ultimo_evento_seq);
CREATE TRIGGER trg_cortex_estado_entidade_touch_atualizado_em
BEFORE UPDATE ON cortex_estado_entidade
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE cortex_mapeamento_legado (
    id varchar(36) PRIMARY KEY,
    sistema_legado varchar(80) NOT NULL,
    tabela_legado varchar(120) NOT NULL,
    id_legado varchar(160) NOT NULL,
    hash_legado char(64),
    objeto_id varchar(36) NOT NULL,
    tipo_entidade varchar(80) NOT NULL,
    entidade_id varchar(36) NOT NULL,
    import_batch_id varchar(36),
    snapshot_origem_json jsonb,
    ativo boolean NOT NULL DEFAULT true,
    importado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_cortex_mapeamento_legado
        UNIQUE (sistema_legado, tabela_legado, id_legado),
    CONSTRAINT fk_cortex_mapeamento_legado_objeto
        FOREIGN KEY (objeto_id) REFERENCES cortex_objeto(id) ON DELETE RESTRICT
);

CREATE INDEX idx_cortex_mapeamento_legado_objeto ON cortex_mapeamento_legado (objeto_id);
CREATE INDEX idx_cortex_mapeamento_legado_entidade
    ON cortex_mapeamento_legado (tipo_entidade, entidade_id);
CREATE INDEX idx_cortex_mapeamento_legado_batch ON cortex_mapeamento_legado (import_batch_id);
CREATE TRIGGER trg_cortex_mapeamento_legado_touch_atualizado_em
BEFORE UPDATE ON cortex_mapeamento_legado
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE cortex_evidencia_operacional (
    id varchar(36) PRIMARY KEY,
    objeto_id varchar(36) NOT NULL,
    tipo_entidade varchar(80) NOT NULL,
    entidade_id varchar(36) NOT NULL,
    nome_campo varchar(120) NOT NULL,
    valor_campo text,
    tipo_campo varchar(40) NOT NULL DEFAULT 'TEXTO',
    fonte varchar(80) NOT NULL DEFAULT 'SISTEMA',
    confianca numeric(9,6) NOT NULL DEFAULT 1.000000,
    valido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    metadados_json jsonb,
    CONSTRAINT uq_cortex_evidencia_operacional UNIQUE (objeto_id, nome_campo, fonte),
    CONSTRAINT fk_cortex_evidencia_operacional_objeto
        FOREIGN KEY (objeto_id) REFERENCES cortex_objeto(id) ON DELETE CASCADE
);

CREATE INDEX idx_cortex_evidencia_entidade
    ON cortex_evidencia_operacional (tipo_entidade, entidade_id);
CREATE INDEX idx_cortex_evidencia_campo ON cortex_evidencia_operacional (nome_campo);
CREATE INDEX idx_cortex_evidencia_fonte
    ON cortex_evidencia_operacional (fonte, atualizado_em);
CREATE TRIGGER trg_cortex_evidencia_operacional_touch_atualizado_em
BEFORE UPDATE ON cortex_evidencia_operacional
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE ajuste_banco_horas (
    id varchar(36) PRIMARY KEY,
    colaborador_id varchar(36) NOT NULL,
    saldo_id varchar(36),
    data_ajuste date NOT NULL,
    minutos_anterior integer NOT NULL,
    minutos_novo integer NOT NULL,
    minutos_delta integer NOT NULL,
    motivo text NOT NULL,
    usuario_id varchar(120) NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'PENDENTE',
    aprovado_por varchar(120),
    aprovado_em timestamp(6) without time zone,
    evento_id varchar(36),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_ajuste_banco_horas_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ajuste_banco_horas_saldo
        FOREIGN KEY (saldo_id) REFERENCES saldo_banco_horas(id) ON DELETE SET NULL,
    CONSTRAINT fk_ajuste_banco_horas_evento
        FOREIGN KEY (evento_id) REFERENCES cortex_evento_operacional(id) ON DELETE SET NULL,
    CONSTRAINT chk_ajuste_banco_horas_status
        CHECK (status IN ('PENDENTE', 'APROVADO', 'REJEITADO', 'CANCELADO'))
);

CREATE INDEX idx_ajuste_banco_horas_colaborador_data
    ON ajuste_banco_horas (colaborador_id, data_ajuste);
CREATE INDEX idx_ajuste_banco_horas_saldo ON ajuste_banco_horas (saldo_id);
CREATE INDEX idx_ajuste_banco_horas_evento ON ajuste_banco_horas (evento_id);

CREATE TABLE sync_dispositivo (
    id varchar(36) PRIMARY KEY,
    nome varchar(255),
    tipo varchar(80),
    usuario_id varchar(36),
    ativo boolean NOT NULL DEFAULT true,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    visto_por_ultimo_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    desativado_em timestamp(6) without time zone,
    CONSTRAINT chk_sync_dispositivo_tipo
        CHECK (tipo IS NULL OR tipo IN ('WEB', 'TABLET', 'CELULAR', 'DESKTOP', 'OUTRO'))
);

CREATE INDEX idx_sync_dispositivo_usuario ON sync_dispositivo (usuario_id);
CREATE INDEX idx_sync_dispositivo_ativo ON sync_dispositivo (ativo);

CREATE TABLE sync_estado_dispositivo (
    dispositivo_id varchar(36) PRIMARY KEY,
    ultimo_evento_recebido_seq bigint NOT NULL DEFAULT 0,
    ultimo_evento_recebido_commit_seq bigint NOT NULL DEFAULT 0,
    ultimo_push_em timestamp(6) without time zone,
    ultimo_pull_em timestamp(6) without time zone,
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_sync_estado_dispositivo
        FOREIGN KEY (dispositivo_id) REFERENCES sync_dispositivo(id) ON DELETE CASCADE
);

CREATE INDEX idx_sync_estado_cursor ON sync_estado_dispositivo (ultimo_evento_recebido_seq);
CREATE INDEX idx_sync_estado_commit_cursor
    ON sync_estado_dispositivo (ultimo_evento_recebido_commit_seq);
CREATE TRIGGER trg_sync_estado_dispositivo_touch_atualizado_em
BEFORE UPDATE ON sync_estado_dispositivo
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE sync_mutacao_cliente (
    id varchar(36) PRIMARY KEY,
    dispositivo_id varchar(36) NOT NULL,
    proprietario_id varchar(36),
    client_mutation_id varchar(120) NOT NULL,
    correlacao_id varchar(120),
    entidade_tipo varchar(80) NOT NULL,
    entidade_id varchar(36),
    operacao varchar(80) NOT NULL,
    base_versao bigint,
    payload_json jsonb NOT NULL,
    payload_hash char(64),
    status varchar(40) NOT NULL DEFAULT 'PENDENTE',
    erro text,
    resultado_json jsonb,
    conflito_json jsonb,
    erro_categoria varchar(80),
    evento_servidor_commit_seq bigint,
    escopo_autorizacao_json jsonb,
    field_patch_json jsonb,
    base_values_json jsonb,
    evento_cliente_id varchar(36),
    causacao_id varchar(120),
    dependencias_json jsonb,
    criada_no_cliente_em timestamp(6) without time zone,
    recebida_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    aplicada_em timestamp(6) without time zone,
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_sync_mutacao_cliente UNIQUE (dispositivo_id, client_mutation_id),
    CONSTRAINT fk_sync_mutacao_dispositivo
        FOREIGN KEY (dispositivo_id) REFERENCES sync_dispositivo(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_mutacao_proprietario
        FOREIGN KEY (proprietario_id) REFERENCES colaborador(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_mutacao_evento_servidor_commit
        FOREIGN KEY (evento_servidor_commit_seq) REFERENCES cortex_evento_operacional(commit_seq)
        ON DELETE RESTRICT,
    CONSTRAINT chk_sync_mutacao_status CHECK (status IN (
        'PENDENTE', 'APLICADA', 'ERRO', 'DESCARTADA', 'CONFLITO', 'REJEITADA'
    ))
);

CREATE INDEX idx_sync_mutacao_dispositivo ON sync_mutacao_cliente (dispositivo_id);
CREATE INDEX idx_sync_mutacao_status_recebida ON sync_mutacao_cliente (status, recebida_em);
CREATE INDEX idx_sync_mutacao_entidade ON sync_mutacao_cliente (entidade_tipo, entidade_id);
CREATE INDEX idx_sync_mutacao_evento_servidor_commit
    ON sync_mutacao_cliente (evento_servidor_commit_seq);
CREATE INDEX idx_sync_mutacao_owner_status
    ON sync_mutacao_cliente (proprietario_id, status, recebida_em, id);
CREATE INDEX idx_sync_mutacao_correlation
    ON sync_mutacao_cliente (correlacao_id, recebida_em, id);
CREATE INDEX idx_sync_mutacao_owner_client_event
    ON sync_mutacao_cliente (proprietario_id, evento_cliente_id);
CREATE TRIGGER trg_sync_mutacao_cliente_touch_atualizado_em
BEFORE UPDATE ON sync_mutacao_cliente
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE stavia_contexto_obra (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    usuario_id varchar(120),
    descricao varchar(500),
    nome_arquivo varchar(255) NOT NULL,
    content_type varchar(120),
    tamanho_bytes bigint NOT NULL,
    hash_sha256 char(64) NOT NULL,
    storage_key varchar(512),
    texto_extraido text,
    status_processamento varchar(80) NOT NULL DEFAULT 'ARQUIVO_ARMAZENADO',
    ativo boolean NOT NULL DEFAULT true,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_stavia_contexto_obra_obra FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT uq_stavia_contexto_obra_hash UNIQUE (obra_id, hash_sha256),
    CONSTRAINT chk_stavia_contexto_status CHECK (status_processamento IN (
        'TEXTO_EXTRAIDO', 'ARQUIVO_ARMAZENADO', 'ARQUIVO_ARMAZENADO_SEM_EXTRACAO'
    ))
);

CREATE INDEX idx_stavia_contexto_obra
    ON stavia_contexto_obra (obra_id, ativo, criado_em);
CREATE INDEX idx_stavia_contexto_hash ON stavia_contexto_obra (hash_sha256);
CREATE TRIGGER trg_stavia_contexto_obra_touch_atualizado_em
BEFORE UPDATE ON stavia_contexto_obra
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE rdo_attachment (
    id varchar(36) PRIMARY KEY,
    rdo_id varchar(36) NOT NULL,
    obra_id varchar(36) NOT NULL,
    tipo varchar(40) NOT NULL DEFAULT 'FOTO',
    nome varchar(255) NOT NULL,
    nome_original varchar(255),
    mime_type varchar(120) NOT NULL,
    tamanho_original_bytes bigint NOT NULL DEFAULT 0,
    tamanho_comprimido_bytes bigint NOT NULL DEFAULT 0,
    tamanho_bytes bigint NOT NULL DEFAULT 0,
    storage_ref varchar(500),
    sync_status varchar(40) NOT NULL DEFAULT 'SYNCED',
    metadata_json jsonb,
    ultimo_erro text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    removido_em timestamp(6) without time zone,
    CONSTRAINT fk_rdo_attachment_rdo
        FOREIGN KEY (rdo_id) REFERENCES rdo(id) ON DELETE CASCADE,
    CONSTRAINT fk_rdo_attachment_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT chk_rdo_attachment_tipo CHECK (tipo IN ('FOTO', 'VIDEO')),
    CONSTRAINT chk_rdo_attachment_sync_status CHECK (sync_status IN (
        'LOCAL_ONLY', 'PENDING_SYNC', 'SYNCING', 'SYNCED', 'SYNC_FAILED'
    ))
);

CREATE INDEX idx_rdo_attachment_rdo ON rdo_attachment (rdo_id, criado_em);
CREATE INDEX idx_rdo_attachment_obra ON rdo_attachment (obra_id, criado_em);
CREATE INDEX idx_rdo_attachment_sync ON rdo_attachment (sync_status, atualizado_em);
CREATE TRIGGER trg_rdo_attachment_touch_atualizado_em
BEFORE UPDATE ON rdo_attachment
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE ontology_entities (
    id varchar(36) PRIMARY KEY,
    entity_type varchar(80) NOT NULL,
    external_ref_type varchar(120),
    external_ref_id varchar(160),
    canonical_name varchar(255) NOT NULL,
    description text,
    status varchar(80),
    metadata_json jsonb,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_ontology_entities_external
        UNIQUE (entity_type, external_ref_type, external_ref_id)
);

CREATE INDEX idx_ontology_entities_type_status ON ontology_entities (entity_type, status);
CREATE INDEX idx_ontology_entities_name ON ontology_entities (canonical_name);
CREATE INDEX idx_ontology_entities_external
    ON ontology_entities (external_ref_type, external_ref_id);
CREATE TRIGGER trg_ontology_entities_touch_updated_at
BEFORE UPDATE ON ontology_entities
FOR EACH ROW EXECUTE FUNCTION cortex_touch_updated_at();

CREATE TABLE ontology_relations (
    id varchar(36) PRIMARY KEY,
    source_entity_id varchar(36) NOT NULL,
    relation_type varchar(120) NOT NULL,
    target_entity_id varchar(36) NOT NULL,
    confidence numeric(9,6) NOT NULL DEFAULT 1.000000,
    metadata_json jsonb,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_ontology_relations_source
        FOREIGN KEY (source_entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE,
    CONSTRAINT fk_ontology_relations_target
        FOREIGN KEY (target_entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE,
    CONSTRAINT uq_ontology_relations_edge
        UNIQUE (source_entity_id, relation_type, target_entity_id)
);

CREATE INDEX idx_ontology_relations_source_type
    ON ontology_relations (source_entity_id, relation_type);
CREATE INDEX idx_ontology_relations_target_type
    ON ontology_relations (target_entity_id, relation_type);

CREATE TABLE ontology_events (
    id varchar(36) PRIMARY KEY,
    event_type varchar(120) NOT NULL,
    entity_id varchar(36) NOT NULL,
    related_entity_id varchar(36),
    source_type varchar(120),
    source_id varchar(160),
    description text NOT NULL,
    payload_json jsonb,
    occurred_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_ontology_events_entity
        FOREIGN KEY (entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE,
    CONSTRAINT fk_ontology_events_related_entity
        FOREIGN KEY (related_entity_id) REFERENCES ontology_entities(id) ON DELETE SET NULL,
    CONSTRAINT uq_ontology_events_source
        UNIQUE (event_type, source_type, source_id, entity_id)
);

CREATE INDEX idx_ontology_events_entity_time ON ontology_events (entity_id, occurred_at);
CREATE INDEX idx_ontology_events_type_time ON ontology_events (event_type, occurred_at);
CREATE INDEX idx_ontology_events_source ON ontology_events (source_type, source_id);

CREATE TABLE operational_states (
    id varchar(36) PRIMARY KEY,
    entity_id varchar(36) NOT NULL,
    state_type varchar(120) NOT NULL,
    state_value varchar(255),
    numeric_value numeric(18,6),
    unit varchar(40),
    valid_from timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    valid_to timestamp(6) without time zone,
    source_event_id varchar(36),
    metadata_json jsonb,
    CONSTRAINT fk_operational_states_entity
        FOREIGN KEY (entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE,
    CONSTRAINT fk_operational_states_event
        FOREIGN KEY (source_event_id) REFERENCES ontology_events(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_operational_states_current
    ON operational_states (entity_id, state_type)
    WHERE valid_to IS null;
CREATE INDEX idx_operational_states_entity_type
    ON operational_states (entity_id, state_type, valid_from);
CREATE INDEX idx_operational_states_type_value
    ON operational_states (state_type, numeric_value);

CREATE TABLE operational_evidences (
    id varchar(36) PRIMARY KEY,
    entity_id varchar(36) NOT NULL,
    evidence_type varchar(120) NOT NULL,
    source_type varchar(120) NOT NULL,
    source_id varchar(160) NOT NULL,
    description text NOT NULL,
    file_ref varchar(500),
    metadata_json jsonb,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_operational_evidences_entity
        FOREIGN KEY (entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE,
    CONSTRAINT uq_operational_evidence_source
        UNIQUE (entity_id, evidence_type, source_type, source_id)
);

CREATE INDEX idx_operational_evidences_entity_type
    ON operational_evidences (entity_id, evidence_type);
CREATE INDEX idx_operational_evidences_source
    ON operational_evidences (source_type, source_id);

CREATE TABLE operational_decisions (
    id varchar(36) PRIMARY KEY,
    entity_id varchar(36) NOT NULL,
    decision_type varchar(120) NOT NULL,
    description text NOT NULL,
    decided_by varchar(160),
    decided_at timestamp(6) without time zone,
    expected_impact text,
    metadata_json jsonb,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_operational_decisions_entity
        FOREIGN KEY (entity_id) REFERENCES ontology_entities(id) ON DELETE CASCADE
);

CREATE INDEX idx_operational_decisions_entity_type
    ON operational_decisions (entity_id, decision_type);
CREATE INDEX idx_operational_decisions_date ON operational_decisions (decided_at);

CREATE TABLE stavia_queries (
    id varchar(36) PRIMARY KEY,
    user_id varchar(160),
    query_text text NOT NULL,
    detected_intent varchar(120) NOT NULL,
    query_scope_json jsonb,
    entities_used_json jsonb,
    events_used_json jsonb,
    relations_used_json jsonb,
    response_text text NOT NULL,
    confidence numeric(9,6) NOT NULL DEFAULT 0.000000,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE INDEX idx_stavia_queries_user_time ON stavia_queries (user_id, created_at);
CREATE INDEX idx_stavia_queries_intent_time ON stavia_queries (detected_intent, created_at);

CREATE TABLE stavia_context_snapshots (
    id varchar(36) PRIMARY KEY,
    query_id varchar(36) NOT NULL,
    context_json jsonb NOT NULL,
    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_stavia_context_snapshots_query
        FOREIGN KEY (query_id) REFERENCES stavia_queries(id) ON DELETE CASCADE
);

CREATE INDEX idx_stavia_context_snapshots_query ON stavia_context_snapshots (query_id);

CREATE TABLE pdor_snapshot (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    codigo_obra varchar(80) NOT NULL,
    executado_em timestamp(6) without time zone NOT NULL,
    data_referencia date NOT NULL,
    versao_modelo varchar(40) NOT NULL,
    versao_premissas varchar(80) NOT NULL,
    status_execucao varchar(40) NOT NULL,
    tipo_disparo varchar(40) NOT NULL,
    iniciado_por varchar(120) NOT NULL,
    tipo_iniciador varchar(20) NOT NULL,
    evento_origem_id varchar(36),
    chave_idempotencia char(64) NOT NULL,
    versao_dados char(64) NOT NULL,
    inputs_json jsonb NOT NULL,
    origem_inputs_json jsonb NOT NULL,
    escopo_analise_json jsonb NOT NULL,
    janela_temporal_json jsonb NOT NULL,
    features_utilizadas_json jsonb NOT NULL,
    dados_ausentes_json jsonb NOT NULL,
    limitacoes_json jsonb NOT NULL,
    alertas_json jsonb NOT NULL,
    recomendacoes_json jsonb NOT NULL,
    comparacao_anterior_json jsonb NOT NULL,
    evidencias_json jsonb NOT NULL,
    warnings_json jsonb NOT NULL,
    modo_calculo varchar(40),
    calibracao varchar(40),
    fase_obra varchar(40),
    nivel_risco varchar(40),
    p10_receita numeric(18,2),
    p50_receita numeric(18,2),
    p80_receita numeric(18,2),
    p95_receita numeric(18,2),
    rac_rci numeric(18,2),
    rac_rci_spi numeric(18,2),
    rac_bottom_up numeric(18,2),
    rac_ponderado numeric(18,2),
    rci numeric(12,6),
    spi numeric(12,6),
    prob_abaixo_contrato numeric(9,6),
    prob_abaixo_95_pct numeric(9,6),
    prob_abaixo_90_pct numeric(9,6),
    score_heuristico numeric(9,6),
    confianca numeric(9,6),
    simulacao_convergiu boolean,
    iteracoes_simulacao integer,
    drivers_json jsonb NOT NULL,
    erro_execucao text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_pdor_snapshot_chave_idempotencia UNIQUE (chave_idempotencia),
    CONSTRAINT fk_pdor_snapshot_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pdor_snapshot_evento_origem
        FOREIGN KEY (evento_origem_id) REFERENCES cortex_evento_operacional(id) ON DELETE RESTRICT,
    CONSTRAINT chk_pdor_snapshot_status_execucao
        CHECK (status_execucao IN ('SUCCESS', 'INSUFFICIENT_DATA', 'FAILED')),
    CONSTRAINT chk_pdor_snapshot_tipo_disparo
        CHECK (tipo_disparo IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API')),
    CONSTRAINT chk_pdor_snapshot_tipo_iniciador
        CHECK (tipo_iniciador IN ('USER', 'PROCESS', 'UNKNOWN'))
);

CREATE INDEX idx_pdor_snapshot_obra_execucao
    ON pdor_snapshot (obra_id, executado_em, criado_em, id);
CREATE INDEX idx_pdor_snapshot_obra_referencia
    ON pdor_snapshot (obra_id, data_referencia);
CREATE INDEX idx_pdor_snapshot_codigo_obra_execucao
    ON pdor_snapshot (codigo_obra, executado_em);
CREATE INDEX idx_pdor_snapshot_status ON pdor_snapshot (status_execucao, executado_em);
CREATE INDEX idx_pdor_snapshot_versao_dados ON pdor_snapshot (obra_id, versao_dados);

CREATE TABLE previsao_financeira_snapshot (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    codigo_obra varchar(80) NOT NULL,
    data_referencia date NOT NULL,
    executado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_modelo varchar(40) NOT NULL,
    versao_premissas varchar(80) NOT NULL,
    status_execucao varchar(40) NOT NULL,
    status_calculo varchar(40) NOT NULL,
    tipo_disparo varchar(40) NOT NULL DEFAULT 'MANUAL',
    evento_origem_id varchar(36),
    chave_idempotencia char(64) NOT NULL,
    receita_estimada_dia numeric(18,2),
    receita_estimada_acumulada numeric(18,2),
    receita_validada_acumulada numeric(18,2),
    receita_medida numeric(18,2),
    receita_aprovada numeric(18,2),
    receita_faturada numeric(18,2),
    receita_recebida numeric(18,2),
    receita_prevista_final numeric(18,2),
    custo_realizado numeric(18,2),
    custo_comprometido numeric(18,2),
    custo_previsto_final numeric(18,2),
    margem_atual numeric(18,2),
    margem_prevista numeric(18,2),
    margem_percentual numeric(12,6),
    producao_planejada numeric(18,3),
    producao_realizada numeric(18,3),
    backlog_contratual numeric(18,2),
    receita_em_risco numeric(18,2),
    confianca numeric(9,6),
    qualidade_dados numeric(9,6),
    warnings_json jsonb NOT NULL,
    inputs_json jsonb NOT NULL,
    fontes_json jsonb NOT NULL,
    erro_execucao text,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_previsao_financeira_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id) ON DELETE RESTRICT,
    CONSTRAINT fk_previsao_financeira_evento_origem
        FOREIGN KEY (evento_origem_id) REFERENCES cortex_evento_operacional(id) ON DELETE RESTRICT,
    CONSTRAINT uq_previsao_financeira_idempotencia UNIQUE (chave_idempotencia),
    CONSTRAINT chk_previsao_financeira_status_execucao CHECK (status_execucao IN (
        'NAO_EXECUTADO', 'SEM_SNAPSHOT', 'DADOS_INSUFICIENTES', 'CALCULADO', 'ERRO_DE_EXECUCAO'
    )),
    CONSTRAINT chk_previsao_financeira_status_calculo
        CHECK (status_calculo IN ('NAO_CALIBRADO', 'CALIBRADO', 'DADOS_INSUFICIENTES')),
    CONSTRAINT chk_previsao_financeira_tipo_disparo
        CHECK (tipo_disparo IN ('MANUAL', 'EVENT', 'SCHEDULED', 'API'))
);

CREATE INDEX idx_previsao_financeira_obra_execucao
    ON previsao_financeira_snapshot (obra_id, executado_em, criado_em, id);
CREATE INDEX idx_previsao_financeira_obra_referencia
    ON previsao_financeira_snapshot (obra_id, data_referencia);
CREATE INDEX idx_previsao_financeira_status
    ON previsao_financeira_snapshot (status_execucao, executado_em);

CREATE TABLE obra_geometria (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    categoria varchar(40) NOT NULL,
    objeto_tipo varchar(40),
    objeto_id varchar(36),
    tipo_geometria varchar(24) NOT NULL,
    geometria_json jsonb NOT NULL,
    propriedades_json jsonb NOT NULL,
    fonte varchar(80) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ATIVA',
    valido_desde timestamp(6) without time zone NOT NULL,
    valido_ate timestamp(6) without time zone,
    motivo_encerramento varchar(500),
    versao_linha bigint NOT NULL DEFAULT 0,
    criado_por varchar(36) NOT NULL,
    atualizado_por varchar(36) NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL,
    atualizado_em timestamp(6) without time zone NOT NULL,
    CONSTRAINT fk_obra_geometria_obra FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_obra_geometria_criado_por
        FOREIGN KEY (criado_por) REFERENCES colaborador(id),
    CONSTRAINT fk_obra_geometria_atualizado_por
        FOREIGN KEY (atualizado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_obra_geometria_categoria CHECK (categoria IN (
        'LOCALIZACAO_OBRA', 'PERIMETRO_OBRA', 'TRECHO', 'PONTO_OPERACIONAL',
        'FRENTE_TRABALHO', 'EQUIPAMENTO', 'EVENTO', 'RDO', 'OCORRENCIA', 'PROGRAMACAO'
    )),
    CONSTRAINT chk_obra_geometria_tipo CHECK (tipo_geometria IN (
        'POINT', 'MULTIPOINT', 'LINESTRING', 'MULTILINESTRING',
        'POLYGON', 'MULTIPOLYGON'
    )),
    CONSTRAINT chk_obra_geometria_status CHECK (status IN ('ATIVA', 'ENCERRADA')),
    CONSTRAINT chk_obra_geometria_vigencia CHECK (valido_ate IS NULL OR valido_ate >= valido_desde),
    CONSTRAINT chk_obra_geometria_objeto CHECK (
        (objeto_tipo IS NULL AND objeto_id IS NULL)
        OR (objeto_tipo IS NOT NULL AND objeto_id IS NOT NULL)
    )
);

CREATE INDEX idx_obra_geometria_obra_status_categoria
    ON obra_geometria (obra_id, status, categoria, valido_desde, id);
CREATE INDEX idx_obra_geometria_objeto ON obra_geometria (objeto_tipo, objeto_id, status);
CREATE INDEX idx_obra_geometria_atualizacao ON obra_geometria (atualizado_em, id);

-- V26--V30: explicit access, authentication, object storage, messaging and teams.
-- The clean baseline deliberately starts these operational domains empty.
CREATE TABLE vinculo_colaborador_obra (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL REFERENCES obra(id),
    colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id),
    status varchar(20) NOT NULL DEFAULT 'ATIVO', papel_na_obra varchar(40) NOT NULL DEFAULT 'OPERACIONAL',
    atribuido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atribuido_por varchar(120),
    revogado_em timestamp(6) without time zone, revogado_por varchar(120), metadados_json jsonb,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_vinculo_colaborador_obra UNIQUE (colaborador_id, obra_id),
    CONSTRAINT chk_vinculo_colaborador_obra_status CHECK (status IN ('ATIVO', 'REVOGADO'))
);
CREATE INDEX idx_vinculo_colaborador_obra_colaborador ON vinculo_colaborador_obra (colaborador_id, status);
CREATE INDEX idx_vinculo_colaborador_obra_obra ON vinculo_colaborador_obra (obra_id, status);
CREATE TRIGGER trg_vinculo_colaborador_obra_touch_atualizado_em BEFORE UPDATE ON vinculo_colaborador_obra FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE auth_email_challenge (
    id varchar(36) PRIMARY KEY, colaborador_id varchar(36) REFERENCES colaborador(id),
    identifier_digest char(64) NOT NULL, codigo_digest char(64) NOT NULL,
    expira_em timestamp(6) without time zone NOT NULL, tentativas smallint NOT NULL DEFAULT 0,
    max_tentativas smallint NOT NULL, status varchar(20) NOT NULL DEFAULT 'PENDENTE',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), consumido_em timestamp(6) without time zone,
    CONSTRAINT chk_auth_email_challenge_status CHECK (status IN ('PENDENTE', 'CONSUMIDO', 'EXPIRADO', 'BLOQUEADO')),
    CONSTRAINT chk_auth_email_challenge_attempts CHECK (tentativas >= 0 AND max_tentativas > 0)
);
CREATE INDEX idx_auth_email_challenge_identifier_time ON auth_email_challenge (identifier_digest, criado_em);
CREATE INDEX idx_auth_email_challenge_expiry ON auth_email_challenge (status, expira_em);
CREATE INDEX idx_auth_email_challenge_retention ON auth_email_challenge (expira_em, id);

CREATE TABLE auth_rate_limit_bucket (
    bucket_key char(64) PRIMARY KEY, janela_inicio timestamp(6) without time zone NOT NULL,
    contador integer NOT NULL, bloqueado_ate timestamp(6) without time zone,
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_auth_rate_limit_contador CHECK (contador >= 0)
);
CREATE INDEX idx_auth_rate_limit_bucket_updated ON auth_rate_limit_bucket (atualizado_em, bucket_key);
CREATE TRIGGER trg_auth_rate_limit_bucket_touch_atualizado_em BEFORE UPDATE ON auth_rate_limit_bucket FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE auth_session (
    id varchar(36) PRIMARY KEY, colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id),
    token_hash char(64) NOT NULL UNIQUE, csrf_hash char(64) NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), expira_em timestamp(6) without time zone NOT NULL,
    visto_por_ultimo_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), revogado_em timestamp(6) without time zone,
    revogado_motivo varchar(120), dispositivo_id varchar(36), CONSTRAINT chk_auth_session_expiry CHECK (expira_em >= criado_em)
);
CREATE INDEX idx_auth_session_user_active ON auth_session (colaborador_id, revogado_em, expira_em);
CREATE INDEX idx_auth_session_expiry_retention ON auth_session (expira_em, id);
CREATE INDEX idx_auth_session_revoked_retention ON auth_session (revogado_em, id);

CREATE TABLE auth_webauthn_challenge (
    id varchar(36) PRIMARY KEY, colaborador_id varchar(36) REFERENCES colaborador(id), ceremony varchar(20) NOT NULL,
    challenge_hash char(64) NOT NULL, request_json jsonb NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), expira_em timestamp(6) without time zone NOT NULL,
    consumido_em timestamp(6) without time zone,
    CONSTRAINT chk_auth_webauthn_challenge_ceremony CHECK (ceremony IN ('REGISTRATION', 'AUTHENTICATION'))
);
CREATE INDEX idx_auth_webauthn_challenge_retention ON auth_webauthn_challenge (expira_em, id);

CREATE TABLE auth_provisioning_receipt (
    arquivo_digest char(64) PRIMARY KEY, processado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    identidades_processadas integer NOT NULL, CONSTRAINT chk_auth_provisioning_receipt_count CHECK (identidades_processadas >= 0)
);

CREATE TABLE stored_object (
    id varchar(36) PRIMARY KEY, proprietario_id varchar(36) NOT NULL REFERENCES colaborador(id), obra_id varchar(36) REFERENCES obra(id),
    dedupe_key char(64) NOT NULL UNIQUE, sha256 char(64) NOT NULL, storage_backend varchar(20) NOT NULL, storage_key varchar(700) NOT NULL UNIQUE,
    nome_original varchar(255), media_type_declarado varchar(160), media_type_detectado varchar(160) NOT NULL, tamanho_bytes bigint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DISPONIVEL', criado_por varchar(36) NOT NULL REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), disponivel_em timestamp(6) without time zone,
    arquivado_em timestamp(6) without time zone, arquivado_por varchar(36) REFERENCES colaborador(id), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_stored_object_id_obra UNIQUE (id, obra_id),
    CONSTRAINT chk_stored_object_backend CHECK (storage_backend IN ('LOCAL', 'S3')),
    CONSTRAINT chk_stored_object_status CHECK (status IN ('TEMPORARIO', 'DISPONIVEL', 'QUARENTENA', 'ARQUIVADO')),
    CONSTRAINT chk_stored_object_size CHECK (tamanho_bytes >= 0)
);
CREATE INDEX idx_stored_object_owner_created ON stored_object (proprietario_id, criado_em, id);
CREATE INDEX idx_stored_object_worksite_created ON stored_object (obra_id, criado_em, id);
CREATE INDEX idx_stored_object_hash_size ON stored_object (sha256, tamanho_bytes);
CREATE INDEX idx_stored_object_status_created ON stored_object (status, criado_em, id);

CREATE TABLE cortex_evento_visibilidade (
    id varchar(36) PRIMARY KEY, evento_id varchar(36) NOT NULL REFERENCES cortex_evento_operacional(id) ON DELETE CASCADE,
    escopo_tipo varchar(40) NOT NULL, escopo_id varchar(120) NOT NULL, capacidade varchar(40) NOT NULL DEFAULT '',
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_cortex_evento_visibility UNIQUE (evento_id, escopo_tipo, escopo_id, capacidade),
    CONSTRAINT chk_cortex_evento_visibility_scope CHECK (escopo_tipo IN ('GLOBAL_ALFA', 'WORKSITE', 'CONVERSATION_PARTICIPANT', 'FINANCIAL_CAPABILITY'))
);
CREATE INDEX idx_cortex_evento_visibility_scope ON cortex_evento_visibilidade (escopo_tipo, escopo_id, capacidade, evento_id);

CREATE TABLE funcao_operacional (
    id varchar(36) PRIMARY KEY, codigo varchar(80) NOT NULL UNIQUE, nome varchar(160) NOT NULL, descricao text,
    ativo boolean NOT NULL DEFAULT true, ordem_exibicao integer NOT NULL DEFAULT 0, criado_por varchar(120) NOT NULL, atualizado_por varchar(120) NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0, CONSTRAINT chk_funcao_operacional_ordem CHECK (ordem_exibicao >= 0)
);
CREATE INDEX idx_funcao_operacional_ativo_ordem ON funcao_operacional (ativo, ordem_exibicao, nome, id);
CREATE TRIGGER trg_funcao_operacional_touch_atualizado_em BEFORE UPDATE ON funcao_operacional FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE equipe (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), obra_principal_id varchar(36) NOT NULL REFERENCES obra(id), nome varchar(160) NOT NULL, descricao varchar(500),
    status varchar(20) NOT NULL DEFAULT 'ATIVA', inicio_validade_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    fim_validade_em timestamp(6) without time zone, arquivada_em timestamp(6) without time zone,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), atualizado_por varchar(36) NOT NULL REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletado_em timestamp(6) without time zone, deletado_por varchar(36) REFERENCES colaborador(id), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_equipe_obra_nome UNIQUE (obra_id, nome), CONSTRAINT chk_equipe_status CHECK (status IN ('ATIVA', 'INATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_equipe_validade CHECK (fim_validade_em IS NULL OR fim_validade_em >= inicio_validade_em)
);
CREATE INDEX idx_equipe_obra_status ON equipe (obra_principal_id, status, atualizado_em, id);
CREATE TRIGGER trg_equipe_touch_atualizado_em BEFORE UPDATE ON equipe FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE equipe_membro (
    id varchar(36) PRIMARY KEY, equipe_id varchar(36) NOT NULL REFERENCES equipe(id) ON DELETE RESTRICT,
    colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id), funcao_operacional_id varchar(36) REFERENCES funcao_operacional(id),
    responsavel boolean NOT NULL DEFAULT false, papel varchar(20) NOT NULL DEFAULT 'MEMBRO', status varchar(20) NOT NULL DEFAULT 'ATIVO',
    adicionado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), adicionado_por varchar(36) NOT NULL REFERENCES colaborador(id),
    removido_em timestamp(6) without time zone, removido_por varchar(36) REFERENCES colaborador(id),
    inicio_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), fim_em timestamp(6) without time zone, motivo_encerramento varchar(500),
    atribuido_por varchar(36) NOT NULL REFERENCES colaborador(id), atualizado_por varchar(36) NOT NULL REFERENCES colaborador(id), encerrado_por varchar(36) REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_equipe_membro_inicio UNIQUE (equipe_id, colaborador_id, inicio_em), CONSTRAINT chk_equipe_membro_papel CHECK (papel IN ('ADMIN', 'MEMBRO')),
    CONSTRAINT chk_equipe_membro_status CHECK (status IN ('ATIVO', 'REMOVIDO', 'ENCERRADO')),
    CONSTRAINT chk_equipe_membro_periodo CHECK (fim_em IS NULL OR fim_em >= inicio_em)
);
CREATE UNIQUE INDEX uq_equipe_membro_ativo ON equipe_membro (equipe_id, colaborador_id) WHERE status = 'ATIVO' AND fim_em IS NULL;
CREATE INDEX idx_equipe_membro_equipe_status ON equipe_membro (equipe_id, status, inicio_em, id);
CREATE INDEX idx_equipe_membro_colaborador_status ON equipe_membro (colaborador_id, status, inicio_em, id);
CREATE INDEX idx_equipe_membro_funcao_status ON equipe_membro (funcao_operacional_id, status, inicio_em, id);
CREATE TRIGGER trg_equipe_membro_touch_atualizado_em BEFORE UPDATE ON equipe_membro FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE equipe_obra (
    id varchar(36) PRIMARY KEY, equipe_id varchar(36) NOT NULL REFERENCES equipe(id) ON DELETE RESTRICT, obra_id varchar(36) NOT NULL REFERENCES obra(id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL DEFAULT 'ATIVO', inicio_em timestamp(6) without time zone NOT NULL, fim_em timestamp(6) without time zone,
    motivo_encerramento varchar(500), atribuido_por varchar(120) NOT NULL, encerrado_por varchar(120),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_equipe_obra_inicio UNIQUE (equipe_id, obra_id, inicio_em), CONSTRAINT chk_equipe_obra_status CHECK (status IN ('ATIVO', 'ENCERRADO')),
    CONSTRAINT chk_equipe_obra_periodo CHECK (fim_em IS NULL OR fim_em >= inicio_em),
    CONSTRAINT chk_equipe_obra_estado_temporal CHECK ((status = 'ATIVO' AND fim_em IS NULL) OR (status = 'ENCERRADO' AND fim_em IS NOT NULL))
);
CREATE UNIQUE INDEX uq_equipe_obra_ativa ON equipe_obra (equipe_id, obra_id) WHERE status = 'ATIVO' AND fim_em IS NULL;
CREATE INDEX idx_equipe_obra_obra_status ON equipe_obra (obra_id, status, inicio_em, id);
CREATE INDEX idx_equipe_obra_equipe_periodo ON equipe_obra (equipe_id, inicio_em, fim_em, id);
CREATE TRIGGER trg_equipe_obra_touch_atualizado_em BEFORE UPDATE ON equipe_obra FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE conversa (
    id varchar(36) PRIMARY KEY, tipo varchar(20) NOT NULL, obra_id varchar(36) REFERENCES obra(id), equipe_id varchar(36) REFERENCES equipe(id), titulo varchar(200),
    chave_participantes_direta char(64), status varchar(20) NOT NULL DEFAULT 'ATIVA', criado_por varchar(36) NOT NULL REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletado_em timestamp(6) without time zone, deletado_por varchar(36) REFERENCES colaborador(id), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_conversa_tipo CHECK (tipo IN ('DIRETA', 'GRUPO', 'EQUIPE', 'OBRA')), CONSTRAINT chk_conversa_status CHECK (status IN ('ATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_conversa_escopo CHECK (
        (tipo = 'DIRETA' AND chave_participantes_direta IS NOT NULL AND obra_id IS NULL AND equipe_id IS NULL) OR
        (tipo = 'GRUPO' AND chave_participantes_direta IS NULL AND obra_id IS NULL AND equipe_id IS NULL) OR
        (tipo = 'EQUIPE' AND chave_participantes_direta IS NULL AND obra_id IS NOT NULL AND equipe_id IS NOT NULL) OR
        (tipo = 'OBRA' AND chave_participantes_direta IS NULL AND obra_id IS NOT NULL AND equipe_id IS NULL)
    )
);
CREATE UNIQUE INDEX uq_conversa_direta_participantes ON conversa (chave_participantes_direta) WHERE chave_participantes_direta IS NOT NULL;
CREATE INDEX idx_conversa_obra_atualizado ON conversa (obra_id, status, atualizado_em, id);
CREATE INDEX idx_conversa_equipe_atualizado ON conversa (equipe_id, status, atualizado_em, id);
CREATE TRIGGER trg_conversa_touch_atualizado_em BEFORE UPDATE ON conversa FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE conversa_participante (
    id varchar(36) PRIMARY KEY, conversa_id varchar(36) NOT NULL REFERENCES conversa(id), colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id),
    papel varchar(20) NOT NULL DEFAULT 'MEMBRO', status varchar(20) NOT NULL DEFAULT 'ATIVO', adicionado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    adicionado_por varchar(36) NOT NULL REFERENCES colaborador(id), removido_em timestamp(6) without time zone, removido_por varchar(36) REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0, CONSTRAINT uq_conversa_participante UNIQUE (conversa_id, colaborador_id),
    CONSTRAINT chk_conversa_participante_papel CHECK (papel IN ('ADMIN', 'MEMBRO')), CONSTRAINT chk_conversa_participante_status CHECK (status IN ('ATIVO', 'REMOVIDO')),
    CONSTRAINT chk_conversa_participante_intervalo CHECK ((status = 'ATIVO' AND removido_em IS NULL) OR (status = 'REMOVIDO' AND removido_em IS NOT NULL))
);
CREATE INDEX idx_conversa_participante_colaborador ON conversa_participante (colaborador_id, status, atualizado_em, conversa_id);
CREATE TRIGGER trg_conversa_participante_touch_atualizado_em BEFORE UPDATE ON conversa_participante FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE mensagem (
    id varchar(36) PRIMARY KEY, conversa_id varchar(36) NOT NULL REFERENCES conversa(id), autor_id varchar(36) NOT NULL REFERENCES colaborador(id), corpo text,
    status varchar(20) NOT NULL DEFAULT 'ATIVA', client_mutation_id varchar(120) NOT NULL, criado_cliente_em timestamp(6) without time zone NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), editado_em timestamp(6) without time zone, editado_por varchar(36) REFERENCES colaborador(id),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deletado_em timestamp(6) without time zone, deletado_por varchar(36) REFERENCES colaborador(id),
    versao_linha bigint NOT NULL DEFAULT 0, CONSTRAINT uq_mensagem_autor_mutation UNIQUE (autor_id, client_mutation_id),
    CONSTRAINT chk_mensagem_status CHECK (status IN ('ATIVA', 'EDITADA', 'EXCLUIDA'))
);
CREATE INDEX idx_mensagem_conversa_criado ON mensagem (conversa_id, criado_em DESC, id);
CREATE INDEX idx_mensagem_autor_criado ON mensagem (autor_id, criado_em DESC, id);
CREATE INDEX gin_mensagem_corpo_portuguese ON mensagem USING gin (to_tsvector('portuguese', coalesce(corpo, '')));
CREATE TRIGGER trg_mensagem_touch_atualizado_em BEFORE UPDATE ON mensagem FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE mensagem_anexo (
    id varchar(36) PRIMARY KEY, mensagem_id varchar(36) NOT NULL REFERENCES mensagem(id), stored_object_id varchar(36) NOT NULL REFERENCES stored_object(id),
    nome_exibicao varchar(255), ordem smallint NOT NULL DEFAULT 0, status varchar(20) NOT NULL DEFAULT 'ATIVO', criado_por varchar(36) NOT NULL REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletado_em timestamp(6) without time zone, deletado_por varchar(36) REFERENCES colaborador(id), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_mensagem_anexo_objeto UNIQUE (mensagem_id, stored_object_id), CONSTRAINT chk_mensagem_anexo_status CHECK (status IN ('ATIVO', 'REMOVIDO')),
    CONSTRAINT chk_mensagem_anexo_ordem CHECK (ordem >= 0)
);
CREATE INDEX idx_mensagem_anexo_mensagem_ordem ON mensagem_anexo (mensagem_id, status, ordem, id);
CREATE TRIGGER trg_mensagem_anexo_touch_atualizado_em BEFORE UPDATE ON mensagem_anexo FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

-- Finance is a clean, empty configuration and transaction domain. Composite
-- worksite keys preserve the authorization boundary of the Córtex domain.
CREATE TABLE finance_fornecedor (
    id varchar(36) PRIMARY KEY, razao_social varchar(255) NOT NULL, nome_fantasia varchar(255), cnpj_normalizado char(14) NOT NULL UNIQUE,
    email_financeiro varchar(320), telefone varchar(40), status varchar(20) NOT NULL DEFAULT 'ATIVO',
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_fin_fornecedor_cnpj CHECK (cnpj_normalizado ~ '^[0-9]{14}$'),
    CONSTRAINT chk_fin_fornecedor_status CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_fornecedor_arquivo CHECK ((status = 'ATIVO' AND arquivado_em IS NULL) OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL))
);
CREATE INDEX idx_fin_fornecedor_nome ON finance_fornecedor (razao_social, nome_fantasia);
CREATE INDEX idx_fin_fornecedor_status ON finance_fornecedor (status, atualizado_em);
CREATE TRIGGER trg_fin_fornecedor_touch_atualizado_em BEFORE UPDATE ON finance_fornecedor FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_fornecedor_obra (
    id varchar(36) PRIMARY KEY, fornecedor_id varchar(36) NOT NULL REFERENCES finance_fornecedor(id), obra_id varchar(36) NOT NULL REFERENCES obra(id),
    status varchar(20) NOT NULL DEFAULT 'ATIVO', criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone,
    CONSTRAINT uq_fin_fornecedor_obra UNIQUE (fornecedor_id, obra_id), CONSTRAINT chk_fin_fornecedor_obra_status CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_fornecedor_obra_arquivo CHECK ((status = 'ATIVO' AND arquivado_em IS NULL) OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL))
);
CREATE INDEX idx_fin_fornecedor_obra_scope ON finance_fornecedor_obra (obra_id, status, fornecedor_id);

CREATE TABLE finance_centro_custo (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), codigo varchar(80) NOT NULL, nome varchar(255) NOT NULL, descricao varchar(1000),
    responsavel_id varchar(36) REFERENCES colaborador(id), status varchar(20) NOT NULL DEFAULT 'ATIVO', criado_por varchar(36) NOT NULL REFERENCES colaborador(id),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0, CONSTRAINT uq_fin_centro_custo_obra_codigo UNIQUE (obra_id, codigo), CONSTRAINT uq_fin_centro_custo_id_obra UNIQUE (id, obra_id),
    CONSTRAINT chk_fin_centro_custo_status CHECK (status IN ('ATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_centro_custo_arquivo CHECK ((status = 'ATIVO' AND arquivado_em IS NULL) OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL))
);
CREATE INDEX idx_fin_centro_custo_obra_status ON finance_centro_custo (obra_id, status, nome);
CREATE TRIGGER trg_fin_centro_custo_touch_atualizado_em BEFORE UPDATE ON finance_centro_custo FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_categoria (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), codigo varchar(80) NOT NULL, nome varchar(255) NOT NULL, descricao varchar(1000),
    status varchar(20) NOT NULL DEFAULT 'ATIVA', criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_categoria_obra_codigo UNIQUE (obra_id, codigo), CONSTRAINT uq_fin_categoria_id_obra UNIQUE (id, obra_id),
    CONSTRAINT chk_fin_categoria_status CHECK (status IN ('ATIVA', 'ARQUIVADA')),
    CONSTRAINT chk_fin_categoria_arquivo CHECK ((status = 'ATIVA' AND arquivado_em IS NULL) OR (status = 'ARQUIVADA' AND arquivado_em IS NOT NULL))
);
CREATE INDEX idx_fin_categoria_obra_status ON finance_categoria (obra_id, status, nome);
CREATE TRIGGER trg_fin_categoria_touch_atualizado_em BEFORE UPDATE ON finance_categoria FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_status_definicao (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), agregado_tipo varchar(30) NOT NULL, codigo varchar(60) NOT NULL,
    nome varchar(120) NOT NULL, descricao varchar(500), ordem integer NOT NULL, terminal boolean NOT NULL DEFAULT false, ativo boolean NOT NULL DEFAULT true,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_em timestamp(6) without time zone,
    versao_linha bigint NOT NULL DEFAULT 0, CONSTRAINT uq_fin_status_obra_agregado_codigo UNIQUE (obra_id, agregado_tipo, codigo),
    CONSTRAINT uq_fin_status_id_obra_agregado UNIQUE (id, obra_id, agregado_tipo), CONSTRAINT uq_fin_status_id_obra UNIQUE (id, obra_id),
    CONSTRAINT chk_fin_status_agregado CHECK (agregado_tipo IN ('SOLICITACAO', 'COMPRA', 'NOTA_FISCAL', 'LANCAMENTO')),
    CONSTRAINT chk_fin_status_codigo CHECK (codigo ~ '^[A-Z][A-Z0-9_]{1,59}$'), CONSTRAINT chk_fin_status_ordem CHECK (ordem >= 0)
);
CREATE INDEX idx_fin_status_obra_agregado_ativo ON finance_status_definicao (obra_id, agregado_tipo, ativo, ordem);
CREATE TRIGGER trg_fin_status_definicao_touch_atualizado_em BEFORE UPDATE ON finance_status_definicao FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_status_transicao (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), agregado_tipo varchar(30) NOT NULL,
    status_origem_id varchar(36) NOT NULL, status_destino_id varchar(36) NOT NULL, exige_aprovacao boolean NOT NULL DEFAULT false, ativo boolean NOT NULL DEFAULT true,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_transicao_status UNIQUE (status_origem_id, status_destino_id),
    CONSTRAINT fk_fin_transicao_origem FOREIGN KEY (status_origem_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_transicao_destino FOREIGN KEY (status_destino_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT chk_fin_transicao_status_diferente CHECK (status_origem_id <> status_destino_id)
);
CREATE TRIGGER trg_fin_status_transicao_touch_atualizado_em BEFORE UPDATE ON finance_status_transicao FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_solicitacao_compra (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id), centro_custo_id varchar(36) NOT NULL,
    categoria_id varchar(36) NOT NULL, fornecedor_id varchar(36), solicitante_id varchar(36) NOT NULL REFERENCES colaborador(id), responsavel_compra_id varchar(36) REFERENCES colaborador(id),
    agregado_tipo varchar(30) NOT NULL DEFAULT 'SOLICITACAO', status_id varchar(36) NOT NULL, titulo varchar(255) NOT NULL, descricao text,
    prioridade varchar(20) NOT NULL, moeda char(3) NOT NULL, valor_previsto numeric(19,4) NOT NULL, valor_aprovado numeric(19,4), valor_contratado numeric(19,4), valor_pago numeric(19,4), necessario_em date,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_solicitacao_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT uq_fin_solicitacao_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_solicitacao_centro_obra FOREIGN KEY (centro_custo_id, obra_id) REFERENCES finance_centro_custo (id, obra_id),
    CONSTRAINT fk_fin_solicitacao_categoria_obra FOREIGN KEY (categoria_id, obra_id) REFERENCES finance_categoria (id, obra_id),
    CONSTRAINT fk_fin_solicitacao_status_obra FOREIGN KEY (status_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_solicitacao_fornecedor_obra FOREIGN KEY (fornecedor_id, obra_id) REFERENCES finance_fornecedor_obra (fornecedor_id, obra_id),
    CONSTRAINT chk_fin_solicitacao_prioridade CHECK (prioridade IN ('BAIXA', 'MEDIA', 'ALTA', 'URGENTE')), CONSTRAINT chk_fin_solicitacao_agregado CHECK (agregado_tipo = 'SOLICITACAO'),
    CONSTRAINT chk_fin_solicitacao_moeda CHECK (moeda ~ '^[A-Z]{3}$'), CONSTRAINT chk_fin_solicitacao_valores CHECK (valor_previsto >= 0 AND (valor_aprovado IS NULL OR valor_aprovado >= 0) AND (valor_contratado IS NULL OR valor_contratado >= 0) AND (valor_pago IS NULL OR valor_pago >= 0))
);
CREATE INDEX idx_fin_solicitacao_obra_status_periodo ON finance_solicitacao_compra (obra_id, status_id, criado_em);
CREATE INDEX idx_fin_solicitacao_responsavel ON finance_solicitacao_compra (responsavel_compra_id, criado_em);
CREATE TRIGGER trg_fin_solicitacao_compra_touch_atualizado_em BEFORE UPDATE ON finance_solicitacao_compra FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_solicitacao_item (
    id varchar(36) PRIMARY KEY, solicitacao_id varchar(36) NOT NULL REFERENCES finance_solicitacao_compra(id), ordem integer NOT NULL, descricao varchar(1000) NOT NULL,
    quantidade numeric(19,4) NOT NULL, unidade varchar(40) NOT NULL, valor_unitario_previsto numeric(19,4), valor_total_previsto numeric(19,4),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_solicitacao_item_ordem UNIQUE (solicitacao_id, ordem), CONSTRAINT chk_fin_solicitacao_item_valores CHECK (ordem >= 0 AND quantidade > 0 AND (valor_unitario_previsto IS NULL OR valor_unitario_previsto >= 0) AND (valor_total_previsto IS NULL OR valor_total_previsto >= 0))
);
CREATE TRIGGER trg_fin_solicitacao_item_touch_atualizado_em BEFORE UPDATE ON finance_solicitacao_item FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_compra (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, solicitacao_id varchar(36), obra_id varchar(36) NOT NULL REFERENCES obra(id), centro_custo_id varchar(36) NOT NULL,
    categoria_id varchar(36) NOT NULL, fornecedor_id varchar(36) NOT NULL, responsavel_compra_id varchar(36) NOT NULL REFERENCES colaborador(id), agregado_tipo varchar(30) NOT NULL DEFAULT 'COMPRA',
    status_id varchar(36) NOT NULL, numero_pedido varchar(120), descricao varchar(1000) NOT NULL, prioridade varchar(20) NOT NULL, moeda char(3) NOT NULL,
    valor_previsto numeric(19,4), valor_aprovado numeric(19,4), valor_contratado numeric(19,4) NOT NULL, valor_pago numeric(19,4), pedido_emitido_em date, entrega_prevista_em date,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_compra_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT uq_fin_compra_numero_obra UNIQUE (obra_id, numero_pedido), CONSTRAINT uq_fin_compra_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_compra_solicitacao_obra FOREIGN KEY (solicitacao_id, obra_id) REFERENCES finance_solicitacao_compra(id, obra_id),
    CONSTRAINT fk_fin_compra_centro_obra FOREIGN KEY (centro_custo_id, obra_id) REFERENCES finance_centro_custo (id, obra_id),
    CONSTRAINT fk_fin_compra_categoria_obra FOREIGN KEY (categoria_id, obra_id) REFERENCES finance_categoria (id, obra_id),
    CONSTRAINT fk_fin_compra_status_obra FOREIGN KEY (status_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao (id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_compra_fornecedor_obra FOREIGN KEY (fornecedor_id, obra_id) REFERENCES finance_fornecedor_obra (fornecedor_id, obra_id),
    CONSTRAINT chk_fin_compra_prioridade CHECK (prioridade IN ('BAIXA', 'MEDIA', 'ALTA', 'URGENTE')), CONSTRAINT chk_fin_compra_agregado CHECK (agregado_tipo = 'COMPRA'),
    CONSTRAINT chk_fin_compra_moeda CHECK (moeda ~ '^[A-Z]{3}$'), CONSTRAINT chk_fin_compra_valores CHECK (valor_contratado >= 0 AND (valor_previsto IS NULL OR valor_previsto >= 0) AND (valor_aprovado IS NULL OR valor_aprovado >= 0) AND (valor_pago IS NULL OR valor_pago >= 0))
);
CREATE INDEX idx_fin_compra_obra_status_periodo ON finance_compra (obra_id, status_id, criado_em);
CREATE TRIGGER trg_fin_compra_touch_atualizado_em BEFORE UPDATE ON finance_compra FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_compra_item (
    id varchar(36) PRIMARY KEY, compra_id varchar(36) NOT NULL REFERENCES finance_compra(id), solicitacao_item_id varchar(36) REFERENCES finance_solicitacao_item(id), ordem integer NOT NULL,
    descricao varchar(1000) NOT NULL, quantidade numeric(19,4) NOT NULL, unidade varchar(40) NOT NULL, valor_unitario numeric(19,4) NOT NULL, valor_total numeric(19,4) NOT NULL,
    natureza varchar(20) NOT NULL DEFAULT 'CONSUMO', criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_compra_item_ordem UNIQUE (compra_id, ordem), CONSTRAINT chk_fin_compra_item_valores CHECK (ordem >= 0 AND quantidade > 0 AND valor_unitario >= 0 AND valor_total >= 0),
    CONSTRAINT chk_fin_compra_item_natureza CHECK (natureza IN ('CONSUMO', 'CAPITALIZAVEL'))
);
CREATE TRIGGER trg_fin_compra_item_touch_atualizado_em BEFORE UPDATE ON finance_compra_item FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_regra_aprovacao (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), centro_custo_id varchar(36), categoria_id varchar(36), nome varchar(255) NOT NULL,
    prioridade varchar(20), moeda char(3) NOT NULL, valor_minimo numeric(19,4) NOT NULL, valor_maximo numeric(19,4), vigente_de timestamp(6) without time zone NOT NULL,
    vigente_ate timestamp(6) without time zone, ativo boolean NOT NULL DEFAULT true, criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_regra_id_obra UNIQUE (id, obra_id), CONSTRAINT fk_fin_regra_centro_obra FOREIGN KEY (centro_custo_id, obra_id) REFERENCES finance_centro_custo(id, obra_id),
    CONSTRAINT fk_fin_regra_categoria_obra FOREIGN KEY (categoria_id, obra_id) REFERENCES finance_categoria(id, obra_id), CONSTRAINT chk_fin_regra_prioridade CHECK (prioridade IS NULL OR prioridade IN ('BAIXA', 'MEDIA', 'ALTA', 'URGENTE')),
    CONSTRAINT chk_fin_regra_moeda CHECK (moeda ~ '^[A-Z]{3}$'), CONSTRAINT chk_fin_regra_valores CHECK (valor_minimo >= 0 AND (valor_maximo IS NULL OR valor_maximo >= valor_minimo)), CONSTRAINT chk_fin_regra_vigencia CHECK (vigente_ate IS NULL OR vigente_ate > vigente_de)
);
CREATE TRIGGER trg_fin_regra_aprovacao_touch_atualizado_em BEFORE UPDATE ON finance_regra_aprovacao FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_regra_aprovacao_etapa (
    id varchar(36) PRIMARY KEY, regra_id varchar(36) NOT NULL REFERENCES finance_regra_aprovacao(id), ordem integer NOT NULL, aprovador_tipo varchar(30) NOT NULL,
    aprovador_colaborador_id varchar(36) REFERENCES colaborador(id), permissao_requerida varchar(40), aprovacoes_necessarias integer NOT NULL DEFAULT 1,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_regra_etapa_ordem UNIQUE (regra_id, ordem), CONSTRAINT chk_fin_regra_etapa_tipo CHECK (aprovador_tipo IN ('COLABORADOR', 'PERMISSAO')),
    CONSTRAINT chk_fin_regra_etapa_alvo CHECK ((aprovador_tipo = 'COLABORADOR' AND aprovador_colaborador_id IS NOT NULL AND permissao_requerida IS NULL) OR (aprovador_tipo = 'PERMISSAO' AND aprovador_colaborador_id IS NULL AND permissao_requerida = 'FINANCEIRO_APROVAR')),
    CONSTRAINT chk_fin_regra_etapa_quantidade CHECK (ordem >= 1 AND aprovacoes_necessarias >= 1)
);
CREATE TRIGGER trg_fin_regra_aprovacao_etapa_touch_atualizado_em BEFORE UPDATE ON finance_regra_aprovacao_etapa FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_aprovacao (
    id varchar(36) PRIMARY KEY, agregado_tipo varchar(30) NOT NULL, solicitacao_id varchar(36), compra_id varchar(36), obra_id varchar(36) NOT NULL REFERENCES obra(id), regra_id varchar(36) NOT NULL,
    status_origem_id varchar(36) NOT NULL, status_destino_id varchar(36) NOT NULL, moeda char(3) NOT NULL, valor_submetido numeric(19,4) NOT NULL, status varchar(30) NOT NULL DEFAULT 'PENDENTE',
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), concluido_em timestamp(6) without time zone, atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_fin_aprovacao_regra_obra FOREIGN KEY (regra_id, obra_id) REFERENCES finance_regra_aprovacao(id, obra_id),
    CONSTRAINT fk_fin_aprovacao_status_origem_obra FOREIGN KEY (status_origem_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_aprovacao_status_destino_obra FOREIGN KEY (status_destino_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_aprovacao_solicitacao_obra FOREIGN KEY (solicitacao_id, obra_id) REFERENCES finance_solicitacao_compra(id, obra_id),
    CONSTRAINT fk_fin_aprovacao_compra_obra FOREIGN KEY (compra_id, obra_id) REFERENCES finance_compra(id, obra_id),
    CONSTRAINT chk_fin_aprovacao_agregado CHECK ((agregado_tipo = 'SOLICITACAO' AND solicitacao_id IS NOT NULL AND compra_id IS NULL) OR (agregado_tipo = 'COMPRA' AND compra_id IS NOT NULL AND solicitacao_id IS NULL)),
    CONSTRAINT chk_fin_aprovacao_status CHECK (status IN ('PENDENTE', 'APROVADA', 'REJEITADA', 'CANCELADA')), CONSTRAINT chk_fin_aprovacao_valor CHECK (valor_submetido >= 0)
);
CREATE INDEX idx_fin_aprovacao_obra_status ON finance_aprovacao (obra_id, status, criado_em);
CREATE TRIGGER trg_fin_aprovacao_touch_atualizado_em BEFORE UPDATE ON finance_aprovacao FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_aprovacao_etapa (
    id varchar(36) PRIMARY KEY, aprovacao_id varchar(36) NOT NULL REFERENCES finance_aprovacao(id), regra_etapa_id varchar(36) NOT NULL REFERENCES finance_regra_aprovacao_etapa(id), ordem integer NOT NULL,
    aprovador_tipo varchar(30) NOT NULL, aprovador_colaborador_id varchar(36) REFERENCES colaborador(id), permissao_requerida varchar(40), aprovacoes_necessarias integer NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'PENDENTE', concluida_em timestamp(6) without time zone, criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_aprovacao_etapa_ordem UNIQUE (aprovacao_id, ordem), CONSTRAINT chk_fin_aprovacao_etapa_status CHECK (status IN ('PENDENTE', 'APROVADA', 'REJEITADA', 'CANCELADA')), CONSTRAINT chk_fin_aprovacao_etapa_quantidade CHECK (ordem >= 1 AND aprovacoes_necessarias >= 1)
);
CREATE TRIGGER trg_fin_aprovacao_etapa_touch_atualizado_em BEFORE UPDATE ON finance_aprovacao_etapa FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_aprovacao_decisao (
    id varchar(36) PRIMARY KEY, aprovacao_etapa_id varchar(36) NOT NULL REFERENCES finance_aprovacao_etapa(id), decisao varchar(20) NOT NULL, justificativa varchar(2000),
    decidido_por varchar(36) NOT NULL REFERENCES colaborador(id), client_mutation_id varchar(120) NOT NULL, dispositivo_id varchar(120), correlacao_id varchar(120), decidido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_decisao_mutacao UNIQUE (decidido_por, client_mutation_id), CONSTRAINT uq_fin_decisao_etapa_ator UNIQUE (aprovacao_etapa_id, decidido_por), CONSTRAINT chk_fin_decisao_tipo CHECK (decisao IN ('APROVAR', 'REJEITAR'))
);

CREATE TABLE finance_status_historico (
    id varchar(36) PRIMARY KEY, entidade_tipo varchar(30) NOT NULL, entidade_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id),
    status_anterior_id varchar(36), status_novo_id varchar(36) NOT NULL, ator_id varchar(36) NOT NULL REFERENCES colaborador(id), origem varchar(30) NOT NULL,
    dispositivo_id varchar(120), client_mutation_id varchar(120), correlacao_id varchar(120), estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL,
    resultado varchar(30) NOT NULL, erro_sanitizado varchar(1000), ocorrido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_historico_mutacao UNIQUE (ator_id, client_mutation_id, entidade_tipo),
    CONSTRAINT fk_fin_historico_status_anterior FOREIGN KEY (status_anterior_id, obra_id, entidade_tipo) REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT fk_fin_historico_status_novo FOREIGN KEY (status_novo_id, obra_id, entidade_tipo) REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT chk_fin_historico_entidade CHECK (entidade_tipo IN ('SOLICITACAO', 'COMPRA', 'NOTA_FISCAL', 'LANCAMENTO')), CONSTRAINT chk_fin_historico_origem CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')), CONSTRAINT chk_fin_historico_resultado CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
);
CREATE INDEX idx_fin_historico_entidade ON finance_status_historico (entidade_tipo, entidade_id, ocorrido_em);
CREATE INDEX idx_fin_historico_obra_periodo ON finance_status_historico (obra_id, ocorrido_em);

ALTER TABLE item_contratual
    ADD CONSTRAINT uq_item_contratual_id_obra UNIQUE (id, obra_id);

CREATE TABLE finance_documento_extracao_job (
    id varchar(36) PRIMARY KEY, stored_object_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id), client_mutation_id varchar(120) NOT NULL,
    formato varchar(20) NOT NULL, status varchar(30) NOT NULL DEFAULT 'PENDENTE', extrator varchar(80), extrator_versao varchar(40), sha256_cliente char(64), sha256_servidor char(64) NOT NULL,
    resultado_json jsonb, avisos_json jsonb, autorizacao_fiscal_status varchar(30) NOT NULL DEFAULT 'NAO_VERIFICADA', erro_sanitizado varchar(1000),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), dispositivo_id varchar(120), correlacao_id varchar(120), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    iniciado_em timestamp(6) without time zone, concluido_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_doc_extracao_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT uq_fin_doc_extracao_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_doc_extracao_objeto FOREIGN KEY (stored_object_id, obra_id) REFERENCES stored_object(id, obra_id),
    CONSTRAINT chk_fin_doc_extracao_formato CHECK (formato IN ('XML', 'PDF', 'IMAGEM')),
    CONSTRAINT chk_fin_doc_extracao_status CHECK (status IN ('PENDENTE', 'PROCESSANDO', 'EXTRAIDO', 'REVISAO_NECESSARIA', 'FALHA')),
    CONSTRAINT chk_fin_doc_extracao_autorizacao CHECK (autorizacao_fiscal_status IN ('NAO_VERIFICADA')),
    CONSTRAINT chk_fin_doc_extracao_tempos CHECK ((status = 'PENDENTE' AND iniciado_em IS NULL AND concluido_em IS NULL) OR (status = 'PROCESSANDO' AND iniciado_em IS NOT NULL AND concluido_em IS NULL) OR (status IN ('EXTRAIDO', 'REVISAO_NECESSARIA', 'FALHA') AND iniciado_em IS NOT NULL AND concluido_em IS NOT NULL))
);
CREATE INDEX idx_fin_doc_extracao_objeto ON finance_documento_extracao_job (stored_object_id, status, criado_em, id);
CREATE INDEX idx_fin_doc_extracao_obra ON finance_documento_extracao_job (obra_id, criado_em, id);

CREATE TABLE finance_documento_extracao_candidato (
    id varchar(36) PRIMARY KEY, job_id varchar(36) NOT NULL REFERENCES finance_documento_extracao_job(id) ON DELETE CASCADE, campo varchar(50) NOT NULL, ordem smallint NOT NULL DEFAULT 0,
    valor_normalizado_json jsonb NOT NULL, texto_original varchar(2000), extrator varchar(80) NOT NULL, extrator_versao varchar(40) NOT NULL, localizacao varchar(500),
    confianca numeric(7,6) NOT NULL, validacao_status varchar(30) NOT NULL, validacao_detalhe varchar(1000), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_doc_extracao_candidato UNIQUE (job_id, campo, ordem),
    CONSTRAINT chk_fin_doc_extracao_campo CHECK (campo IN ('numero', 'serie', 'chaveAcesso', 'tipoDocumento', 'dataEmissao', 'dataCompetencia', 'vencimentoEm', 'moeda', 'valorBruto', 'desconto', 'acrescimo', 'retencoes', 'valorLiquido', 'fornecedorCnpj', 'fornecedorNome')),
    CONSTRAINT chk_fin_doc_extracao_ordem CHECK (ordem >= 0), CONSTRAINT chk_fin_doc_extracao_confianca CHECK (confianca >= 0 AND confianca <= 1), CONSTRAINT chk_fin_doc_extracao_validacao CHECK (validacao_status IN ('VALIDO', 'DIVERGENTE', 'NAO_VERIFICADO'))
);

CREATE TABLE finance_nota_fiscal (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id), fornecedor_id varchar(36) NOT NULL,
    centro_custo_id varchar(36), categoria_id varchar(36), responsavel_id varchar(36) NOT NULL REFERENCES colaborador(id), agregado_tipo varchar(30) NOT NULL DEFAULT 'NOTA_FISCAL', status_id varchar(36) NOT NULL,
    numero varchar(80) NOT NULL, serie varchar(40) NOT NULL DEFAULT '', chave_acesso char(44), tipo_documento varchar(30) NOT NULL, data_emissao date NOT NULL, data_competencia date, data_recebimento date, vencimento_em date,
    moeda char(3) NOT NULL, valor_bruto numeric(19,4) NOT NULL, desconto numeric(19,4) NOT NULL DEFAULT 0, acrescimo numeric(19,4) NOT NULL DEFAULT 0, retencoes numeric(19,4) NOT NULL DEFAULT 0, valor_liquido numeric(19,4) NOT NULL,
    ocr_status varchar(30) NOT NULL DEFAULT 'NAO_CONFIGURADO', observacoes varchar(4000), criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_nf_id_obra UNIQUE (id, obra_id), CONSTRAINT uq_fin_nf_identidade_fornecedor UNIQUE (fornecedor_id, numero, serie), CONSTRAINT uq_fin_nf_chave_acesso UNIQUE (chave_acesso), CONSTRAINT uq_fin_nf_mutacao UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT fk_fin_nf_fornecedor_obra FOREIGN KEY (fornecedor_id, obra_id) REFERENCES finance_fornecedor_obra(fornecedor_id, obra_id),
    CONSTRAINT fk_fin_nf_centro_obra FOREIGN KEY (centro_custo_id, obra_id) REFERENCES finance_centro_custo(id, obra_id), CONSTRAINT fk_fin_nf_categoria_obra FOREIGN KEY (categoria_id, obra_id) REFERENCES finance_categoria(id, obra_id),
    CONSTRAINT fk_fin_nf_status_obra FOREIGN KEY (status_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT chk_fin_nf_agregado CHECK (agregado_tipo = 'NOTA_FISCAL'), CONSTRAINT chk_fin_nf_tipo CHECK (tipo_documento IN ('NFE', 'NFSE', 'CTE', 'RECIBO', 'OUTRO')),
    CONSTRAINT chk_fin_nf_chave CHECK (chave_acesso IS NULL OR chave_acesso ~ '^[0-9]{44}$'), CONSTRAINT chk_fin_nf_moeda CHECK (moeda ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_fin_nf_valores CHECK (valor_bruto >= 0 AND desconto >= 0 AND acrescimo >= 0 AND retencoes >= 0 AND valor_liquido >= 0 AND valor_liquido = valor_bruto - desconto + acrescimo - retencoes)
);
CREATE INDEX idx_fin_nf_obra_status_emissao ON finance_nota_fiscal (obra_id, status_id, data_emissao);
CREATE INDEX idx_fin_nf_vencimento ON finance_nota_fiscal (obra_id, vencimento_em, status_id);
CREATE TRIGGER trg_fin_nota_fiscal_touch_atualizado_em BEFORE UPDATE ON finance_nota_fiscal FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_nota_fiscal_compra (
    id varchar(36) PRIMARY KEY, nota_fiscal_id varchar(36) NOT NULL, compra_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, valor_vinculado numeric(19,4) NOT NULL,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_nf_compra UNIQUE (nota_fiscal_id, compra_id), CONSTRAINT fk_fin_nf_compra_nf FOREIGN KEY (nota_fiscal_id, obra_id) REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_nf_compra_compra FOREIGN KEY (compra_id, obra_id) REFERENCES finance_compra(id, obra_id), CONSTRAINT chk_fin_nf_compra_valor CHECK (valor_vinculado > 0),
    CONSTRAINT chk_fin_nf_compra_arquivo CHECK ((arquivado_em IS NULL AND arquivado_por IS NULL) OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL))
);
CREATE INDEX idx_fin_nf_compra_compra ON finance_nota_fiscal_compra (compra_id, obra_id);

CREATE TABLE finance_nota_fiscal_documento (
    id varchar(36) PRIMARY KEY, nota_fiscal_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, stored_object_id varchar(36) NOT NULL, tipo_documento varchar(30) NOT NULL, principal boolean NOT NULL DEFAULT false,
    enviado_por varchar(36) NOT NULL REFERENCES colaborador(id), enviado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), dispositivo_id varchar(120), client_mutation_id varchar(120), sha256_cliente char(64), sha256_servidor char(64) NOT NULL,
    inspecao_status varchar(30) NOT NULL, extracao_job_id varchar(36), extrator_versao varchar(40), confirmado_por varchar(36) NOT NULL REFERENCES colaborador(id), confirmado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    confirmacao_client_mutation_id varchar(120) NOT NULL, confirmacao_dispositivo_id varchar(120), confirmacao_correlacao_id varchar(120),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_nf_documento_objeto UNIQUE (nota_fiscal_id, stored_object_id), CONSTRAINT uq_fin_nf_documento_mutacao UNIQUE (enviado_por, client_mutation_id), CONSTRAINT uq_fin_nf_documento_confirmacao_mutacao UNIQUE (confirmado_por, confirmacao_client_mutation_id),
    CONSTRAINT fk_fin_nf_documento_nf FOREIGN KEY (nota_fiscal_id, obra_id) REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT fk_fin_nf_documento_objeto FOREIGN KEY (stored_object_id, obra_id) REFERENCES stored_object(id, obra_id),
    CONSTRAINT fk_fin_nf_documento_extracao FOREIGN KEY (extracao_job_id, obra_id) REFERENCES finance_documento_extracao_job(id, obra_id),
    CONSTRAINT chk_fin_nf_documento_tipo CHECK (tipo_documento IN ('DANFE', 'XML', 'BOLETO', 'RECIBO', 'COMPROVANTE', 'OUTRO')),
    CONSTRAINT chk_fin_nf_documento_inspecao CHECK (inspecao_status IN ('APROVADO', 'QUARENTENA')),
    CONSTRAINT chk_fin_nf_documento_arquivo CHECK ((arquivado_em IS NULL AND arquivado_por IS NULL) OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL))
);
CREATE INDEX idx_fin_nf_documento_nf ON finance_nota_fiscal_documento (nota_fiscal_id, arquivado_em, principal, criado_em);
CREATE INDEX idx_fin_nf_documento_extracao ON finance_nota_fiscal_documento (extracao_job_id, nota_fiscal_id);

CREATE TABLE finance_nota_fiscal_historico (
    id varchar(36) PRIMARY KEY, nota_fiscal_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, acao varchar(40) NOT NULL, ator_id varchar(36) NOT NULL REFERENCES colaborador(id), origem varchar(30) NOT NULL,
    dispositivo_id varchar(120), client_mutation_id varchar(120), correlacao_id varchar(120), estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL, resultado varchar(30) NOT NULL, erro_sanitizado varchar(1000), ocorrido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_nf_historico_mutacao UNIQUE (ator_id, client_mutation_id), CONSTRAINT fk_fin_nf_historico_nf FOREIGN KEY (nota_fiscal_id, obra_id) REFERENCES finance_nota_fiscal(id, obra_id),
    CONSTRAINT chk_fin_nf_historico_origem CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')), CONSTRAINT chk_fin_nf_historico_resultado CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
);
CREATE INDEX idx_fin_nf_historico_entidade ON finance_nota_fiscal_historico (nota_fiscal_id, ocorrido_em, id);

CREATE TABLE finance_lancamento (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id), nota_fiscal_id varchar(36), fornecedor_id varchar(36), centro_custo_id varchar(36), categoria_id varchar(36),
    responsavel_id varchar(36) NOT NULL REFERENCES colaborador(id), agregado_tipo varchar(30) NOT NULL DEFAULT 'LANCAMENTO', status_id varchar(36) NOT NULL, tipo varchar(20) NOT NULL, origem varchar(30) NOT NULL,
    numero_documento varchar(120), descricao varchar(1000) NOT NULL, data_competencia date NOT NULL, data_emissao date, vencimento_em date NOT NULL, moeda char(3) NOT NULL,
    valor_original numeric(19,4) NOT NULL, desconto numeric(19,4) NOT NULL DEFAULT 0, juros numeric(19,4) NOT NULL DEFAULT 0, multa numeric(19,4) NOT NULL DEFAULT 0, valor_liquido numeric(19,4) NOT NULL, valor_liquidado numeric(19,4) NOT NULL DEFAULT 0,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_lancamento_id_obra UNIQUE (id, obra_id), CONSTRAINT uq_fin_lancamento_mutacao UNIQUE (criado_por, client_mutation_id),
    CONSTRAINT fk_fin_lancamento_nf FOREIGN KEY (nota_fiscal_id, obra_id) REFERENCES finance_nota_fiscal(id, obra_id), CONSTRAINT fk_fin_lancamento_fornecedor FOREIGN KEY (fornecedor_id, obra_id) REFERENCES finance_fornecedor_obra(fornecedor_id, obra_id),
    CONSTRAINT fk_fin_lancamento_centro FOREIGN KEY (centro_custo_id, obra_id) REFERENCES finance_centro_custo(id, obra_id), CONSTRAINT fk_fin_lancamento_categoria FOREIGN KEY (categoria_id, obra_id) REFERENCES finance_categoria(id, obra_id),
    CONSTRAINT fk_fin_lancamento_status FOREIGN KEY (status_id, obra_id, agregado_tipo) REFERENCES finance_status_definicao(id, obra_id, agregado_tipo),
    CONSTRAINT chk_fin_lancamento_agregado CHECK (agregado_tipo = 'LANCAMENTO'), CONSTRAINT chk_fin_lancamento_tipo CHECK (tipo IN ('PAGAR', 'RECEBER')), CONSTRAINT chk_fin_lancamento_origem CHECK (origem IN ('MANUAL', 'NOTA_FISCAL', 'COMPRA', 'AJUSTE')),
    CONSTRAINT chk_fin_lancamento_moeda CHECK (moeda ~ '^[A-Z]{3}$'), CONSTRAINT chk_fin_lancamento_valores CHECK (valor_original >= 0 AND desconto >= 0 AND juros >= 0 AND multa >= 0 AND valor_liquido >= 0 AND valor_liquidado >= 0 AND valor_liquidado <= valor_liquido AND valor_liquido = valor_original - desconto + juros + multa)
);
CREATE INDEX idx_fin_lancamento_obra_tipo_vencimento ON finance_lancamento (obra_id, tipo, vencimento_em, id);
CREATE INDEX idx_fin_lancamento_status_vencimento ON finance_lancamento (obra_id, status_id, vencimento_em, id);
CREATE TRIGGER trg_fin_lancamento_touch_atualizado_em BEFORE UPDATE ON finance_lancamento FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_lancamento_alocacao (
    id varchar(36) PRIMARY KEY, lancamento_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, centro_custo_id varchar(36) NOT NULL, categoria_id varchar(36) NOT NULL, valor_alocado numeric(19,4) NOT NULL, percentual numeric(9,6),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_lancamento_alocacao UNIQUE (lancamento_id, centro_custo_id, categoria_id), CONSTRAINT fk_fin_lanc_aloc_lancamento FOREIGN KEY (lancamento_id, obra_id) REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_lanc_aloc_centro FOREIGN KEY (centro_custo_id, obra_id) REFERENCES finance_centro_custo(id, obra_id), CONSTRAINT fk_fin_lanc_aloc_categoria FOREIGN KEY (categoria_id, obra_id) REFERENCES finance_categoria(id, obra_id),
    CONSTRAINT chk_fin_lanc_aloc_valor CHECK (valor_alocado > 0), CONSTRAINT chk_fin_lanc_aloc_percentual CHECK (percentual IS NULL OR (percentual > 0 AND percentual <= 100))
);
CREATE TRIGGER trg_fin_lancamento_alocacao_touch_atualizado_em BEFORE UPDATE ON finance_lancamento_alocacao FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_lancamento_orcamento_vinculo (
    id varchar(36) PRIMARY KEY, lancamento_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, item_contratual_id varchar(36) NOT NULL, valor_vinculado numeric(19,4) NOT NULL,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_lanc_orcamento_item UNIQUE (lancamento_id, item_contratual_id), CONSTRAINT fk_fin_lanc_orc_lancamento FOREIGN KEY (lancamento_id, obra_id) REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT fk_fin_lanc_orc_item FOREIGN KEY (item_contratual_id, obra_id) REFERENCES item_contratual(id, obra_id), CONSTRAINT chk_fin_lanc_orc_valor CHECK (valor_vinculado > 0)
);

CREATE TABLE finance_lancamento_historico (
    id varchar(36) PRIMARY KEY, lancamento_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, acao varchar(40) NOT NULL, ator_id varchar(36) NOT NULL REFERENCES colaborador(id), origem varchar(30) NOT NULL,
    dispositivo_id varchar(120), client_mutation_id varchar(120), correlacao_id varchar(120), estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL, resultado varchar(30) NOT NULL, erro_sanitizado varchar(1000), ocorrido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_lanc_historico_mutacao UNIQUE (ator_id, client_mutation_id), CONSTRAINT fk_fin_lanc_historico_lancamento FOREIGN KEY (lancamento_id, obra_id) REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT chk_fin_lanc_historico_origem CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')), CONSTRAINT chk_fin_lanc_historico_resultado CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
);
CREATE INDEX idx_fin_lanc_historico_entidade ON finance_lancamento_historico (lancamento_id, ocorrido_em, id);

CREATE TABLE finance_liquidacao (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id), tipo varchar(20) NOT NULL, status varchar(20) NOT NULL DEFAULT 'RASCUNHO',
    data_liquidacao date NOT NULL, moeda char(3) NOT NULL, valor_total numeric(19,4) NOT NULL, meio varchar(40), referencia_bancaria varchar(255), observacoes varchar(2000),
    efetivada_por varchar(36) REFERENCES colaborador(id), efetivada_em timestamp(6) without time zone, cancelada_por varchar(36) REFERENCES colaborador(id), cancelada_em timestamp(6) without time zone, motivo_cancelamento varchar(1000),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_liquidacao_id_obra UNIQUE (id, obra_id), CONSTRAINT uq_fin_liquidacao_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT chk_fin_liquidacao_tipo CHECK (tipo IN ('PAGAMENTO', 'RECEBIMENTO')),
    CONSTRAINT chk_fin_liquidacao_status CHECK (status IN ('RASCUNHO', 'AGENDADA', 'EFETIVADA', 'CANCELADA')), CONSTRAINT chk_fin_liquidacao_moeda CHECK (moeda ~ '^[A-Z]{3}$'), CONSTRAINT chk_fin_liquidacao_valor CHECK (valor_total > 0),
    CONSTRAINT chk_fin_liquidacao_efetivacao CHECK ((status = 'EFETIVADA' AND efetivada_por IS NOT NULL AND efetivada_em IS NOT NULL AND cancelada_por IS NULL AND cancelada_em IS NULL) OR (status = 'CANCELADA' AND cancelada_por IS NOT NULL AND cancelada_em IS NOT NULL AND motivo_cancelamento IS NOT NULL) OR (status IN ('RASCUNHO', 'AGENDADA') AND efetivada_por IS NULL AND efetivada_em IS NULL AND cancelada_por IS NULL AND cancelada_em IS NULL))
);
CREATE INDEX idx_fin_liquidacao_obra_data ON finance_liquidacao (obra_id, data_liquidacao, tipo, status, id);
CREATE TRIGGER trg_fin_liquidacao_touch_atualizado_em BEFORE UPDATE ON finance_liquidacao FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_liquidacao_lancamento (
    id varchar(36) PRIMARY KEY, liquidacao_id varchar(36) NOT NULL, lancamento_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, valor_aplicado numeric(19,4) NOT NULL,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_liquidacao_lancamento UNIQUE (liquidacao_id, lancamento_id), CONSTRAINT fk_fin_liq_lanc_liquidacao FOREIGN KEY (liquidacao_id, obra_id) REFERENCES finance_liquidacao(id, obra_id), CONSTRAINT fk_fin_liq_lanc_lancamento FOREIGN KEY (lancamento_id, obra_id) REFERENCES finance_lancamento(id, obra_id), CONSTRAINT chk_fin_liq_lanc_valor CHECK (valor_aplicado > 0)
);

CREATE TABLE finance_liquidacao_historico (
    id varchar(36) PRIMARY KEY, liquidacao_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, acao varchar(40) NOT NULL, ator_id varchar(36) NOT NULL REFERENCES colaborador(id), origem varchar(30) NOT NULL,
    dispositivo_id varchar(120), client_mutation_id varchar(120), correlacao_id varchar(120), estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL, resultado varchar(30) NOT NULL, erro_sanitizado varchar(1000), ocorrido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_liq_historico_mutacao UNIQUE (ator_id, client_mutation_id), CONSTRAINT fk_fin_liq_historico_liquidacao FOREIGN KEY (liquidacao_id, obra_id) REFERENCES finance_liquidacao(id, obra_id),
    CONSTRAINT chk_fin_liq_historico_origem CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')), CONSTRAINT chk_fin_liq_historico_resultado CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
);
CREATE INDEX idx_fin_liq_historico_entidade ON finance_liquidacao_historico (liquidacao_id, ocorrido_em, id);

CREATE TABLE finance_email_perfil_remetente (
    id varchar(36) PRIMARY KEY, codigo varchar(80) NOT NULL UNIQUE, nome varchar(255) NOT NULL, configuracao_remetente_chave varchar(120) NOT NULL UNIQUE, status varchar(20) NOT NULL DEFAULT 'ATIVO',
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_fin_email_remetente_status CHECK (status IN ('ATIVO', 'INATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_email_remetente_arquivo CHECK ((status = 'ARQUIVADO' AND arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL) OR (status <> 'ARQUIVADO' AND arquivado_em IS NULL AND arquivado_por IS NULL))
);
CREATE TRIGGER trg_fin_email_perfil_remetente_touch_atualizado_em BEFORE UPDATE ON finance_email_perfil_remetente FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_email_reply_to_permitido (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), colaborador_id varchar(36) REFERENCES colaborador(id), nome varchar(255) NOT NULL, email varchar(320) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ATIVO', criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_email_reply_obra UNIQUE (obra_id, email), CONSTRAINT uq_fin_email_reply_id_obra UNIQUE (id, obra_id), CONSTRAINT chk_fin_email_reply_status CHECK (status IN ('ATIVO', 'INATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_email_reply_formato CHECK (email LIKE '%_@_%._%' AND email NOT LIKE '% %'), CONSTRAINT chk_fin_email_reply_arquivo CHECK ((status = 'ARQUIVADO' AND arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL) OR (status <> 'ARQUIVADO' AND arquivado_em IS NULL AND arquivado_por IS NULL))
);
CREATE INDEX idx_fin_email_reply_obra_status ON finance_email_reply_to_permitido (obra_id, status, email);
CREATE TRIGGER trg_fin_email_reply_to_permitido_touch_atualizado_em BEFORE UPDATE ON finance_email_reply_to_permitido FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_modelo_email (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), codigo varchar(80) NOT NULL, nome varchar(255) NOT NULL, versao integer NOT NULL, status varchar(30) NOT NULL DEFAULT 'RASCUNHO',
    assunto_template varchar(500) NOT NULL, corpo_texto_template text NOT NULL, variaveis_permitidas_json jsonb NOT NULL, previsualizacao_hash char(64), previsualizado_por varchar(36) REFERENCES colaborador(id), previsualizado_em timestamp(6) without time zone,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_modelo_email_versao UNIQUE (obra_id, codigo, versao), CONSTRAINT uq_fin_modelo_email_id_obra UNIQUE (id, obra_id), CONSTRAINT chk_fin_modelo_email_versao CHECK (versao > 0),
    CONSTRAINT chk_fin_modelo_email_status CHECK (status IN ('RASCUNHO', 'PREVISUALIZADO', 'ATIVO', 'INATIVO', 'ARQUIVADO')),
    CONSTRAINT chk_fin_modelo_email_preview CHECK ((status IN ('ATIVO', 'PREVISUALIZADO') AND previsualizacao_hash IS NOT NULL AND previsualizado_por IS NOT NULL AND previsualizado_em IS NOT NULL) OR status NOT IN ('ATIVO', 'PREVISUALIZADO')),
    CONSTRAINT chk_fin_modelo_email_arquivo CHECK ((status = 'ARQUIVADO' AND arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL) OR (status <> 'ARQUIVADO' AND arquivado_em IS NULL AND arquivado_por IS NULL))
);
CREATE INDEX idx_fin_modelo_email_obra_status ON finance_modelo_email (obra_id, status, atualizado_em);
CREATE TRIGGER trg_fin_modelo_email_touch_atualizado_em BEFORE UPDATE ON finance_modelo_email FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_regra_cobranca (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, obra_id varchar(36) NOT NULL REFERENCES obra(id), modelo_email_id varchar(36) NOT NULL, perfil_remetente_id varchar(36) NOT NULL REFERENCES finance_email_perfil_remetente(id), reply_to_permitido_id varchar(36),
    nome varchar(255) NOT NULL, modo varchar(20) NOT NULL, referencia varchar(30) NOT NULL DEFAULT 'VENCIMENTO', deslocamento_dias integer NOT NULL DEFAULT 0, horario_local time, fuso_horario varchar(80) NOT NULL, vigente_de date, vigente_ate date, status varchar(30) NOT NULL DEFAULT 'RASCUNHO',
    previsualizacao_hash char(64), previsualizacao_confirmada_por varchar(36) REFERENCES colaborador(id), previsualizacao_confirmada_em timestamp(6) without time zone,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_regra_cobranca_nome UNIQUE (obra_id, nome), CONSTRAINT uq_fin_regra_cobranca_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT uq_fin_regra_cobranca_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_regra_cobranca_modelo FOREIGN KEY (modelo_email_id, obra_id) REFERENCES finance_modelo_email(id, obra_id), CONSTRAINT fk_fin_regra_cobranca_reply FOREIGN KEY (reply_to_permitido_id, obra_id) REFERENCES finance_email_reply_to_permitido(id, obra_id),
    CONSTRAINT chk_fin_regra_cobranca_modo CHECK (modo IN ('MANUAL', 'AGENDADA', 'AUTOMATICA')), CONSTRAINT chk_fin_regra_cobranca_referencia CHECK (referencia IN ('VENCIMENTO', 'EMISSAO', 'CRIACAO')),
    CONSTRAINT chk_fin_regra_cobranca_status CHECK (status IN ('RASCUNHO', 'PREVISUALIZADA', 'ATIVA', 'INATIVA', 'ARQUIVADA')), CONSTRAINT chk_fin_regra_cobranca_vigencia CHECK (vigente_ate IS NULL OR vigente_de IS NULL OR vigente_ate >= vigente_de),
    CONSTRAINT chk_fin_regra_cobranca_ativacao CHECK ((status = 'ATIVA' AND previsualizacao_hash IS NOT NULL AND previsualizacao_confirmada_por IS NOT NULL AND previsualizacao_confirmada_em IS NOT NULL) OR status <> 'ATIVA'),
    CONSTRAINT chk_fin_regra_cobranca_arquivo CHECK ((status = 'ARQUIVADA' AND arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL) OR (status <> 'ARQUIVADA' AND arquivado_em IS NULL AND arquivado_por IS NULL))
);
CREATE INDEX idx_fin_regra_cobranca_obra_status ON finance_regra_cobranca (obra_id, status, modo, vigente_de, vigente_ate);
CREATE TRIGGER trg_fin_regra_cobranca_touch_atualizado_em BEFORE UPDATE ON finance_regra_cobranca FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_cobranca_email (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, idempotency_key varchar(200) NOT NULL UNIQUE, obra_id varchar(36) NOT NULL REFERENCES obra(id), regra_id varchar(36), modelo_email_id varchar(36) NOT NULL, perfil_remetente_id varchar(36) NOT NULL REFERENCES finance_email_perfil_remetente(id), reply_to_permitido_id varchar(36), fornecedor_id varchar(36) NOT NULL,
    compra_id varchar(36), nota_fiscal_id varchar(36), lancamento_id varchar(36), responsavel_id varchar(36) NOT NULL REFERENCES colaborador(id), modo varchar(20) NOT NULL, status varchar(30) NOT NULL DEFAULT 'RASCUNHO', destinatario varchar(320) NOT NULL,
    vencimento_em date, ocorrencia_prevista_em timestamp(6) without time zone NOT NULL, agendada_para timestamp(6) without time zone, dados_renderizacao_json jsonb NOT NULL,
    previsualizacao_hash char(64), previsualizacao_confirmada_por varchar(36) REFERENCES colaborador(id), previsualizacao_confirmada_em timestamp(6) without time zone,
    tentativas integer NOT NULL DEFAULT 0, proxima_tentativa_em timestamp(6) without time zone, lock_token varchar(36), lock_ate timestamp(6) without time zone, provider varchar(80), provider_message_id varchar(255), enviada_em timestamp(6) without time zone, entregue_em timestamp(6) without time zone,
    erro_categoria varchar(80), erro_sanitizado varchar(1000), cancelada_por varchar(36) REFERENCES colaborador(id), cancelada_em timestamp(6) without time zone, motivo_cancelamento varchar(1000),
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    alvo_tipo varchar(20) GENERATED ALWAYS AS (CASE WHEN compra_id IS NOT NULL THEN 'COMPRA' WHEN nota_fiscal_id IS NOT NULL THEN 'NOTA_FISCAL' ELSE 'LANCAMENTO' END) STORED,
    alvo_id varchar(36) GENERATED ALWAYS AS (COALESCE(compra_id, nota_fiscal_id, lancamento_id)) STORED,
    CONSTRAINT uq_fin_cobranca_email_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT uq_fin_cobranca_email_regra_ocorrencia UNIQUE (regra_id, alvo_tipo, alvo_id, ocorrencia_prevista_em), CONSTRAINT uq_fin_cobranca_email_provider UNIQUE (provider, provider_message_id), CONSTRAINT uq_fin_cobranca_email_id_obra UNIQUE (id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_regra FOREIGN KEY (regra_id, obra_id) REFERENCES finance_regra_cobranca(id, obra_id), CONSTRAINT fk_fin_cobranca_email_modelo FOREIGN KEY (modelo_email_id, obra_id) REFERENCES finance_modelo_email(id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_reply FOREIGN KEY (reply_to_permitido_id, obra_id) REFERENCES finance_email_reply_to_permitido(id, obra_id), CONSTRAINT fk_fin_cobranca_email_fornecedor FOREIGN KEY (fornecedor_id, obra_id) REFERENCES finance_fornecedor_obra(fornecedor_id, obra_id),
    CONSTRAINT fk_fin_cobranca_email_compra FOREIGN KEY (compra_id, obra_id) REFERENCES finance_compra(id, obra_id), CONSTRAINT fk_fin_cobranca_email_nf FOREIGN KEY (nota_fiscal_id, obra_id) REFERENCES finance_nota_fiscal(id, obra_id), CONSTRAINT fk_fin_cobranca_email_lancamento FOREIGN KEY (lancamento_id, obra_id) REFERENCES finance_lancamento(id, obra_id),
    CONSTRAINT chk_fin_cobranca_email_modo CHECK (modo IN ('MANUAL', 'AGENDADA', 'AUTOMATICA')), CONSTRAINT chk_fin_cobranca_email_status CHECK (status IN ('RASCUNHO', 'PREVISUALIZADA', 'AGENDADA', 'NA_FILA', 'ENVIANDO', 'ENVIADA', 'FALHOU', 'ENTREGUE', 'CANCELADA')),
    CONSTRAINT chk_fin_cobranca_email_destinatario CHECK (destinatario LIKE '%_@_%._%' AND destinatario NOT LIKE '% %'), CONSTRAINT chk_fin_cobranca_email_alvo CHECK (num_nonnulls(compra_id, nota_fiscal_id, lancamento_id) = 1),
    CONSTRAINT chk_fin_cobranca_email_agendamento CHECK (modo <> 'AGENDADA' OR agendada_para IS NOT NULL), CONSTRAINT chk_fin_cobranca_email_tentativas CHECK (tentativas >= 0)
);
CREATE INDEX idx_fin_cobranca_email_fila ON finance_cobranca_email (status, proxima_tentativa_em, agendada_para, lock_ate);
CREATE INDEX idx_fin_cobranca_email_obra_status ON finance_cobranca_email (obra_id, status, ocorrencia_prevista_em);
CREATE TRIGGER trg_fin_cobranca_email_touch_atualizado_em BEFORE UPDATE ON finance_cobranca_email FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_cobranca_tentativa (
    id varchar(36) PRIMARY KEY, cobranca_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, numero_tentativa integer NOT NULL, idempotency_key varchar(200) NOT NULL UNIQUE,
    resultado varchar(20) NOT NULL, provider varchar(80), provider_message_id varchar(255), erro_categoria varchar(80), erro_sanitizado varchar(1000), proxima_tentativa_em timestamp(6) without time zone,
    iniciada_em timestamp(6) without time zone NOT NULL, concluida_em timestamp(6) without time zone NOT NULL, criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_cobranca_tentativa_numero UNIQUE (cobranca_id, numero_tentativa), CONSTRAINT uq_fin_cobranca_tentativa_provider UNIQUE (provider, provider_message_id),
    CONSTRAINT fk_fin_cobranca_tentativa_cobranca FOREIGN KEY (cobranca_id, obra_id) REFERENCES finance_cobranca_email(id, obra_id), CONSTRAINT chk_fin_cobranca_tentativa_numero CHECK (numero_tentativa > 0), CONSTRAINT chk_fin_cobranca_tentativa_resultado CHECK (resultado IN ('ENVIADA', 'FALHOU')), CONSTRAINT chk_fin_cobranca_tentativa_tempo CHECK (concluida_em >= iniciada_em)
);

CREATE TABLE finance_cobranca_evento_entrega (
    id varchar(36) PRIMARY KEY, cobranca_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, tentativa_id varchar(36) REFERENCES finance_cobranca_tentativa(id), provider varchar(80) NOT NULL, provider_event_id varchar(255) NOT NULL, provider_message_id varchar(255) NOT NULL,
    tipo_evento varchar(30) NOT NULL, payload_hash char(64) NOT NULL, ocorrido_em timestamp(6) without time zone NOT NULL, autenticado_em timestamp(6) without time zone NOT NULL, recebido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_cobranca_evento_provider UNIQUE (provider, provider_event_id), CONSTRAINT fk_fin_cobranca_evento_cobranca FOREIGN KEY (cobranca_id, obra_id) REFERENCES finance_cobranca_email(id, obra_id), CONSTRAINT chk_fin_cobranca_evento_tipo CHECK (tipo_evento IN ('ACEITA', 'ENTREGUE', 'FALHOU', 'REJEITADA')), CONSTRAINT chk_fin_cobranca_evento_autenticacao CHECK (autenticado_em <= recebido_em)
);
CREATE INDEX idx_fin_cobranca_evento_mensagem ON finance_cobranca_evento_entrega (provider, provider_message_id, ocorrido_em);

CREATE TABLE finance_cobranca_email_historico (
    id varchar(36) PRIMARY KEY, cobranca_id varchar(36) NOT NULL, obra_id varchar(36) NOT NULL, acao varchar(40) NOT NULL, ator_id varchar(36) NOT NULL REFERENCES colaborador(id), origem varchar(30) NOT NULL,
    dispositivo_id varchar(120), client_mutation_id varchar(120), correlacao_id varchar(120), estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL, resultado varchar(30) NOT NULL, erro_sanitizado varchar(1000), ocorrido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_fin_cobranca_historico_mutacao UNIQUE (ator_id, client_mutation_id), CONSTRAINT fk_fin_cobranca_historico_cobranca FOREIGN KEY (cobranca_id, obra_id) REFERENCES finance_cobranca_email(id, obra_id), CONSTRAINT chk_fin_cobranca_historico_origem CHECK (origem IN ('ONLINE', 'OFFLINE', 'SYNC', 'SISTEMA')), CONSTRAINT chk_fin_cobranca_historico_resultado CHECK (resultado IN ('SUCESSO', 'FALHA', 'CONFLITO'))
);
CREATE INDEX idx_fin_cobranca_historico_obra ON finance_cobranca_email_historico (obra_id, ocorrido_em, id);

CREATE TABLE finance_unidade_controle (
    id varchar(36) PRIMARY KEY, tipo varchar(30) NOT NULL, obra_id varchar(36) REFERENCES obra(id), ativo_id varchar(36) REFERENCES asset(id), codigo varchar(120) NOT NULL UNIQUE, nome varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ATIVA', origem varchar(30) NOT NULL, criado_por varchar(36) REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_unidade_obra UNIQUE (obra_id), CONSTRAINT uq_fin_unidade_ativo UNIQUE (ativo_id), CONSTRAINT uq_fin_unidade_id_ativo UNIQUE (id, ativo_id),
    CONSTRAINT chk_fin_unidade_tipo CHECK (tipo IN ('OBRA', 'ATIVO', 'ADMINISTRATIVO', 'CORPORATIVO')), CONSTRAINT chk_fin_unidade_status CHECK (status IN ('ATIVA', 'ARQUIVADA')), CONSTRAINT chk_fin_unidade_origem CHECK (origem IN ('MIGRACAO_OBRA', 'MANUAL', 'COMPRA_ATIVO')),
    CONSTRAINT chk_fin_unidade_alvo CHECK ((tipo = 'OBRA' AND obra_id IS NOT NULL AND ativo_id IS NULL) OR (tipo = 'ATIVO' AND obra_id IS NULL AND ativo_id IS NOT NULL) OR (tipo IN ('ADMINISTRATIVO', 'CORPORATIVO') AND obra_id IS NULL AND ativo_id IS NULL)),
    CONSTRAINT chk_fin_unidade_autoria CHECK ((origem = 'MIGRACAO_OBRA' AND criado_por IS NULL) OR (origem <> 'MIGRACAO_OBRA' AND criado_por IS NOT NULL)), CONSTRAINT chk_fin_unidade_arquivo CHECK ((status = 'ATIVA' AND arquivado_em IS NULL) OR (status = 'ARQUIVADA' AND arquivado_em IS NOT NULL))
);
CREATE INDEX idx_fin_unidade_tipo_status ON finance_unidade_controle (tipo, status, nome);
CREATE TRIGGER trg_fin_unidade_controle_touch_atualizado_em BEFORE UPDATE ON finance_unidade_controle FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE permissao_financeira_colaborador (
    id varchar(36) PRIMARY KEY, colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id), obra_id varchar(36) REFERENCES obra(id), unidade_controle_id varchar(36) NOT NULL REFERENCES finance_unidade_controle(id), permissao varchar(40) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ATIVA', concedido_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), concedido_por varchar(36) NOT NULL REFERENCES colaborador(id), revogado_em timestamp(6) without time zone, revogado_por varchar(36) REFERENCES colaborador(id), justificativa varchar(500) NOT NULL,
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_permissao_financeira_unidade UNIQUE (colaborador_id, unidade_controle_id, permissao), CONSTRAINT chk_permissao_financeira_status CHECK (status IN ('ATIVA', 'REVOGADA')),
    CONSTRAINT chk_permissao_financeira_tipo CHECK (permissao IN ('FINANCEIRO_VISUALIZAR', 'FINANCEIRO_OPERAR', 'FINANCEIRO_APROVAR', 'FINANCEIRO_ADMINISTRAR'))
);
CREATE INDEX idx_permissao_financeira_user_status ON permissao_financeira_colaborador (colaborador_id, status, permissao);
CREATE INDEX idx_permissao_financeira_obra_status ON permissao_financeira_colaborador (obra_id, status, permissao);
CREATE INDEX idx_permissao_financeira_unidade_status ON permissao_financeira_colaborador (unidade_controle_id, status, permissao);
CREATE TRIGGER trg_permissao_financeira_colaborador_touch_atualizado_em BEFORE UPDATE ON permissao_financeira_colaborador FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_rateio (
    id varchar(36) PRIMARY KEY, client_mutation_id varchar(120) NOT NULL, compra_id varchar(36) REFERENCES finance_compra(id), nota_fiscal_id varchar(36) REFERENCES finance_nota_fiscal(id), lancamento_id varchar(36) REFERENCES finance_lancamento(id),
    moeda char(3) NOT NULL, valor_total numeric(19,4) NOT NULL, status varchar(20) NOT NULL DEFAULT 'ATIVO', criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), arquivado_por varchar(36) REFERENCES colaborador(id), arquivado_em timestamp(6) without time zone, versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_rateio_mutacao UNIQUE (criado_por, client_mutation_id), CONSTRAINT uq_fin_rateio_compra UNIQUE (compra_id), CONSTRAINT uq_fin_rateio_nota UNIQUE (nota_fiscal_id), CONSTRAINT uq_fin_rateio_lancamento UNIQUE (lancamento_id),
    CONSTRAINT chk_fin_rateio_origem_unica CHECK (num_nonnulls(compra_id, nota_fiscal_id, lancamento_id) = 1), CONSTRAINT chk_fin_rateio_moeda CHECK (moeda ~ '^[A-Z]{3}$'), CONSTRAINT chk_fin_rateio_valor CHECK (valor_total > 0), CONSTRAINT chk_fin_rateio_status CHECK (status IN ('ATIVO', 'ARQUIVADO')), CONSTRAINT chk_fin_rateio_arquivo CHECK ((status = 'ATIVO' AND arquivado_em IS NULL) OR (status = 'ARQUIVADO' AND arquivado_em IS NOT NULL))
);
CREATE TRIGGER trg_fin_rateio_touch_atualizado_em BEFORE UPDATE ON finance_rateio FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_rateio_item (
    id varchar(36) PRIMARY KEY, rateio_id varchar(36) NOT NULL REFERENCES finance_rateio(id), unidade_controle_id varchar(36) NOT NULL REFERENCES finance_unidade_controle(id), centro_custo_id varchar(36) REFERENCES finance_centro_custo(id), categoria_id varchar(36) REFERENCES finance_categoria(id),
    valor_alocado numeric(19,4) NOT NULL, percentual numeric(9,6) NOT NULL, ordem integer NOT NULL, criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), atualizado_por varchar(36) REFERENCES colaborador(id), atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_rateio_item_ordem UNIQUE (rateio_id, ordem), CONSTRAINT chk_fin_rateio_item_valores CHECK (ordem >= 0 AND valor_alocado > 0 AND percentual > 0 AND percentual <= 1)
);
CREATE INDEX idx_fin_rateio_item_rateio_unidade ON finance_rateio_item (rateio_id, unidade_controle_id, ordem);
CREATE TRIGGER trg_fin_rateio_item_touch_atualizado_em BEFORE UPDATE ON finance_rateio_item FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();

CREATE TABLE finance_rateio_historico (
    id varchar(36) PRIMARY KEY, rateio_id varchar(36) NOT NULL REFERENCES finance_rateio(id), client_mutation_id varchar(120) NOT NULL, operacao varchar(30) NOT NULL, versao_anterior bigint, versao_nova bigint NOT NULL,
    estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL, alterado_por varchar(36) NOT NULL REFERENCES colaborador(id), alterado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), correlacao_id varchar(120), dispositivo_id varchar(120),
    CONSTRAINT uq_fin_rateio_hist_mutacao UNIQUE (alterado_por, client_mutation_id), CONSTRAINT chk_fin_rateio_hist_operacao CHECK (operacao IN ('CRIAR', 'ATUALIZAR', 'ARQUIVAR')), CONSTRAINT chk_fin_rateio_hist_versao CHECK ((versao_anterior IS NULL AND versao_nova = 0) OR (versao_anterior IS NOT NULL AND versao_nova = versao_anterior + 1))
);
CREATE INDEX idx_fin_rateio_hist_timeline ON finance_rateio_historico (rateio_id, alterado_em, id);

CREATE TABLE finance_rateio_mutacao (
    ator_id varchar(36) NOT NULL REFERENCES colaborador(id), client_mutation_id varchar(120) NOT NULL, origem_tipo varchar(30) NOT NULL, origem_id varchar(36) NOT NULL,
    rateio_id varchar(36) REFERENCES finance_rateio(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), concluido_em timestamp(6) without time zone,
    PRIMARY KEY (ator_id, client_mutation_id), CONSTRAINT chk_fin_rateio_mutacao_origem CHECK (origem_tipo IN ('COMPRA', 'NOTA_FISCAL', 'LANCAMENTO')), CONSTRAINT chk_fin_rateio_mutacao_conclusao CHECK ((rateio_id IS NULL AND concluido_em IS NULL) OR (rateio_id IS NOT NULL AND concluido_em IS NOT NULL))
);
CREATE INDEX idx_fin_rateio_mutacao_rateio ON finance_rateio_mutacao (rateio_id, criado_em);

CREATE TABLE finance_compra_item_ativo (
    id varchar(36) PRIMARY KEY, compra_item_id varchar(36) NOT NULL REFERENCES finance_compra_item(id), ativo_id varchar(36) NOT NULL, unidade_controle_id varchar(36) NOT NULL, sequencia integer NOT NULL,
    criado_por varchar(36) NOT NULL REFERENCES colaborador(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_fin_item_ativo_sequencia UNIQUE (compra_item_id, sequencia), CONSTRAINT uq_fin_item_ativo UNIQUE (ativo_id), CONSTRAINT fk_fin_item_ativo_unidade_ativo FOREIGN KEY (unidade_controle_id, ativo_id) REFERENCES finance_unidade_controle(id, ativo_id), CONSTRAINT chk_fin_item_ativo_sequencia CHECK (sequencia > 0)
);

CREATE TABLE finance_compra_item_ativo_historico (
    id varchar(36) PRIMARY KEY, vinculo_id varchar(36) NOT NULL REFERENCES finance_compra_item_ativo(id), client_mutation_id varchar(120) NOT NULL, operacao varchar(30) NOT NULL,
    estado_anterior_json jsonb, estado_novo_json jsonb NOT NULL, alterado_por varchar(36) NOT NULL REFERENCES colaborador(id), alterado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), correlacao_id varchar(120), dispositivo_id varchar(120),
    CONSTRAINT uq_fin_item_ativo_hist_mutacao UNIQUE (alterado_por, client_mutation_id, vinculo_id), CONSTRAINT chk_fin_item_ativo_hist_operacao CHECK (operacao IN ('VINCULAR', 'DESVINCULAR'))
);
CREATE INDEX idx_fin_item_ativo_hist_timeline ON finance_compra_item_ativo_historico (vinculo_id, alterado_em, id);

CREATE TABLE finance_compra_item_ativo_mutacao (
    ator_id varchar(36) NOT NULL REFERENCES colaborador(id), client_mutation_id varchar(120) NOT NULL, compra_item_id varchar(36) NOT NULL REFERENCES finance_compra_item(id), criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), concluido_em timestamp(6) without time zone,
    PRIMARY KEY (ator_id, client_mutation_id)
);
CREATE INDEX idx_fin_item_ativo_mutacao_item ON finance_compra_item_ativo_mutacao (compra_item_id, criado_em);

CREATE TABLE auth_capacidade_administrativa (
    colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id), capacidade varchar(40) NOT NULL, ativa boolean NOT NULL DEFAULT true,
    concedida_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6), concedida_por varchar(36) NOT NULL REFERENCES colaborador(id), justificativa_concessao varchar(500) NOT NULL,
    revogada_em timestamp(6) without time zone, revogada_por varchar(36) REFERENCES colaborador(id), justificativa_revogacao varchar(500), versao_linha bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (colaborador_id, capacidade), CONSTRAINT chk_auth_capacidade_nome CHECK (capacidade = 'ADMINISTRAR_PAPEIS'),
    CONSTRAINT chk_auth_capacidade_estado CHECK ((ativa AND revogada_em IS NULL AND revogada_por IS NULL) OR (NOT ativa AND revogada_em IS NOT NULL AND revogada_por IS NOT NULL))
);
CREATE INDEX idx_auth_capacidade_ativa ON auth_capacidade_administrativa (capacidade, ativa, colaborador_id);

CREATE TABLE auth_papel_acesso_historico (
    id varchar(36) PRIMARY KEY, colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id), papel_anterior varchar(20) NOT NULL, papel_novo varchar(20) NOT NULL,
    alterado_por varchar(36) NOT NULL REFERENCES colaborador(id), justificativa varchar(500) NOT NULL, criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_auth_papel_historico_anterior CHECK (papel_anterior IN ('ALFA', 'BETA')), CONSTRAINT chk_auth_papel_historico_novo CHECK (papel_novo IN ('ALFA', 'BETA')), CONSTRAINT chk_auth_papel_historico_mudanca CHECK (papel_anterior <> papel_novo)
);
CREATE INDEX idx_auth_papel_historico_alvo_data ON auth_papel_acesso_historico (colaborador_id, criado_em, id);
CREATE INDEX idx_auth_papel_historico_actor_data ON auth_papel_acesso_historico (alterado_por, criado_em, id);

CREATE TABLE tarefa (
    id varchar(36) PRIMARY KEY, obra_id varchar(36) NOT NULL REFERENCES obra(id), equipe varchar(160) NOT NULL, titulo varchar(255) NOT NULL, observacoes text NOT NULL,
    criada_por varchar(255) NOT NULL, criada_por_colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id), responsavel_equipe varchar(255) NOT NULL, responsavel_colaborador_id varchar(36) REFERENCES colaborador(id),
    prioridade smallint NOT NULL, concluida boolean NOT NULL DEFAULT false, concluida_em timestamp(6) without time zone, criada_em timestamp(6) without time zone NOT NULL, atualizada_em timestamp(6) without time zone NOT NULL, deletada_em timestamp(6) without time zone,
    criada_mutacao_id varchar(120) NOT NULL, atualizada_por_colaborador_id varchar(36) NOT NULL REFERENCES colaborador(id),
    CONSTRAINT uq_tarefa_criador_mutacao UNIQUE (criada_por_colaborador_id, criada_mutacao_id), CONSTRAINT chk_tarefa_prioridade CHECK (prioridade IN (1, 2, 3)), CONSTRAINT chk_tarefa_conclusao CHECK ((NOT concluida AND concluida_em IS NULL) OR (concluida AND concluida_em IS NOT NULL))
);
CREATE INDEX idx_tarefa_obra_estado ON tarefa (obra_id, deletada_em, concluida, prioridade, atualizada_em);
CREATE INDEX idx_tarefa_responsavel_estado ON tarefa (responsavel_colaborador_id, deletada_em, atualizada_em);

-- Final integrity overlays from V31--V38. They remain DDL-only in the clean
-- baseline: no legacy records, status configuration, or backfill is seeded.
CREATE INDEX idx_fin_solicitacao_fornecedor ON finance_solicitacao_compra (fornecedor_id, criado_em);
CREATE INDEX idx_fin_solicitacao_categoria ON finance_solicitacao_compra (categoria_id, criado_em);
CREATE INDEX idx_fin_solicitacao_centro_custo ON finance_solicitacao_compra (centro_custo_id, criado_em);
CREATE INDEX idx_fin_solicitacao_prioridade ON finance_solicitacao_compra (prioridade, criado_em);
CREATE INDEX idx_fin_compra_fornecedor_periodo ON finance_compra (fornecedor_id, criado_em);
CREATE INDEX idx_fin_compra_responsavel_periodo ON finance_compra (responsavel_compra_id, criado_em);
CREATE INDEX idx_fin_compra_categoria_periodo ON finance_compra (categoria_id, criado_em);
CREATE INDEX idx_fin_compra_centro_periodo ON finance_compra (centro_custo_id, criado_em);
CREATE INDEX idx_fin_regra_aprovacao_escopo_vigencia
    ON finance_regra_aprovacao (obra_id, centro_custo_id, categoria_id, ativo, vigente_de, vigente_ate);

ALTER TABLE finance_nota_fiscal
    ADD CONSTRAINT chk_fin_nf_datas CHECK (vencimento_em IS NULL OR vencimento_em >= data_emissao),
    ADD CONSTRAINT chk_fin_nf_ocr_status CHECK (ocr_status IN ('NAO_CONFIGURADO', 'PENDENTE', 'PROCESSANDO', 'CONCLUIDO', 'FALHOU')),
    ADD CONSTRAINT chk_fin_nf_arquivo CHECK ((arquivado_em IS NULL AND arquivado_por IS NULL) OR (arquivado_em IS NOT NULL AND arquivado_por IS NOT NULL));
ALTER TABLE finance_documento_extracao_job
    ADD CONSTRAINT chk_fin_doc_extracao_hash_cliente CHECK (sha256_cliente IS NULL OR sha256_cliente ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_fin_doc_extracao_hash_servidor CHECK (sha256_servidor ~ '^[0-9a-f]{64}$');
ALTER TABLE finance_nota_fiscal_documento
    ADD CONSTRAINT chk_fin_nf_documento_hash_cliente CHECK (sha256_cliente IS NULL OR sha256_cliente ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_fin_nf_documento_hash_servidor CHECK (sha256_servidor ~ '^[0-9a-f]{64}$');
CREATE INDEX idx_fin_nf_fornecedor_periodo ON finance_nota_fiscal (obra_id, fornecedor_id, data_emissao);
CREATE INDEX idx_fin_nf_responsavel_periodo ON finance_nota_fiscal (obra_id, responsavel_id, data_emissao);
CREATE INDEX idx_fin_nf_centro_categoria ON finance_nota_fiscal (obra_id, centro_custo_id, categoria_id);
CREATE INDEX idx_fin_nf_historico_obra ON finance_nota_fiscal_historico (obra_id, ocorrido_em, id);
CREATE INDEX idx_fin_lancamento_fornecedor ON finance_lancamento (obra_id, fornecedor_id, vencimento_em);
CREATE INDEX idx_fin_lancamento_responsavel ON finance_lancamento (obra_id, responsavel_id, vencimento_em);
CREATE INDEX idx_fin_lancamento_centro_categoria ON finance_lancamento (obra_id, centro_custo_id, categoria_id);
CREATE INDEX idx_fin_lancamento_nf ON finance_lancamento (nota_fiscal_id, obra_id);
CREATE INDEX idx_fin_lanc_aloc_centro ON finance_lancamento_alocacao (obra_id, centro_custo_id, categoria_id, arquivado_em);
CREATE INDEX idx_fin_lanc_orc_item ON finance_lancamento_orcamento_vinculo (item_contratual_id, obra_id, arquivado_em);
CREATE INDEX idx_fin_liquidacao_status ON finance_liquidacao (obra_id, status, atualizado_em, id);
CREATE INDEX idx_fin_liq_lanc_lancamento ON finance_liquidacao_lancamento (lancamento_id, obra_id, arquivado_em);
CREATE INDEX idx_fin_liq_historico_obra ON finance_liquidacao_historico (obra_id, ocorrido_em, id);

ALTER TABLE finance_cobranca_email
    ADD CONSTRAINT chk_fin_cobranca_email_automatica CHECK (
        modo <> 'AUTOMATICA' OR (regra_id IS NOT NULL AND previsualizacao_hash IS NOT NULL
            AND previsualizacao_confirmada_por IS NOT NULL AND previsualizacao_confirmada_em IS NOT NULL)
    ),
    ADD CONSTRAINT chk_fin_cobranca_email_preview CHECK (
        status = 'RASCUNHO' OR (previsualizacao_hash IS NOT NULL
            AND previsualizacao_confirmada_por IS NOT NULL AND previsualizacao_confirmada_em IS NOT NULL)
    ),
    ADD CONSTRAINT chk_fin_cobranca_email_lock CHECK (
        (status = 'ENVIANDO' AND lock_token IS NOT NULL AND lock_ate IS NOT NULL) OR status <> 'ENVIANDO'
    ),
    ADD CONSTRAINT chk_fin_cobranca_email_envio CHECK (
        (status IN ('ENVIADA', 'ENTREGUE') AND provider IS NOT NULL
            AND provider_message_id IS NOT NULL AND enviada_em IS NOT NULL) OR status NOT IN ('ENVIADA', 'ENTREGUE')
    ),
    ADD CONSTRAINT chk_fin_cobranca_email_entrega CHECK (
        (status = 'ENTREGUE' AND entregue_em IS NOT NULL) OR status <> 'ENTREGUE'
    ),
    ADD CONSTRAINT chk_fin_cobranca_email_falha CHECK (
        (status = 'FALHOU' AND erro_categoria IS NOT NULL AND erro_sanitizado IS NOT NULL) OR status <> 'FALHOU'
    ),
    ADD CONSTRAINT chk_fin_cobranca_email_cancelamento CHECK (
        (status = 'CANCELADA' AND cancelada_por IS NOT NULL AND cancelada_em IS NOT NULL AND motivo_cancelamento IS NOT NULL)
        OR (status <> 'CANCELADA' AND cancelada_por IS NULL AND cancelada_em IS NULL)
    );
CREATE INDEX idx_fin_cobranca_email_fornecedor ON finance_cobranca_email (obra_id, fornecedor_id, status);
CREATE INDEX idx_fin_cobranca_email_alvo ON finance_cobranca_email (obra_id, alvo_tipo, alvo_id);
ALTER TABLE finance_cobranca_tentativa
    ADD CONSTRAINT chk_fin_cobranca_tentativa_saida CHECK (
        (resultado = 'ENVIADA' AND provider IS NOT NULL AND provider_message_id IS NOT NULL
            AND erro_categoria IS NULL AND erro_sanitizado IS NULL)
        OR (resultado = 'FALHOU' AND provider_message_id IS NULL
            AND erro_categoria IS NOT NULL AND erro_sanitizado IS NOT NULL)
    );
CREATE INDEX idx_fin_cobranca_tentativa_retry ON finance_cobranca_tentativa (resultado, proxima_tentativa_em);

-- PostgreSQL does not automatically index child foreign-key columns. All
-- named domain indexes above are retained. This final catalog pass adds a
-- deterministic, full (never partial) supporting index for each remaining
-- child FK. Its predicate is identical to the migration integration contract.
DO $$
DECLARE
    foreign_key record;
    supporting_index_name text;
BEGIN
    FOR foreign_key IN
        SELECT fk.conname,
               child.relname AS child_table,
               string_agg(format('%I', attribute.attname), ', ' ORDER BY key_column.ordinality)
                   AS column_list
        FROM pg_constraint fk
        JOIN pg_class child ON child.oid = fk.conrelid
        JOIN pg_namespace namespace ON namespace.oid = child.relnamespace
        JOIN unnest(fk.conkey) WITH ORDINALITY AS key_column(attnum, ordinality)
          ON true
        JOIN pg_attribute attribute
          ON attribute.attrelid = child.oid AND attribute.attnum = key_column.attnum
        WHERE fk.contype = 'f'
          AND namespace.nspname = 'public'
          AND NOT EXISTS (
              SELECT 1
              FROM pg_index candidate
              WHERE candidate.indrelid = fk.conrelid
                AND candidate.indisvalid
                AND candidate.indpred IS NULL
                AND NOT EXISTS (
                    SELECT 1
                    FROM unnest(fk.conkey) WITH ORDINALITY AS fk_column(attnum, ordinality)
                    LEFT JOIN LATERAL (
                        SELECT index_column.attnum
                        FROM unnest(candidate.indkey::smallint[])
                                 WITH ORDINALITY AS index_column(attnum, ordinality)
                        WHERE index_column.ordinality = fk_column.ordinality
                          AND index_column.ordinality <= candidate.indnkeyatts
                    ) leading_index_column ON true
                    WHERE leading_index_column.attnum IS DISTINCT FROM fk_column.attnum
                )
          )
        GROUP BY fk.conname, child.relname
        ORDER BY child.relname, fk.conname
    LOOP
        supporting_index_name := format(
            'idx_fk_%s_%s',
            left(regexp_replace(foreign_key.conname, '[^a-zA-Z0-9_]', '_', 'g'), 45),
            left(md5(foreign_key.conname), 8)
        );
        EXECUTE format(
            'CREATE INDEX %I ON public.%I (%s)',
            supporting_index_name,
            foreign_key.child_table,
            foreign_key.column_list
        );
    END LOOP;
END $$;
