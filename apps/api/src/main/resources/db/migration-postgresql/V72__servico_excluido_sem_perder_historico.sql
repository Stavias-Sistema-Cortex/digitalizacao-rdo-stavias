-- Excluir um serviço sem apagar o que ele já produziu.
--
-- O catálogo nasceu só com 'ACTIVE', preso por um CHECK que impedia qualquer
-- outro estado. Sem estado de saída, tirar um serviço do catálogo só poderia
-- ser DELETE — e um DELETE aqui levaria junto o histórico de preços e a
-- referência dos RDOs que já usaram o serviço, que é exatamente o dado que
-- não pode sumir: uma medição passada precisa continuar sabendo o que foi
-- executado e por qual preço.
--
-- A exclusão passa a ser um estado do serviço, não a ausência dele. Quem foi
-- excluído deixa de ser oferecido para lançamento novo e continua existindo,
-- legível, para tudo o que já aconteceu.

ALTER TABLE catalogo_servico
    DROP CONSTRAINT chk_catalogo_servico_status;

ALTER TABLE catalogo_servico
    ADD CONSTRAINT chk_catalogo_servico_status
        CHECK (status IN ('ACTIVE', 'EXCLUIDO'));

ALTER TABLE catalogo_servico
    ADD COLUMN excluido_em timestamptz,
    ADD COLUMN excluido_por varchar(36) REFERENCES colaborador(id) ON DELETE RESTRICT;

-- Os dois campos de auditoria andam junto com o estado: excluído sem autor e
-- sem data seria uma exclusão que ninguém assinou, e restaurado guardando a
-- data da exclusão anterior contaria uma história que não é mais verdade.
ALTER TABLE catalogo_servico
    ADD CONSTRAINT chk_catalogo_servico_exclusao_completa
        CHECK (
            (status = 'ACTIVE' AND excluido_em IS NULL AND excluido_por IS NULL)
            OR
            (status = 'EXCLUIDO' AND excluido_em IS NOT NULL AND excluido_por IS NOT NULL)
        );

-- O código continua único entre os serviços vivos. Um serviço excluído libera
-- o código para um novo — mas dois excluídos com o mesmo código também podem
-- coexistir, porque a unicidade deixa de valer para quem saiu do catálogo.
DROP INDEX uq_catalogo_servico_codigo_normalizado;

CREATE UNIQUE INDEX uq_catalogo_servico_codigo_normalizado
    ON catalogo_servico (lower(codigo))
    WHERE status = 'ACTIVE';

CREATE INDEX idx_catalogo_servico_status
    ON catalogo_servico (status);

-- O recibo de mutação é o que torna a operação idempotente: reenviar a mesma
-- exclusão pela fila offline devolve o resultado da primeira, em vez de uma
-- segunda exclusão. Os dois estados novos precisam existir nessa lista, senão
-- o recibo é recusado pelo banco e a operação nunca fica repetível.
ALTER TABLE service_catalog_mutation
    DROP CONSTRAINT chk_service_catalog_mutation_operation;

ALTER TABLE service_catalog_mutation
    ADD CONSTRAINT chk_service_catalog_mutation_operation CHECK (operation_type IN (
        'SERVICE_CREATED',
        'SERVICE_EXCLUDED',
        'SERVICE_RESTORED',
        'SERVICE_PRICE_VERSION_CREATED',
        'SERVICE_PRICE_VERSION_SUPERSEDED',
        'SERVICE_PRICE_VERSION_CANCELLED'
    ));
