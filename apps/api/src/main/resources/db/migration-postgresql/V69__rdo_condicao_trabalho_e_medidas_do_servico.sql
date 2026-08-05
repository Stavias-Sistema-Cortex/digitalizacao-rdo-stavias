-- Duas medidas que o RDO passou a precisar guardar.
--
-- A etapa de controle geométrico saiu do formulário: a empresa não a usava
-- como etapa própria. As medidas que viviam lá, porém, continuam sendo o que
-- transforma um trecho em quantidade — largura e espessura são o que fecham
-- área e volume — e agora pertencem ao serviço executado, junto do trecho a
-- que se referem, em vez de a uma etapa paralela que ninguém preenchia.
--
-- Área e volume não viram coluna: são conta de largura, comprimento e
-- espessura, e gravar resultado ao lado das parcelas cria duas versões da
-- mesma verdade, que divergem no primeiro acerto de uma delas.
ALTER TABLE execucao_servico_rdo
    ADD COLUMN largura_m numeric(10,3),
    ADD COLUMN espessura_cm numeric(10,3);

COMMENT ON COLUMN execucao_servico_rdo.largura_m IS
    'Largura executada do serviço, em metros. NULL é ausência de medida — nunca zero presumido —, e sem ela a área do serviço fica indeterminada em vez de virar zero.';

COMMENT ON COLUMN execucao_servico_rdo.espessura_cm IS
    'Espessura executada do serviço, em centímetros, na mesma unidade em que se mede em campo. NULL é ausência de medida; sem ela o volume fica indeterminado.';

-- A condição de trabalho do dia.
--
-- O clima já era registrado por turno, mas clima não é a mesma pergunta que
-- "deu para trabalhar". Chove e a frente segue; não chove e o trecho está
-- intransitável por outra razão. Sem um campo próprio, essa informação vivia
-- na observação em texto livre, de onde nenhum relatório consegue lê-la.
ALTER TABLE rdo
    ADD COLUMN condicao_trabalho varchar(20);

COMMENT ON COLUMN rdo.condicao_trabalho IS
    'PRATICAVEL ou IMPRATICAVEL, declarado por quem apontou. NULL identifica RDOs anteriores à V69 e os que não a informaram; ausência nunca deve ser lida como praticável.';

ALTER TABLE rdo
    ADD CONSTRAINT chk_rdo_condicao_trabalho
    CHECK (condicao_trabalho IS NULL
           OR condicao_trabalho IN ('PRATICAVEL', 'IMPRATICAVEL'));
