ALTER TABLE obra
    ALTER COLUMN versao_linha SET DEFAULT 1;

ALTER TABLE obra DISABLE TRIGGER trg_obra_touch_atualizado_em;

UPDATE obra AS o
SET versao_linha = estado.versao_entidade
FROM cortex_estado_entidade AS estado
WHERE estado.tipo_entidade = 'OBRA'
  AND estado.entidade_id = o.id
  AND o.versao_linha IS DISTINCT FROM estado.versao_entidade;

ALTER TABLE obra ENABLE TRIGGER trg_obra_touch_atualizado_em;

CREATE INDEX idx_obra_arquivadas_atualizado
    ON obra (atualizado_em DESC, id DESC)
    WHERE arquivado_em IS NOT NULL;
