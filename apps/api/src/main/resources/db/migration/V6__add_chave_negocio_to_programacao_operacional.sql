ALTER TABLE programacao_operacional
    ADD COLUMN chave_negocio VARCHAR(700) AFTER hash_origem;

UPDATE programacao_operacional
SET chave_negocio = SHA2(
    CONCAT_WS('|',
        COALESCE(codigo_contrato_origem, ''),
        COALESCE(DATE_FORMAT(data_programacao, '%Y-%m-%d'), ''),
        COALESCE(equipe, ''),
        COALESCE(servico, ''),
        COALESCE(tipo_servico, ''),
        COALESCE(rodovia, ''),
        COALESCE(sentido, ''),
        COALESCE(faixa, ''),
        COALESCE(km_inicial, ''),
        COALESCE(km_final, ''),
        COALESCE(CAST(linha_origem AS CHAR), '')
    ),
    256
)
WHERE chave_negocio IS NULL;

CREATE INDEX idx_programacao_chave_negocio
    ON programacao_operacional (chave_negocio);
