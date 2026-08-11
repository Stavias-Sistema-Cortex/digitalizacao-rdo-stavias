-- Corrigir a unidade de um preço registrado.
--
-- Antes da V74 o cadastro não aceitava M² nem M³: o formato da unidade não
-- tinha lugar para expoente, então todo serviço medido em área ou em volume
-- entrava como "M" — a única coisa que passava. O conserto da V74 abriu o
-- cadastro novo e deixou o passado onde estava: os preços já registrados
-- continuam dizendo metro linear sobre serviços medidos em metro quadrado.
--
-- Não havia caminho de volta. A V75 abriu a correção do valor, da quantidade,
-- da vigência e da fonte, mas manteve a unidade como identidade — junto com
-- obra, serviço, moeda e número da versão. Substituir também não resolve:
-- herda a unidade da versão anterior. E cadastrar de novo deixa a versão errada
-- no histórico, dizendo que um dia se contratou por metro linear.
--
-- A unidade passa a ser corrigível sob a mesma guarda de tudo o mais: enquanto
-- nenhuma execução citou o preço e nada o substituiu ou cancelou. O que
-- continua imutável é o que de fato identifica o registro — obra, serviço,
-- moeda, autoria — e o identificador que os RDOs citam.

CREATE OR REPLACE FUNCTION cortex_validate_service_price_version_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Obra, serviço, moeda e autoria não se corrigem: mudá-los é apontar para
    -- outro preço, não consertar este.
    IF NEW.id <> OLD.id
       OR NEW.obra_id <> OLD.obra_id
       OR NEW.service_id <> OLD.service_id
       OR NEW.moeda <> OLD.moeda
       OR NEW.supersedes_id IS DISTINCT FROM OLD.supersedes_id
       OR NEW.criado_por <> OLD.criado_por
       OR NEW.criado_em <> OLD.criado_em THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_IDENTITY_IMMUTABLE';
    END IF;

    -- O número da versão é por obra, serviço, unidade e moeda. Ele só se move
    -- junto com a unidade, porque mudar de unidade é entrar noutra sequência —
    -- onde o número antigo pode já pertencer a outro preço. Sozinho, ele é
    -- identidade como o resto.
    IF NEW.versao <> OLD.versao AND NEW.unidade = OLD.unidade THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SERVICE_PRICE_IDENTITY_IMMUTABLE';
    END IF;

    -- Só o carimbo de revisão mudou: nada a conferir.
    IF NEW.unidade = OLD.unidade
       AND NEW.valor_unitario = OLD.valor_unitario
       AND NEW.quantidade_contratada IS NOT DISTINCT FROM OLD.quantidade_contratada
       AND NEW.vigencia_inicio = OLD.vigencia_inicio
       AND NEW.vigencia_fim IS NOT DISTINCT FROM OLD.vigencia_fim
       AND NEW.fonte = OLD.fonte THEN
        RETURN NEW;
    END IF;

    -- O preço já foi copiado para dentro de uma execução. A partir daí ele não
    -- é mais só um cadastro: é a prova do quanto aquela execução vale. Isso
    -- vale em dobro para a unidade, que é parte da chave estrangeira pela qual
    -- a execução o cita.
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

COMMENT ON FUNCTION cortex_validate_service_price_version_update() IS
    'Correção de preço registrado, unidade inclusive: permitida enquanto '
    'nenhuma execução o citou e nenhuma versão o substituiu ou cancelou.';
