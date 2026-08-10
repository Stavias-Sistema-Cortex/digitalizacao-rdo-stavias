-- Deixar o apontamento subir incompleto, em vez de derrubar o RDO inteiro.
--
-- A recusa de uma linha de serviço é terminal: a fila offline não reenvia um
-- 400. Uma linha sem unidade — apontada por quem sabia o que executou mas não
-- em que unidade o contrato mede — levava junto o dia inteiro de apontamento,
-- com as outras linhas, as pessoas e a frota. O RDO ficava parado sem que
-- nada na tela dissesse qual campo era o culpado.
--
-- A unidade passa a ser opcional, como largura e espessura já são: ausência é
-- falta de medida, nunca uma unidade inventada. A receita não muda — ela
-- continua exigindo serviço do catálogo, preço vigente e validação, e é lá que
-- a unidade é conferida contra o preço.

ALTER TABLE execucao_servico_rdo
    ALTER COLUMN unidade_medida DROP NOT NULL;
