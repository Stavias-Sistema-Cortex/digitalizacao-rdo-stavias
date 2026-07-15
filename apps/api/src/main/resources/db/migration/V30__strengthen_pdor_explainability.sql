-- Metadados de reprodutibilidade e explicabilidade explícita do PDOR.
-- Snapshots anteriores são preservados e marcados como legado; novas
-- execuções passam a preencher todos os campos com evidência estruturada.
ALTER TABLE pdor_snapshot
    ADD COLUMN versao_dados CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER chave_idempotencia,
    ADD COLUMN escopo_analise_json JSON NULL AFTER origem_inputs_json,
    ADD COLUMN janela_temporal_json JSON NULL AFTER escopo_analise_json,
    ADD COLUMN features_utilizadas_json JSON NULL AFTER janela_temporal_json,
    ADD COLUMN dados_ausentes_json JSON NULL AFTER features_utilizadas_json,
    ADD COLUMN limitacoes_json JSON NULL AFTER dados_ausentes_json,
    ADD COLUMN alertas_json JSON NULL AFTER limitacoes_json,
    ADD COLUMN recomendacoes_json JSON NULL AFTER alertas_json,
    ADD COLUMN comparacao_anterior_json JSON NULL AFTER recomendacoes_json,
    ADD COLUMN evidencias_json JSON NULL AFTER comparacao_anterior_json,
    ADD COLUMN iniciado_por VARCHAR(120) NULL AFTER tipo_disparo,
    ADD COLUMN tipo_iniciador VARCHAR(20) NULL AFTER iniciado_por;

UPDATE pdor_snapshot
SET
    versao_dados = chave_idempotencia,
    escopo_analise_json = JSON_OBJECT(
        'obraId', obra_id,
        'legado', TRUE,
        'observacao', 'Escopo detalhado indisponível no snapshot legado.'
    ),
    janela_temporal_json = JSON_OBJECT(
        'dataReferencia', data_referencia,
        'legado', TRUE
    ),
    features_utilizadas_json = JSON_ARRAY(),
    dados_ausentes_json = JSON_ARRAY(),
    limitacoes_json = warnings_json,
    alertas_json = JSON_ARRAY(),
    recomendacoes_json = JSON_ARRAY(),
    comparacao_anterior_json = JSON_OBJECT(
        'disponivel', FALSE,
        'motivo', 'Comparação não persistida no snapshot legado.'
    ),
    evidencias_json = JSON_ARRAY(),
    iniciado_por = CASE
        WHEN tipo_disparo IN ('EVENT', 'SCHEDULED') THEN 'PROCESSO_PDOR'
        ELSE 'LEGADO_NAO_REGISTRADO'
    END,
    tipo_iniciador = CASE
        WHEN tipo_disparo IN ('EVENT', 'SCHEDULED') THEN 'PROCESS'
        ELSE 'UNKNOWN'
    END;

ALTER TABLE pdor_snapshot
    MODIFY versao_dados CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY escopo_analise_json JSON NOT NULL,
    MODIFY janela_temporal_json JSON NOT NULL,
    MODIFY features_utilizadas_json JSON NOT NULL,
    MODIFY dados_ausentes_json JSON NOT NULL,
    MODIFY limitacoes_json JSON NOT NULL,
    MODIFY alertas_json JSON NOT NULL,
    MODIFY recomendacoes_json JSON NOT NULL,
    MODIFY comparacao_anterior_json JSON NOT NULL,
    MODIFY evidencias_json JSON NOT NULL,
    MODIFY iniciado_por VARCHAR(120) NOT NULL,
    MODIFY tipo_iniciador VARCHAR(20) NOT NULL,
    ADD CONSTRAINT chk_pdor_snapshot_tipo_iniciador
        CHECK (tipo_iniciador IN ('USER', 'PROCESS', 'UNKNOWN'));

CREATE INDEX idx_pdor_snapshot_versao_dados
    ON pdor_snapshot (obra_id, versao_dados);
