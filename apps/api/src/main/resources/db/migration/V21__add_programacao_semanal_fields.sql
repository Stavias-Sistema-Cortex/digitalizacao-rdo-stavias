ALTER TABLE programacao_operacional
    ADD COLUMN fechamento VARCHAR(40) NULL
        AFTER equipe,
    ADD COLUMN periodo VARCHAR(40) NULL
        AFTER sentido,
    ADD COLUMN tonelada_massa DECIMAL(14,3) NULL
        AFTER volume_m3,
    ADD COLUMN tipo_cap VARCHAR(80) NULL
        AFTER tonelada_massa,
    ADD COLUMN teor_cap_projeto DECIMAL(10,4) NULL
        AFTER tipo_cap,
    ADD COLUMN cap DECIMAL(14,3) NULL
        AFTER teor_cap_projeto,
    ADD COLUMN encarregado_colaborador_id CHAR(36) NULL
        AFTER encarregado;

ALTER TABLE programacao_operacional
    ADD CONSTRAINT fk_programacao_encarregado_colaborador
        FOREIGN KEY (encarregado_colaborador_id)
        REFERENCES colaborador(id);

CREATE INDEX idx_programacao_encarregado_colaborador
    ON programacao_operacional (encarregado_colaborador_id);

CREATE INDEX idx_programacao_fechamento
    ON programacao_operacional (fechamento);
