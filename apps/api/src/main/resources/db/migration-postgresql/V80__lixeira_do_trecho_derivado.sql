-- A lixeira da linha que o eixo deriva do RDO.
--
-- A linha derivada não é registro: nasce na leitura, do quilômetro que o RDO
-- declara. Apagá-la não pode apagar o RDO — o mapa é projeção, o RDO é o fato.
-- O que a lixeira guarda é o silêncio: este RDO (ou esta linha de serviço)
-- não desenha, até que o próprio RDO seja editado e reafirme o que declara.
-- Editar o RDO apaga o silêncio, e a linha volta — a hierarquia é sempre dele.
CREATE TABLE trecho_derivado_silenciado (
    id varchar(36) PRIMARY KEY,
    obra_id varchar(36) NOT NULL,
    rdo_id varchar(36) NOT NULL,
    -- Nulo silencia a linha da interdição declarada na Identificação;
    -- preenchido silencia a linha de um serviço executado específico.
    execucao_id varchar(36),
    motivo varchar(500),
    silenciado_por varchar(120),
    silenciado_em timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_trecho_silenciado_obra FOREIGN KEY (obra_id)
        REFERENCES obra (id),
    CONSTRAINT fk_trecho_silenciado_rdo FOREIGN KEY (rdo_id)
        REFERENCES rdo (id) ON DELETE CASCADE
);

-- Silenciar duas vezes é o mesmo silêncio: os índices fazem a repetição
-- colidir em vez de acumular linhas que a reafirmação teria de varrer.
CREATE UNIQUE INDEX uq_trecho_silenciado_execucao
    ON trecho_derivado_silenciado (rdo_id, execucao_id)
    WHERE execucao_id IS NOT NULL;
CREATE UNIQUE INDEX uq_trecho_silenciado_interdicao
    ON trecho_derivado_silenciado (rdo_id)
    WHERE execucao_id IS NULL;
CREATE INDEX idx_trecho_silenciado_obra
    ON trecho_derivado_silenciado (obra_id);
