-- Reconstrói o vínculo colaborador ↔ obra antes de ele voltar a decidir acesso.
--
-- O vínculo deixou de cercar a obra em algum ponto da evolução do sistema, e o
-- escopo passou a ser "toda obra para todo colaborador reconhecido". A operação
-- pediu a cerca de volta: quem é Beta deve enxergar, e apontar, apenas nas obras
-- em que trabalha.
--
-- Religar a regra sem antes reconstruir os dados trancaria todo mundo do lado de
-- fora. A tabela existe desde a base V44, mas o banco PostgreSQL nasceu limpo:
-- só tem linha quem foi vinculado à mão pela tela de Gestão de Obras, e isso é
-- pouca gente perto de quem já aponta em campo. A cerca entraria em produção
-- fechando a obra de quem estava dentro dela ontem.
--
-- A evidência de que alguém trabalha numa obra já está gravada, em dois lugares
-- que o próprio sistema mantém: a alocação vigente e a presença em RDO. É dessa
-- evidência que os vínculos nascem aqui — uma vez, agora, para que o primeiro
-- dia da cerca não seja o dia em que ninguém entra.
--
-- Isto é backfill, não inferência em tempo de execução. Depois desta migração,
-- quem concede e quem revoga é o Alfa, explicitamente. A diferença importa: uma
-- regra que deduzisse acesso a partir de RDO faria qualquer apontamento virar
-- permissão para sempre, e não é isso que se quer.
INSERT INTO vinculo_colaborador_obra (
    id,
    obra_id,
    colaborador_id,
    status,
    papel_na_obra,
    atribuido_em,
    atribuido_por,
    metadados_json
)
SELECT
    gen_random_uuid()::varchar,
    evidencia.obra_id,
    evidencia.colaborador_id,
    'ATIVO',
    'OPERACIONAL',
    CURRENT_TIMESTAMP(6),
    'backfill:V70',
    jsonb_build_object('origem', 'V70', 'evidencia', evidencia.origem)
FROM (
    -- Agrupar antes de inserir: as duas evidências se sobrepõem para quase todo
    -- mundo, e o par (colaborador, obra) é único na tabela. Deduplicar aqui
    -- deixa a intenção explícita em vez de depender do ON CONFLICT para
    -- absorver colisão que a própria consulta produziu.
    SELECT
        bruto.colaborador_id,
        bruto.obra_id,
        MIN(bruto.origem) AS origem
    FROM (
        SELECT
            a.colaborador_id,
            a.obra_id,
            'ALOCACAO' AS origem
        FROM alocacao_colaborador a
        WHERE a.status <> 'CANCELADA'

        UNION ALL

        SELECT
            m.colaborador_id,
            r.obra_id,
            'RDO' AS origem
        FROM rdo_mao_obra m
        JOIN rdo r ON r.id = m.rdo_id
        WHERE m.colaborador_id IS NOT NULL
          AND m.colaborador_id <> ''
    ) AS bruto
    GROUP BY bruto.colaborador_id, bruto.obra_id
) AS evidencia
JOIN colaborador c ON c.id = evidencia.colaborador_id
JOIN obra o ON o.id = evidencia.obra_id
WHERE c.ativo = TRUE
  AND c.deletado_em IS NULL
-- Uma revogação já registrada é decisão de alguém, e backfill não desfaz
-- decisão. O par existindo — ATIVO ou REVOGADO — manda esta linha embora.
ON CONFLICT (colaborador_id, obra_id) DO NOTHING;

COMMENT ON TABLE vinculo_colaborador_obra IS
    'Quem trabalha em qual obra. Volta a ser a cerca do perfil Beta: sem vínculo ATIVO não há leitura nem apontamento. Alfa não depende dela. As linhas com metadados_json->>''origem'' = ''V70'' nasceram do backfill da operação já registrada, não de atribuição manual.';
