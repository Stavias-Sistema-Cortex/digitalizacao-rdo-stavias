-- A cerca que faltava no texto do número: a unicidade era só do sequencial,
-- e o numero_rdo — que é o que a operação lê, imprime e cita — podia repetir
-- dentro da mesma obra por uma planilha malformada na importação histórica
-- (a deduplicação de lá é por obra+número+data, então o mesmo número com
-- outra data passava). A criação nunca repete, porque o número nasce do
-- sequencial; a cerca fecha as portas restantes no único lugar que alcança
-- todas: o banco.

-- Antes da cerca, o acerto do que já entrou duplicado: fica intacto o mais
-- antigo, e os demais ganham um sufixo determinístico que preserva o rastro
-- ("-D2", "-D3"). Renomear é melhor que falhar a migração — e o novo nome
-- continua dizendo qual era o original.
WITH duplicatas AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY obra_id, numero_rdo
               ORDER BY criado_em, id
           ) AS posicao
    FROM rdo
)
UPDATE rdo
SET numero_rdo = rdo.numero_rdo || '-D' || duplicatas.posicao
FROM duplicatas
WHERE rdo.id = duplicatas.id
  AND duplicatas.posicao > 1;

ALTER TABLE rdo
    ADD CONSTRAINT uq_rdo_obra_numero UNIQUE (obra_id, numero_rdo);
