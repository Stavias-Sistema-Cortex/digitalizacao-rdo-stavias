-- Registra na memória operacional as obras já referenciadas por relações ativas.
INSERT INTO cortex_objeto (
    id,
    tipo_entidade,
    entidade_id,
    codigo_externo,
    nome,
    status,
    fonte
)
SELECT
    UUID(),
    'OBRA',
    obra.id,
    COALESCE(
        NULLIF(obra.codigo_cw, ''),
        NULLIF(obra.codigo_contrato, ''),
        NULLIF(obra.codigo_interno, ''),
        obra.id
    ),
    obra.nome,
    obra.status,
    'BACKFILL_ONTOLOGIA'
FROM obra
JOIN (
    SELECT DISTINCT destino_id
    FROM cortex_relacao
    WHERE ativa = 1
      AND destino_tipo = 'OBRA'
) relacao
  ON relacao.destino_id = obra.id
WHERE obra.arquivado_em IS NULL
ON DUPLICATE KEY UPDATE
    codigo_externo = VALUES(codigo_externo),
    nome = VALUES(nome),
    status = VALUES(status),
    fonte = VALUES(fonte),
    versao_linha = cortex_objeto.versao_linha + 1;


-- Registra as programações já referenciadas por relações ativas.
INSERT INTO cortex_objeto (
    id,
    tipo_entidade,
    entidade_id,
    codigo_externo,
    nome,
    status,
    fonte
)
SELECT
    UUID(),
    'PROGRAMACAO_OPERACIONAL',
    programacao.id,
    COALESCE(
        NULLIF(programacao.chave_negocio, ''),
        CONCAT('PROG-', programacao.id)
    ),
    CONCAT(
        'Programação ',
        DATE_FORMAT(
            programacao.data_programacao,
            '%d/%m/%Y'
        ),
        CASE
            WHEN programacao.servico IS NULL
              OR TRIM(programacao.servico) = ''
                THEN ''
            ELSE CONCAT(
                ' - ',
                programacao.servico
            )
        END
    ),
    programacao.status,
    'BACKFILL_ONTOLOGIA'
FROM programacao_operacional programacao
JOIN (
    SELECT DISTINCT destino_id
    FROM cortex_relacao
    WHERE ativa = 1
      AND destino_tipo = 'PROGRAMACAO_OPERACIONAL'
) relacao
  ON relacao.destino_id = programacao.id
WHERE programacao.cancelado_em IS NULL
ON DUPLICATE KEY UPDATE
    codigo_externo = VALUES(codigo_externo),
    nome = VALUES(nome),
    status = VALUES(status),
    fonte = VALUES(fonte),
    versao_linha = cortex_objeto.versao_linha + 1;
