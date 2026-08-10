-- Corrigir o que foi digitado errado, sem inventar um registro novo.
--
-- O catálogo só sabia acrescentar. Um serviço cadastrado com o nome trocado —
-- "Freasgem" no lugar de "Fresagem" — não tinha conserto: sobrava excluir e
-- cadastrar de novo, o que troca o identificador que os RDOs já citam. E um
-- preço digitado com um zero a mais só podia ser corrigido por substituição,
-- que grava no histórico uma revisão contratual que nunca existiu.
--
-- Corrigir e revisar são coisas diferentes. Revisar é um fato do contrato e
-- continua sendo uma versão nova. Corrigir é dizer que o registro nunca
-- descreveu o que se quis dizer — e isso só pode valer enquanto o registro não
-- produziu consequência.

ALTER TABLE service_catalog_mutation
    DROP CONSTRAINT chk_service_catalog_mutation_operation;

ALTER TABLE service_catalog_mutation
    ADD CONSTRAINT chk_service_catalog_mutation_operation CHECK (operation_type IN (
        'SERVICE_CREATED',
        'SERVICE_UPDATED',
        'SERVICE_EXCLUDED',
        'SERVICE_RESTORED',
        'SERVICE_PRICE_VERSION_CREATED',
        'SERVICE_PRICE_VERSION_UPDATED',
        'SERVICE_PRICE_VERSION_SUPERSEDED',
        'SERVICE_PRICE_VERSION_CANCELLED'
    ));

-- A validação de vigência morava só no INSERT, porque até aqui a tabela era
-- somente-acrescentar. Abrir a correção sem esta guarda deixaria um UPDATE
-- fazer, por caminho lateral, exatamente o que o INSERT recusa: dois preços
-- válidos para o mesmo serviço no mesmo dia.
CREATE OR REPLACE FUNCTION cortex_validate_service_price_version_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Identidade não se corrige. Obra, serviço, unidade, moeda e número da
    -- versão são o endereço do preço: mudá-los é apontar para outro preço, não
    -- consertar este.
    IF NEW.id <> OLD.id
       OR NEW.obra_id <> OLD.obra_id
       OR NEW.service_id <> OLD.service_id
       OR NEW.unidade <> OLD.unidade
       OR NEW.moeda <> OLD.moeda
       OR NEW.versao <> OLD.versao
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.criado_por <> OLD.criado_por THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_IDENTITY_IMMUTABLE';
    END IF;

    -- Só o carimbo de revisão mudou: nada a conferir.
    IF NEW.valor_unitario = OLD.valor_unitario
       AND NEW.quantidade_contratada IS NOT DISTINCT FROM OLD.quantidade_contratada
       AND NEW.vigencia_inicio = OLD.vigencia_inicio
       AND NEW.vigencia_fim IS NOT DISTINCT FROM OLD.vigencia_fim
       AND NEW.fonte = OLD.fonte THEN
        RETURN NEW;
    END IF;

    -- O preço já foi copiado para dentro de uma execução. A partir daí ele não
    -- é mais só um cadastro: é a prova do quanto aquela execução vale. Mexer
    -- aqui faria o registro contar uma história diferente da que a medição
    -- conta, e é isso que separa corrigir de reescrever o passado.
    IF EXISTS (
        SELECT 1
        FROM execucao_servico_rdo execucao
        WHERE execucao.price_version_id = NEW.id
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_ALREADY_USED';
    END IF;

    -- Substituído ou cancelado, o preço já é elo de uma corrente. Corrigi-lo
    -- deslocaria a vigência do sucessor sem que ninguém tivesse pedido.
    IF EXISTS (
        SELECT 1
        FROM service_price_version sucessor
        WHERE sucessor.supersedes_id = NEW.id
    ) OR EXISTS (
        SELECT 1
        FROM service_price_version_cancellation cancelamento
        WHERE cancelamento.price_version_id = NEW.id
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_ALREADY_TERMINATED';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.obra_id || ':' || NEW.service_id || ':' || NEW.unidade || ':' || NEW.moeda,
        0
    ));

    IF EXISTS (
        SELECT 1
        FROM service_price_version existente
        WHERE existente.obra_id = NEW.obra_id
          AND existente.service_id = NEW.service_id
          AND existente.unidade = NEW.unidade
          AND existente.moeda = NEW.moeda
          AND existente.id <> NEW.id
          AND daterange(
                existente.vigencia_inicio,
                COALESCE(
                    cortex_price_effective_valid_to(existente.id) + 1,
                    'infinity'::date
                ),
                '[)'
              ) && daterange(
                NEW.vigencia_inicio,
                COALESCE(NEW.vigencia_fim + 1, 'infinity'::date),
                '[)'
              )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_VALIDITY_OVERLAP';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_service_price_version_validate_update
BEFORE UPDATE ON service_price_version
FOR EACH ROW EXECUTE FUNCTION cortex_validate_service_price_version_update();

COMMENT ON FUNCTION cortex_validate_service_price_version_update() IS
    'Correção de preço registrado: permitida enquanto nenhuma execução o citou '
    'e nenhuma versão o substituiu ou cancelou.';
