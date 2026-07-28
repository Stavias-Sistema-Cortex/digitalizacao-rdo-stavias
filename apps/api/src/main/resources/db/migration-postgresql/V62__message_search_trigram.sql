CREATE INDEX idx_mensagem_corpo_busca
    ON mensagem USING gin (
        LOWER(COALESCE(corpo, '')) gin_trgm_ops
    )
    WHERE status <> 'EXCLUIDA';
