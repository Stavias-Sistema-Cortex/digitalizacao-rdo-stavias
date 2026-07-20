-- Remove a referência redundante por sequência (evento_servidor_seq).
-- commit_seq passa a ser a única referência ao evento de origem, evitando que as
-- duas colunas apontem para eventos diferentes e eliminando um índice e uma FK.
ALTER TABLE sync_mutacao_cliente
    DROP FOREIGN KEY fk_sync_mutacao_evento_servidor;

ALTER TABLE sync_mutacao_cliente
    DROP INDEX idx_sync_mutacao_evento_servidor,
    DROP COLUMN evento_servidor_seq;


-- Garante utf8mb4 nas colunas de texto livre, independente do charset padrão do
-- servidor, para armazenar corretamente acentuação (português).
ALTER TABLE cortex_objeto
    MODIFY nome VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL;

ALTER TABLE cortex_relacao
    MODIFY observacoes TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL;

ALTER TABLE sync_dispositivo
    MODIFY nome VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL;

ALTER TABLE sync_mutacao_cliente
    MODIFY erro TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL;
