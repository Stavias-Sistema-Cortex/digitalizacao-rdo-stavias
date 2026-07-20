-- Modelo de autorização Alfa/Beta e vínculo explícito colaborador ↔ obra.
--
-- Motivação: até aqui o "administrador" era inferido pelo texto do perfil/grupo
-- e o acesso à obra era inferido por alocação operacional OU por ter aparecido
-- em algum RDO (rdo_mao_obra). Inferir acesso por RDO anterior concede a obra a
-- qualquer colaborador que já tenha sido lançado nela. Este passo torna o papel
-- de acesso e o vínculo com a obra explícitos, deliberados e auditáveis.

-- 1) Papel de acesso explícito do colaborador (ALFA = global, BETA = operacional).
ALTER TABLE colaborador
    ADD COLUMN papel_acesso VARCHAR(20) NULL AFTER nome_perfil;

-- Backfill do papel a partir da mesma heurística de texto usada até então,
-- preservando o comportamento atual: quem era administrador vira ALFA; o
-- restante vira BETA. Ajustes finos passam a ser feitos por dado explícito.
UPDATE colaborador
SET papel_acesso = CASE
        WHEN LOWER(COALESCE(nome_perfil, '')) LIKE '%admin%'
          OR LOWER(COALESCE(nome_grupo, '')) LIKE '%admin%'
            THEN 'ALFA'
        ELSE 'BETA'
    END
WHERE papel_acesso IS NULL;

CREATE INDEX idx_colaborador_papel_acesso
    ON colaborador (papel_acesso);

-- 2) Vínculo explícito colaborador ↔ obra (a "associação" de autorização).
--    Um vínculo ATIVO concede acesso Beta à obra. A revogação encerra o acesso
--    sem apagar o histórico. Uma linha por par (colaborador, obra); o status
--    alterna entre ATIVO e REVOGADO ao longo do tempo.
CREATE TABLE vinculo_colaborador_obra (
    id CHAR(36) NOT NULL,

    obra_id CHAR(36) NOT NULL,
    colaborador_id CHAR(36) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'ATIVO',
    papel_na_obra VARCHAR(40) NOT NULL DEFAULT 'OPERACIONAL',

    atribuido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atribuido_por VARCHAR(120),
    revogado_em DATETIME(6),
    revogado_por VARCHAR(120),

    metadados_json JSON,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT uq_vinculo_colaborador_obra
        UNIQUE (colaborador_id, obra_id),

    CONSTRAINT fk_vinculo_colaborador_obra_colaborador
        FOREIGN KEY (colaborador_id)
        REFERENCES colaborador(id),

    CONSTRAINT fk_vinculo_colaborador_obra_obra
        FOREIGN KEY (obra_id)
        REFERENCES obra(id),

    CONSTRAINT chk_vinculo_colaborador_obra_status
        CHECK (status IN ('ATIVO', 'REVOGADO'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_vinculo_colaborador_obra_colaborador
    ON vinculo_colaborador_obra (colaborador_id, status);

CREATE INDEX idx_vinculo_colaborador_obra_obra
    ON vinculo_colaborador_obra (obra_id, status);

-- 3) Backfill dos vínculos a partir das alocações vigentes, transformando o
--    acesso hoje inferido em vínculo explícito e auditável (uma única vez, na
--    migração). A inferência por RDO anterior deixa de valer em tempo de
--    execução: o que estava alocado agora é um vínculo registrado; o que era
--    apenas "apareceu num RDO" não vira acesso automático.
INSERT INTO vinculo_colaborador_obra (
    id,
    obra_id,
    colaborador_id,
    status,
    papel_na_obra,
    atribuido_em,
    atribuido_por,
    metadados_json
)
SELECT
    UUID(),
    origem.obra_id,
    origem.colaborador_id,
    'ATIVO',
    'OPERACIONAL',
    CURRENT_TIMESTAMP(6),
    'MIGRACAO_V26',
    JSON_OBJECT('fonte', 'BACKFILL_ALOCACAO_VIGENTE')
FROM (
    SELECT DISTINCT ac.colaborador_id, ac.obra_id
    FROM alocacao_colaborador ac
    JOIN colaborador c
        ON c.id = ac.colaborador_id
       AND c.ativo = 1
       AND c.deletado_em IS NULL
    JOIN obra o
        ON o.id = ac.obra_id
    WHERE ac.status <> 'CANCELADA'
) AS origem;
