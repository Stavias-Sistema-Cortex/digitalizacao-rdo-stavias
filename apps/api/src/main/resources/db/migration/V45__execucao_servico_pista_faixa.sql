-- O serviço executado declarava trecho inicial e final, mas não em que pista da
-- rodovia o trabalho aconteceu. Sem isso o esquemático da obra não conseguia
-- posicionar a atividade na pista certa e empilhava tudo numa faixa "não
-- declarada", o que também impedia o desenho do canteiro central.
--
-- As colunas nascem nulas e assim permanecem para todo o histórico: pista não
-- registrada continua sendo ausência de dado, nunca um valor presumido. O
-- vocabulário e o tamanho acompanham rdo_controle_geometrico (V20), que é onde
-- o apontador já descreve a geometria do mesmo RDO.
ALTER TABLE execucao_servico_rdo
    ADD COLUMN pista VARCHAR(80) NULL
        AFTER trecho_final,
    ADD COLUMN faixa VARCHAR(80) NULL
        AFTER pista;
