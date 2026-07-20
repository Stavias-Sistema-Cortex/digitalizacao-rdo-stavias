ALTER TABLE rdo
    ADD COLUMN preenchido_por VARCHAR(255) NULL
        AFTER usuario_importacao,
    ADD COLUMN apontador_rdo VARCHAR(255) NULL
        AFTER preenchido_por,
    ADD COLUMN encarregado_obra VARCHAR(255) NULL
        AFTER apontador_rdo,
    ADD COLUMN fiscalizacao_campo VARCHAR(255) NULL
        AFTER encarregado_obra;

ALTER TABLE rdo_controle_geometrico
    ADD COLUMN numero VARCHAR(80) NULL
        AFTER subtrecho,
    ADD COLUMN pista VARCHAR(80) NULL
        AFTER km_final,
    ADD COLUMN faixa VARCHAR(80) NULL
        AFTER pista,
    ADD COLUMN ordem_servico VARCHAR(120) NULL
        AFTER faixa,
    ADD COLUMN atividade_observacoes TEXT NULL
        AFTER ordem_servico;
