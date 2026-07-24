-- V52 created exact historical revenue evidence and its canonical operational
-- event, before the persisted Cortex object/relation chain became mandatory for
-- evidence detail reads. Reconcile only evidence already proven canonical by
-- V58; no request-time or synthetic fallback is introduced.

CREATE TEMPORARY TABLE cortex_v59_revenue_backfill
ON COMMIT DROP
AS
SELECT execution.id AS execution_id,
       execution.rdo_id,
       execution.obra_id,
       execution.service_id,
       execution.price_version_id,
       execution.revenue_evidence_id,
       execution.unidade_medida AS unit,
       execution.currency,
       execution.accepted_at,
       rdo.numero_rdo,
       rdo.status AS rdo_status,
       service.codigo AS service_code,
       service.nome AS service_name,
       service.status AS service_status,
       price.versao AS price_version
FROM public.execucao_servico_rdo execution
JOIN public.rdo rdo
  ON rdo.id = execution.rdo_id
 AND rdo.obra_id = execution.obra_id
JOIN public.catalogo_servico service
  ON service.id = execution.service_id
JOIN public.service_price_version price
  ON price.id = execution.price_version_id
 AND price.obra_id = execution.obra_id
 AND price.service_id = execution.service_id
 AND price.unidade = execution.unidade_medida
 AND price.moeda = execution.currency
WHERE execution.revenue_coverage_code = 'ACCEPTED_EXACT'
  AND execution.revenue_evidence_id IS NOT NULL
  AND execution.revenue_event_id IS NOT NULL
  AND public.cortex_revenue_event_matches_v58(execution);

ALTER TABLE pg_temp.cortex_v59_revenue_backfill
    ADD PRIMARY KEY (execution_id);

INSERT INTO public.cortex_objeto (
    id,
    tipo_entidade,
    tabela_entidade,
    entidade_id,
    codigo_externo,
    chave_canonica,
    nome,
    status,
    fonte,
    criado_em,
    atualizado_em,
    visto_por_ultimo_em,
    metadados_json
)
SELECT public.cortex_stable_uuid_v52(
           'cortex:object:v59:RDO:' || backfill.rdo_id
       ),
       'RDO',
       'rdo',
       backfill.rdo_id,
       backfill.numero_rdo,
       'RDO:' || COALESCE(
           NULLIF(trim(backfill.numero_rdo), ''),
           'RDO ' || backfill.rdo_id
       ),
       CASE
           WHEN NULLIF(trim(backfill.numero_rdo), '') IS NULL
               THEN 'RDO ' || backfill.rdo_id
           ELSE 'RDO ' || backfill.numero_rdo
       END,
       backfill.rdo_status,
       'CORTEX_FINANCEIRO',
       COALESCE(
           backfill.accepted_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP(6)
       ),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       NULL
FROM (
    SELECT DISTINCT ON (candidate.rdo_id) candidate.*
    FROM pg_temp.cortex_v59_revenue_backfill candidate
    ORDER BY candidate.rdo_id, candidate.execution_id
) backfill
ON CONFLICT (tipo_entidade, entidade_id) DO UPDATE SET
    tabela_entidade = 'rdo',
    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
    versao_linha = public.cortex_objeto.versao_linha + 1;

INSERT INTO public.cortex_objeto (
    id,
    tipo_entidade,
    tabela_entidade,
    entidade_id,
    codigo_externo,
    chave_canonica,
    nome,
    status,
    fonte,
    criado_em,
    atualizado_em,
    visto_por_ultimo_em,
    metadados_json
)
SELECT public.cortex_stable_uuid_v52(
           'cortex:object:v59:SERVICE:' || backfill.service_id
       ),
       'SERVICE',
       'catalogo_servico',
       backfill.service_id,
       backfill.service_code,
       'SERVICE:' || backfill.service_code,
       backfill.service_name,
       backfill.service_status,
       'CORTEX_FINANCEIRO',
       COALESCE(
           backfill.accepted_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP(6)
       ),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       NULL
FROM (
    SELECT DISTINCT ON (candidate.service_id) candidate.*
    FROM pg_temp.cortex_v59_revenue_backfill candidate
    ORDER BY candidate.service_id, candidate.execution_id
) backfill
ON CONFLICT (tipo_entidade, entidade_id) DO UPDATE SET
    tabela_entidade = EXCLUDED.tabela_entidade,
    codigo_externo = EXCLUDED.codigo_externo,
    chave_canonica = EXCLUDED.chave_canonica,
    nome = EXCLUDED.nome,
    status = EXCLUDED.status,
    fonte = EXCLUDED.fonte,
    metadados_json = EXCLUDED.metadados_json,
    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
    versao_linha = public.cortex_objeto.versao_linha + 1;

INSERT INTO public.cortex_objeto (
    id,
    tipo_entidade,
    tabela_entidade,
    entidade_id,
    codigo_externo,
    chave_canonica,
    nome,
    status,
    fonte,
    criado_em,
    atualizado_em,
    visto_por_ultimo_em,
    metadados_json
)
SELECT public.cortex_stable_uuid_v52(
           'cortex:object:v59:SERVICE_PRICE_VERSION:'
           || backfill.price_version_id
       ),
       'SERVICE_PRICE_VERSION',
       'service_price_version',
       backfill.price_version_id,
       NULL,
       'SERVICE_PRICE_VERSION:'
           || backfill.service_code
           || ' · '
           || backfill.unit
           || ' · '
           || backfill.currency
           || ' · v'
           || backfill.price_version,
       backfill.service_code
           || ' · '
           || backfill.unit
           || ' · '
           || backfill.currency
           || ' · v'
           || backfill.price_version,
       'ACTIVE',
       'CORTEX_FINANCEIRO',
       COALESCE(
           backfill.accepted_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP(6)
       ),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       NULL
FROM (
    SELECT DISTINCT ON (candidate.price_version_id) candidate.*
    FROM pg_temp.cortex_v59_revenue_backfill candidate
    ORDER BY candidate.price_version_id, candidate.execution_id
) backfill
ON CONFLICT (tipo_entidade, entidade_id) DO UPDATE SET
    tabela_entidade = EXCLUDED.tabela_entidade,
    codigo_externo = EXCLUDED.codigo_externo,
    chave_canonica = EXCLUDED.chave_canonica,
    nome = EXCLUDED.nome,
    status = EXCLUDED.status,
    fonte = EXCLUDED.fonte,
    metadados_json = EXCLUDED.metadados_json,
    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
    versao_linha = public.cortex_objeto.versao_linha + 1;

INSERT INTO public.cortex_objeto (
    id,
    tipo_entidade,
    tabela_entidade,
    entidade_id,
    codigo_externo,
    chave_canonica,
    nome,
    status,
    fonte,
    criado_em,
    atualizado_em,
    visto_por_ultimo_em,
    metadados_json
)
SELECT public.cortex_stable_uuid_v52(
           'cortex:object:v59:RDO_SERVICE_EXECUTED:'
           || backfill.execution_id
       ),
       'RDO_SERVICE_EXECUTED',
       'execucao_servico_rdo',
       backfill.execution_id,
       backfill.execution_id,
       'RDO_SERVICE_EXECUTED:' || backfill.execution_id,
       backfill.service_name,
       'ACCEPTED',
       'CORTEX_FINANCEIRO',
       COALESCE(
           backfill.accepted_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP(6)
       ),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       jsonb_build_object(
           'rdoId', backfill.rdo_id,
           'obraId', backfill.obra_id
       )
FROM pg_temp.cortex_v59_revenue_backfill backfill
ON CONFLICT (tipo_entidade, entidade_id) DO UPDATE SET
    tabela_entidade = EXCLUDED.tabela_entidade,
    codigo_externo = EXCLUDED.codigo_externo,
    chave_canonica = EXCLUDED.chave_canonica,
    nome = EXCLUDED.nome,
    status = EXCLUDED.status,
    fonte = EXCLUDED.fonte,
    metadados_json = EXCLUDED.metadados_json,
    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
    versao_linha = public.cortex_objeto.versao_linha + 1;

INSERT INTO public.cortex_objeto (
    id,
    tipo_entidade,
    tabela_entidade,
    entidade_id,
    codigo_externo,
    chave_canonica,
    nome,
    status,
    fonte,
    criado_em,
    atualizado_em,
    visto_por_ultimo_em,
    metadados_json
)
SELECT public.cortex_stable_uuid_v52(
           'cortex:object:v59:REVENUE_EVIDENCE:'
           || backfill.revenue_evidence_id
       ),
       'REVENUE_EVIDENCE',
       'execucao_servico_rdo',
       backfill.revenue_evidence_id,
       backfill.revenue_evidence_id,
       'REVENUE_EVIDENCE:' || backfill.revenue_evidence_id,
       'Revenue evidence ' || backfill.execution_id,
       'ACCEPTED',
       'CORTEX_FINANCEIRO',
       COALESCE(
           backfill.accepted_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP(6)
       ),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6),
       jsonb_build_object(
           'executionId', backfill.execution_id,
           'rdoId', backfill.rdo_id,
           'obraId', backfill.obra_id
       )
FROM pg_temp.cortex_v59_revenue_backfill backfill
ON CONFLICT (tipo_entidade, entidade_id) DO UPDATE SET
    tabela_entidade = EXCLUDED.tabela_entidade,
    codigo_externo = EXCLUDED.codigo_externo,
    chave_canonica = EXCLUDED.chave_canonica,
    nome = EXCLUDED.nome,
    status = EXCLUDED.status,
    fonte = EXCLUDED.fonte,
    metadados_json = EXCLUDED.metadados_json,
    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
    versao_linha = public.cortex_objeto.versao_linha + 1;

INSERT INTO public.cortex_relacao (
    id,
    origem_tipo,
    origem_id,
    destino_tipo,
    destino_id,
    tipo_relacao,
    ativa,
    fonte,
    observacoes,
    criado_em,
    atualizado_em
)
SELECT public.cortex_stable_uuid_v52(
           'cortex:relation:v59:'
           || backfill.execution_id
           || ':'
           || relation.tipo_relacao
           || ':'
           || relation.destino_tipo
           || ':'
           || relation.destino_id
       ),
       'RDO_SERVICE_EXECUTED',
       backfill.execution_id,
       relation.destino_tipo,
       relation.destino_id,
       relation.tipo_relacao,
       TRUE,
       'CORTEX_FINANCEIRO',
       relation.tipo_relacao,
       COALESCE(
           backfill.accepted_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP(6)
       ),
       CURRENT_TIMESTAMP(6)
FROM pg_temp.cortex_v59_revenue_backfill backfill
CROSS JOIN LATERAL (
    VALUES
        ('EXECUTED_IN', 'RDO', backfill.rdo_id),
        ('EXECUTES_SERVICE', 'SERVICE', backfill.service_id),
        (
            'GENERATES_REVENUE',
            'REVENUE_EVIDENCE',
            backfill.revenue_evidence_id
        ),
        (
            'PRICED_BY',
            'SERVICE_PRICE_VERSION',
            backfill.price_version_id
        )
) AS relation(tipo_relacao, destino_tipo, destino_id)
ON CONFLICT (
    origem_tipo,
    origem_id,
    destino_tipo,
    destino_id,
    tipo_relacao
) WHERE ativa IS TRUE
DO UPDATE SET
    fonte = EXCLUDED.fonte,
    observacoes = EXCLUDED.observacoes,
    encerrado_em = NULL,
    atualizado_em = CURRENT_TIMESTAMP(6),
    versao_linha = public.cortex_relacao.versao_linha + 1;
