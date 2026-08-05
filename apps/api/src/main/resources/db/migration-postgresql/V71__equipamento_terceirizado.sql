-- Equipamento terceirizado: cadastro próprio do Córtex.
--
-- A tabela `asset` é espelho de importação — a chave é
-- (source_database, source_table, source_pk) e o conector da Zeladoria só
-- reescreve as linhas que ele mesmo trouxe. A máquina alugada não existe na
-- Zeladoria e nunca vai existir, então até hoje ela só entrava no RDO como
-- texto solto: cada apontador escrevia o prefixo do seu jeito e a mesma
-- retroescavadeira virava três equipamentos diferentes na medição do mês.
--
-- O cadastro resolve isso dando identidade estável à máquina de terceiro
-- dentro do próprio Córtex: a linha de `asset` nasce com
-- source_database = 'cortex' e source_pk igual ao id deste cadastro, fora do
-- alcance do conector. Com isso ela atravessa o caminho que já existe —
-- asset_obra_eligibilidade, rdo_equipamento, o catálogo do RDO — sem que nada
-- disso precise saber que a máquina não veio da importação.
--
-- Esta tabela guarda só o que é próprio do terceiro. Estado de vida (ativo,
-- excluído) fica em `asset`, e não aqui: duas fontes para o mesmo fato
-- divergem, e a divergência apareceria como máquina que some do RDO sem ter
-- sido desativada em lugar nenhum.

CREATE TABLE equipamento_terceirizado (
    id varchar(36) PRIMARY KEY,
    asset_id varchar(36) NOT NULL,
    fornecedor varchar(255) NOT NULL,
    documento_fornecedor varchar(32),
    observacoes varchar(1000),
    criado_por varchar(36),
    atualizado_por varchar(36),
    criado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    versao_linha bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_equipamento_terceirizado_asset
        FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT,
    CONSTRAINT uq_equipamento_terceirizado_asset UNIQUE (asset_id),
    CONSTRAINT chk_equipamento_terceirizado_fornecedor
        CHECK (btrim(fornecedor) <> '')
);

CREATE INDEX idx_equipamento_terceirizado_fornecedor
    ON equipamento_terceirizado (fornecedor, id);

CREATE TRIGGER trg_equipamento_terceirizado_touch_atualizado_em
BEFORE UPDATE ON equipamento_terceirizado
FOR EACH ROW EXECUTE FUNCTION cortex_touch_atualizado_em();
