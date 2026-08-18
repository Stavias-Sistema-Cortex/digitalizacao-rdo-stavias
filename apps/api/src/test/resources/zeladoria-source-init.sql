CREATE TABLE ativos (
    id BIGINT UNSIGNED PRIMARY KEY,
    prefixo VARCHAR(120),
    tipo VARCHAR(120),
    modelo VARCHAR(255)
) ENGINE=InnoDB;

INSERT INTO ativos (id, prefixo, tipo, modelo) VALUES
    (1, 'EQ-01', 'CAMINHAO', 'Modelo A'),
    (2, 'EQ-02', 'ROLO', 'Modelo B'),
    (3, 'EQ-03', 'TRATOR', 'Modelo C');
