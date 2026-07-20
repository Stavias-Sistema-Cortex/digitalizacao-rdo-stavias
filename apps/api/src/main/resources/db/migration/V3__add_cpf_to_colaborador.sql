ALTER TABLE colaborador
    ADD COLUMN cpf_hash CHAR(64) AFTER codigo_colaborador,
    ADD COLUMN cpf_mascarado VARCHAR(20) AFTER cpf_hash;

CREATE INDEX idx_colaborador_cpf_hash
    ON colaborador (cpf_hash);
