-- O trecho desenhado no mapa não sabia que dependia de alguma coisa.
--
-- A consulta que alimenta o trecho a partir de obra_geometria filtrava obra,
-- categoria e status, e nada mais: nenhum vínculo com RDO, nenhuma noção de
-- cancelamento. As outras três fontes do trecho descendem do RDO e somem com
-- ele; esta sobrevivia para sempre. Apagar o RDO deixava o desenho na tela,
-- descrevendo um serviço que não existe mais — e, sem amarração com a obra, a
-- rodovia digitada à mão podia ser outra que ninguém conferia.
--
-- Daqui para frente o RDO é a fonte: quem desenha alimenta o apontamento do
-- dia, e o desenho existe como representação dele no mapa. Um trecho sem RDO
-- não descreve execução nenhuma.

-- As geometrias de trecho que já existem sem vínculo descrevem exatamente esse
-- estado anterior — inclusive a de rodovia divergente. São encerradas, não
-- removidas: a linha continua auditável e a memória operacional que aponta
-- para ela continua resolvendo, enquanto some do trecho e do mapa, que leem
-- apenas o que está vigente.
UPDATE obra_geometria
SET status = 'ENCERRADA',
    valido_ate = CURRENT_TIMESTAMP(6),
    motivo_encerramento =
        'Trecho sem vínculo com RDO: o apontamento passou a ser a fonte.',
    versao_linha = versao_linha + 1,
    atualizado_em = CURRENT_TIMESTAMP(6)
WHERE categoria = 'TRECHO'
  AND status = 'ATIVA'
  AND (objeto_tipo IS NULL OR objeto_id IS NULL OR objeto_tipo <> 'RDO');

-- Trecho vigente passa a exigir o vínculo. É índice parcial e não constraint
-- para não recusar a escrita de outras categorias nem reabrir o histórico já
-- encerrado acima: o que se garante é que nenhuma geometria de trecho volte a
-- ficar vigente sem dizer de qual RDO ela é.
CREATE INDEX idx_obra_geometria_trecho_rdo
    ON obra_geometria (objeto_id, valido_desde)
    WHERE categoria = 'TRECHO'
      AND status = 'ATIVA'
      AND objeto_tipo = 'RDO';
