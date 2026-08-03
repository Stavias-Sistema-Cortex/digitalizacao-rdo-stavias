-- Toda decisão de acesso Alfa/Beta passa por duas consultas que o banco não
-- conseguia responder por índice.
--
-- Os identificadores são comparados sem diferenciar caixa — `LOWER(coluna) =
-- LOWER(?)` — e essa forma anula o índice existente: `idx_vinculo_colaborador_
-- obra_colaborador` indexa `colaborador_id`, não `LOWER(colaborador_id)`, então
-- o planejador varria a tabela inteira. Em `rdo` o efeito é o mesmo sobre a
-- própria chave primária, e ali importa mais: a tabela cresce a cada diário e
-- nunca encolhe, então a varredura fica mais cara todo dia.
--
-- Indexar a expressão preserva exatamente a semântica da comparação. A
-- alternativa — tirar o `LOWER` das consultas — mudaria o resultado para
-- qualquer identificador já gravado fora da caixa canônica, e o acesso à obra
-- não é lugar de descobrir isso em produção.

-- Cobre as duas consultas de vínculo: a que lista as obras do colaborador
-- (`LOWER(colaborador_id)` + `status`) e a que verifica uma obra específica,
-- que acrescenta `obra_id`. Com `obra_id` no fim, a listagem é respondida pelo
-- próprio índice, sem visitar a tabela.
CREATE INDEX idx_vinculo_colaborador_obra_colaborador_lower
    ON vinculo_colaborador_obra (LOWER(colaborador_id), status, obra_id);

-- Resolve a obra de um RDO, que é o primeiro passo de toda autorização por RDO.
CREATE INDEX idx_rdo_id_lower ON rdo (LOWER(id));

COMMENT ON INDEX idx_vinculo_colaborador_obra_colaborador_lower IS
    'Atende às consultas de autorização por vínculo, que comparam colaborador_id sem diferenciar caixa. O índice sobre a coluna crua não serve a essa forma.';

COMMENT ON INDEX idx_rdo_id_lower IS
    'Atende à resolução da obra de um RDO, que compara o id sem diferenciar caixa e por isso não alcança a chave primária.';
