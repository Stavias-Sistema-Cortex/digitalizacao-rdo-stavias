CREATE TABLE auth_capacidade_administrativa (
    colaborador_id CHAR(36) NOT NULL,
    capacidade VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ativa TINYINT(1) NOT NULL DEFAULT 1,
    concedida_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concedida_por CHAR(36) NOT NULL,
    justificativa_concessao VARCHAR(500) NOT NULL,
    revogada_em DATETIME(6) NULL,
    revogada_por CHAR(36) NULL,
    justificativa_revogacao VARCHAR(500) NULL,
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (colaborador_id, capacidade),
    CONSTRAINT fk_auth_capacidade_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_auth_capacidade_concedida_por
        FOREIGN KEY (concedida_por) REFERENCES colaborador(id),
    CONSTRAINT fk_auth_capacidade_revogada_por
        FOREIGN KEY (revogada_por) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_capacidade_nome
        CHECK (capacidade = 'ADMINISTRAR_PAPEIS'),
    CONSTRAINT chk_auth_capacidade_estado
        CHECK (
            (ativa = 1 AND revogada_em IS NULL AND revogada_por IS NULL)
            OR (ativa = 0 AND revogada_em IS NOT NULL AND revogada_por IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_capacidade_ativa
    ON auth_capacidade_administrativa (capacidade, ativa, colaborador_id);

-- Formaliza o poder administrativo já exercido pelas contas ALFA existentes,
-- sem promover qualquer BETA nem inferir privilégios de grupo/perfil legado.
INSERT INTO auth_capacidade_administrativa (
    colaborador_id,
    capacidade,
    ativa,
    concedida_por,
    justificativa_concessao
)
SELECT
    colaborador.id,
    'ADMINISTRAR_PAPEIS',
    1,
    colaborador.id,
    'Capacidade administrativa formalizada na migração V39.'
FROM colaborador
WHERE colaborador.papel_acesso = 'ALFA'
  AND colaborador.ativo = 1
  AND colaborador.deletado_em IS NULL;

CREATE TABLE auth_papel_acesso_historico (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    papel_anterior VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    papel_novo VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    alterado_por CHAR(36) NOT NULL,
    justificativa VARCHAR(500) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_papel_historico_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_auth_papel_historico_actor
        FOREIGN KEY (alterado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_papel_historico_anterior
        CHECK (papel_anterior IN ('ALFA', 'BETA')),
    CONSTRAINT chk_auth_papel_historico_novo
        CHECK (papel_novo IN ('ALFA', 'BETA')),
    CONSTRAINT chk_auth_papel_historico_mudanca
        CHECK (papel_anterior <> papel_novo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_papel_historico_alvo_data
    ON auth_papel_acesso_historico (colaborador_id, criado_em, id);

CREATE INDEX idx_auth_papel_historico_actor_data
    ON auth_papel_acesso_historico (alterado_por, criado_em, id);
