ALTER TABLE sync_mutacao_cliente
    ADD COLUMN resultado_json JSON NULL AFTER erro,
    ADD COLUMN conflito_json JSON NULL AFTER resultado_json;
