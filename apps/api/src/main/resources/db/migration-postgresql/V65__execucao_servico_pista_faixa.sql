-- O serviço executado declarava trecho inicial e final, mas não em que pista da
-- rodovia o trabalho aconteceu. Sem isso o esquemático da obra não consegue
-- posicionar a atividade na pista certa: empilha tudo numa faixa "não
-- declarada", o que também impede o desenho do canteiro central.
--
-- O vocabulário e o tamanho acompanham rdo_controle_geometrico, que é onde o
-- apontador já descreve a geometria do mesmo RDO.
ALTER TABLE execucao_servico_rdo
    ADD COLUMN pista varchar(80),
    ADD COLUMN faixa varchar(80);

COMMENT ON COLUMN execucao_servico_rdo.pista IS
    'Pista declarada para o serviço executado. NULL identifica lançamentos anteriores à V65 e RDOs que não a informaram; a projeção do trecho herda a pista do controle geométrico do mesmo RDO e marca o segmento como inferido, em vez de gravar palpite aqui.';

COMMENT ON COLUMN execucao_servico_rdo.faixa IS
    'Faixa declarada para o serviço executado, no mesmo vocabulário livre de rdo_controle_geometrico. NULL é ausência de dado, nunca faixa presumida.';
