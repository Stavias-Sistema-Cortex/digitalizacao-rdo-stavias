package com.projeto.cortex.financeiro.catalog;

import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CancelPriceRecord;
import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CreatePriceRecord;
import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CreateServiceRecord;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServicePriceCatalogService {

    private static final Pattern SERVICE_CODE = Pattern.compile(
            "[A-Z0-9][A-Z0-9._/-]{0,79}"
    );
    /*
     * Metro quadrado e metro cúbico se escrevem com expoente, e o catálogo
     * grava a unidade no símbolo — "m2" digitado vira "M²". O formato não
     * tinha lugar para ² nem ³, então todo serviço medido em área ou em
     * volume era aceito localmente, entrava na fila e voltava recusado, sem
     * que nada dissesse qual caractere era o culpado. Sobrava "M".
     */
    private static final Pattern UNIT =
            Pattern.compile("[A-Z0-9][A-Z0-9²³._/-]{0,29}");
    private static final Pattern SOURCE = Pattern.compile("[A-Z0-9][A-Z0-9._:-]{0,79}");
    private static final int MAX_PAGE_SIZE = 100;

    private final ServicePriceCatalogRepository repository;
    private final ServiceCatalogOntologyPublisher ontology;
    private final ObraOperabilityGuard operabilityGuard;
    private final Clock clock;

    @Autowired
    public ServicePriceCatalogService(
            ServicePriceCatalogRepository repository,
            ServiceCatalogOntologyPublisher ontology,
            ObraOperabilityGuard operabilityGuard
    ) {
        this(repository, ontology, operabilityGuard, Clock.systemUTC());
    }

    ServicePriceCatalogService(
            ServicePriceCatalogRepository repository,
            ServiceCatalogOntologyPublisher ontology,
            ObraOperabilityGuard operabilityGuard,
            Clock clock
    ) {
        this.repository = repository;
        this.ontology = ontology;
        this.operabilityGuard = operabilityGuard;
        this.clock = clock;
    }

    @Transactional
    public ServiceCatalogEntry createService(
            String obraId,
            String actorId,
            CreateServiceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        CreateServiceCommand normalized = normalize(command);
        String hash = requestHash(worksite, normalized);
        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayService(replay.orElseThrow(), hash);
        }
        operabilityGuard.requireWritable(worksite);

        String id = normalized.id() == null
                ? UUID.randomUUID().toString()
                : normalized.id();
        ServiceCatalogEntry created;
        try {
            created = repository.createService(new CreateServiceRecord(
                    id, worksite, actor, normalized.clientMutationId(), hash,
                    normalized.code(), normalized.name(), normalized.description(),
                    clock.instant()
            ));
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayService(concurrentReplay.receipt(), hash);
        } catch (ServiceCatalogCodeConflictException duplicateCode) {
            throw conflict("SERVICE_CATALOG_CODE_EXISTS");
        } catch (DataIntegrityViolationException race) {
            return repository.findMutation(actor, normalized.clientMutationId())
                    .map(receipt -> replayService(receipt, hash))
                    .orElseThrow(() -> race);
        }
        ontology.serviceCreated(
                created, worksite, actor, normalized.clientMutationId()
        );
        return created;
    }

    /**
     * Corrige o que foi escrito no cadastro do serviço.
     *
     * <p>Um serviço cadastrado com o nome trocado não tinha conserto: sobrava
     * excluir e cadastrar de novo, o que troca o identificador que os RDOs, as
     * versões de preço e as medições já citam. O identificador fica; o texto
     * muda.
     *
     * <p>Corrigir não é versionar. O catálogo versiona preço, porque o preço é
     * cláusula de contrato e a fronteira entre um valor e o seguinte é um fato
     * do mundo. O nome do serviço não é: escrever "Freasgem" nunca foi um
     * estado anterior verdadeiro, foi um dedo errado no teclado.
     *
     * <p>Repetível pelo recibo, como todo o resto do catálogo — e o recibo
     * confere o conteúdo, não só o identificador: reenviar a mesma correção
     * devolve o resultado da primeira, enquanto duas correções diferentes com o
     * mesmo identificador de mutação são recusadas em vez de se sobrescreverem.
     */
    @Transactional
    public ServiceCatalogEntry atualizarServico(
            String obraId,
            String actorId,
            String serviceId,
            UpdateServiceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        String service = uuid(serviceId, "serviceId");
        UpdateServiceCommand normalized = normalize(command);
        String hash = requestHash(worksite, service, normalized);

        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            CatalogMutation receipt = replay.orElseThrow();
            requireReplay(receipt, "SERVICE_UPDATED", hash);
            return repository.findService(receipt.entityId())
                    .orElseThrow(() -> conflict("SERVICE_CATALOG_REPLAY_MISSING"));
        }

        ServiceCatalogEntry atual = repository.findService(service)
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));
        /*
         * Nada mudou. Devolver o estado atual em vez de gravar um recibo vazio
         * mantém o gesto repetível na tela: quem abre a correção, olha e fecha
         * sem trocar nada não deveria gerar revisão de catálogo nenhuma.
         */
        if (normalized.code().equals(atual.code())
                && normalized.name().equals(atual.name())
                && Objects.equals(
                        normalized.description(), atual.description())) {
            return atual;
        }
        operabilityGuard.requireWritable(worksite);

        ServiceCatalogEntry corrigido;
        try {
            corrigido = repository.updateService(
                    new ServicePriceCatalogRepository.UpdateServiceRecord(
                            service, actor, normalized.clientMutationId(), hash,
                            normalized.code(), normalized.name(),
                            normalized.description(), clock.instant()
                    )
            );
        } catch (CatalogMutationReplayException concurrentReplay) {
            CatalogMutation receipt = concurrentReplay.receipt();
            requireReplay(receipt, "SERVICE_UPDATED", hash);
            return repository.findService(receipt.entityId())
                    .orElseThrow(() -> conflict("SERVICE_CATALOG_REPLAY_MISSING"));
        } catch (ServiceCatalogCodeConflictException duplicateCode) {
            throw conflict("SERVICE_CATALOG_CODE_EXISTS");
        } catch (DataIntegrityViolationException race) {
            return repository.findMutation(actor, normalized.clientMutationId())
                    .map(receipt -> {
                        requireReplay(receipt, "SERVICE_UPDATED", hash);
                        return repository.findService(receipt.entityId())
                                .orElseThrow(() ->
                                        conflict("SERVICE_CATALOG_REPLAY_MISSING"));
                    })
                    .orElseThrow(() -> race);
        }
        ontology.serviceUpdated(
                corrigido, worksite, actor, normalized.clientMutationId()
        );
        return corrigido;
    }

    /**
     * Tira o serviço do catálogo sem apagar o que ele já produziu.
     *
     * <p>O RDO que o executou, as versões de preço e as medições continuam
     * existindo e legíveis: o que muda é que ele deixa de ser oferecido para
     * lançamento novo. Restaurar é o mesmo movimento no sentido contrário.
     *
     * <p>Repetível pelo recibo, como todo o resto do catálogo — a fila offline
     * reenvia o pedido quando a rede vacila, e o segundo envio precisa devolver
     * o resultado do primeiro em vez de excluir de novo por cima de uma
     * restauração que veio depois.
     */
    @Transactional
    public ServiceCatalogEntry excluirServico(
            String obraId,
            String actorId,
            String serviceId,
            ExcludeServiceCommand command
    ) {
        return transicionarExclusao(obraId, actorId, serviceId, command, true);
    }

    @Transactional
    public ServiceCatalogEntry restaurarServico(
            String obraId,
            String actorId,
            String serviceId,
            ExcludeServiceCommand command
    ) {
        return transicionarExclusao(obraId, actorId, serviceId, command, false);
    }

    private ServiceCatalogEntry transicionarExclusao(
            String obraId,
            String actorId,
            String serviceId,
            ExcludeServiceCommand command,
            boolean excluir
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        String service = uuid(serviceId, "serviceId");
        String mutationId = FinanceValidation.mutationId(
                command == null ? null : command.clientMutationId()
        );
        String operacao = excluir ? "SERVICE_EXCLUDED" : "SERVICE_RESTORED";
        String hash = hash(Map.of(
                "operation", operacao,
                "obraId", worksite,
                "serviceId", service
        ));

        Optional<CatalogMutation> replay = repository.findMutation(actor, mutationId);
        if (replay.isPresent()) {
            CatalogMutation receipt = replay.orElseThrow();
            requireReplay(receipt, operacao, hash);
            return repository.findService(receipt.entityId())
                    .orElseThrow(() -> conflict("SERVICE_CATALOG_REPLAY_MISSING"));
        }

        ServiceCatalogEntry atual = repository.findService(service)
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));
        /*
         * Já está onde o pedido quer levá-lo. Devolver o estado atual em vez de
         * recusar mantém o gesto repetível na tela e na fila: excluir duas
         * vezes é excluir uma, e não um erro que a pessoa precise entender.
         */
        if (atual.excluded() == excluir) {
            return atual;
        }
        operabilityGuard.requireWritable(worksite);

        ServiceCatalogEntry transicionado;
        try {
            transicionado = repository.updateServiceExclusion(
                    new ServicePriceCatalogRepository.ServiceExclusionRecord(
                            service, actor, mutationId, hash, excluir, clock.instant()
                    )
            );
        } catch (DataIntegrityViolationException race) {
            return repository.findMutation(actor, mutationId)
                    .map(receipt -> {
                        requireReplay(receipt, operacao, hash);
                        return repository.findService(receipt.entityId())
                                .orElseThrow(() -> conflict("SERVICE_CATALOG_REPLAY_MISSING"));
                    })
                    .orElseThrow(() -> race);
        }
        /*
         * Publicar é parte de excluir, não um extra.
         *
         * <p>Era o único gesto do catálogo que mudava o banco sem contar à
         * ontologia — criar, corrigir e versionar preço sempre contaram. Só que
         * o valor contratual da obra soma apenas serviço vigente, e quem manda
         * o PDOR recalcular é a observação publicada aqui. Sem ela, o serviço
         * saía do catálogo e o teto do contrato calculado com ele dentro
         * continuava sendo o snapshot atual da obra — o número morto que o
         * Financeiro seguia mostrando.
         */
        ontology.serviceExclusionChanged(
                transicionado, worksite, excluir, actor, mutationId
        );
        return transicionado;
    }

    @Transactional
    public ServicePriceVersion createPrice(
            String obraId,
            String actorId,
            String serviceId,
            CreateServicePriceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        String serviceIdNormalized = uuid(serviceId, "serviceId");
        ServiceCatalogEntry service = repository.findService(serviceIdNormalized)
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));
        CreateServicePriceCommand normalized = normalize(command);
        String hash = requestHash(worksite, serviceIdNormalized, normalized);
        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayPrice(worksite, replay.orElseThrow(), hash);
        }
        operabilityGuard.requireWritable(worksite);

        ServicePriceVersion created;
        try {
            created = repository.createPrice(new CreatePriceRecord(
                    normalized.id() == null
                            ? UUID.randomUUID().toString()
                            : normalized.id(),
                    worksite, serviceIdNormalized,
                    actor, normalized.clientMutationId(), hash,
                    normalized.unit(), normalized.currency(), normalized.unitPrice(),
                    normalized.contractedQuantity(), normalized.validFrom(),
                    normalized.validTo(), normalized.source(),
                    null, clock.instant()
            ));
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayPrice(worksite, concurrentReplay.receipt(), hash);
        } catch (ServicePriceValidityOverlapException overlap) {
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        } catch (DataIntegrityViolationException race) {
            throw conflict("SERVICE_PRICE_WRITE_CONFLICT");
        }
        ontology.priceVersionPublished(
                created, service, actor, normalized.clientMutationId()
        );
        return created;
    }

    /**
     * Corrige um preço já registrado, no lugar, sem criar versão nova.
     *
     * <p>Substituir era o único caminho para trocar um valor, e ele grava no
     * histórico uma revisão contratual: a versão 1 valeu até tal dia, a versão
     * 2 vale de lá em diante. Para um zero a mais digitado ontem, isso é
     * inventar um aditivo que nunca existiu — e a receita passaria a medir dois
     * períodos com preços diferentes por causa de um erro de digitação.
     *
     * <p>A correção só vale enquanto o registro não produziu consequência. Quem
     * decide isso é o banco, dentro da mesma transação da escrita: preço já
     * citado por uma execução, já substituído ou já cancelado é recusado. Entre
     * ler "ninguém usou" e escrever há uma janela em que outro aparelho pode ter
     * validado a execução que usa este preço, e essa janela não existe lá.
     *
     * <p>Depois disso, o caminho continua sendo {@link #supersedePrice}, que é o
     * certo para o que de fato mudou no contrato.
     */
    @Transactional
    public ServicePriceVersion atualizarPreco(
            String obraId,
            String actorId,
            String priceId,
            UpdateServicePriceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        String normalizedPriceId = uuid(priceId, "priceId");
        UpdateServicePriceCommand normalized = normalize(command);
        String hash = requestHash(worksite, normalizedPriceId, normalized);

        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayPrice(worksite, replay.orElseThrow(), hash);
        }

        ServicePriceVersion atual = repository.findPrice(worksite, normalizedPriceId)
                .orElseThrow(() -> notFound("SERVICE_PRICE_VERSION_NOT_FOUND"));
        operabilityGuard.requireWritable(worksite);
        ServiceCatalogEntry catalogService = repository.findService(atual.serviceId())
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));

        ServicePriceVersion corrigido;
        try {
            corrigido = repository.updatePrice(
                    new ServicePriceCatalogRepository.UpdatePriceRecord(
                            normalizedPriceId, worksite, actor,
                            normalized.clientMutationId(), hash,
                            normalized.unit(),
                            normalized.unitPrice(),
                            normalized.contractedQuantity(),
                            normalized.validFrom(), normalized.validTo(),
                            normalized.source(), clock.instant()
                    )
            );
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayPrice(worksite, concurrentReplay.receipt(), hash);
        } catch (ServicePriceValidityOverlapException overlap) {
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        } catch (ServicePriceCancellationException terminal) {
            throw conflict(terminal.getMessage());
        } catch (DataIntegrityViolationException race) {
            throw conflict("SERVICE_PRICE_WRITE_CONFLICT");
        }
        ontology.priceVersionCorrected(
                corrigido, catalogService, actor, normalized.clientMutationId()
        );
        return corrigido;
    }

    @Transactional
    public ServicePriceVersion supersedePrice(
            String obraId,
            String actorId,
            String priceId,
            SupersedeServicePriceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        String previousId = uuid(priceId, "priceId");
        ServicePriceVersion previous = repository.findPrice(worksite, previousId)
                .orElseThrow(() -> notFound("SERVICE_PRICE_VERSION_NOT_FOUND"));
        SupersedeServicePriceCommand normalized = normalize(
                command, previous.validFrom(), previous.contractedQuantity()
        );
        String hash = requestHash(worksite, previousId, normalized);
        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayPrice(worksite, replay.orElseThrow(), hash);
        }
        operabilityGuard.requireWritable(worksite);
        ServiceCatalogEntry catalogService = repository.findService(previous.serviceId())
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));

        ServicePriceVersion replacement;
        try {
            replacement = repository.supersedePrice(new CreatePriceRecord(
                    normalized.id() == null
                            ? UUID.randomUUID().toString()
                            : normalized.id(),
                    worksite, previous.serviceId(), actor,
                    normalized.clientMutationId(), hash, previous.unit(),
                    previous.currency(), normalized.unitPrice(),
                    normalized.contractedQuantity(), normalized.validFrom(),
                    normalized.validTo(), normalized.source(), previous.id(),
                    clock.instant()
            ));
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayPrice(worksite, concurrentReplay.receipt(), hash);
        } catch (ServicePriceValidityOverlapException overlap) {
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        } catch (ServicePriceCancellationException terminal) {
            throw conflict(terminal.getMessage());
        } catch (DataIntegrityViolationException race) {
            throw conflict("SERVICE_PRICE_WRITE_CONFLICT");
        }
        ontology.priceVersionSuperseded(
                previous,
                replacement,
                catalogService,
                actor,
                normalized.clientMutationId()
        );
        return replacement;
    }

    @Transactional
    public ServicePriceVersion cancelPrice(
            String obraId,
            String actorId,
            String priceId,
            CancelServicePriceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String actor = uuid(actorId, "actorId");
        String normalizedPriceId = uuid(priceId, "priceId");
        ServicePriceVersion previous = repository.findPrice(worksite, normalizedPriceId)
                .orElseThrow(() -> notFound("SERVICE_PRICE_VERSION_NOT_FOUND"));
        CancelServicePriceCommand normalized = normalize(command, previous.validFrom());
        String hash = requestHash(worksite, normalizedPriceId, normalized);
        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayPrice(worksite, replay.orElseThrow(), hash);
        }
        ServiceCatalogEntry catalogService = repository.findService(previous.serviceId())
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));
        try {
            ServicePriceVersion cancelled = repository.cancelPrice(new CancelPriceRecord(
                    UUID.randomUUID().toString(), worksite, normalizedPriceId, actor,
                    normalized.clientMutationId(), hash, normalized.effectiveAt(),
                    normalized.reason(), clock.instant()
            ));
            ontology.priceVersionCancelled(
                    cancelled,
                    catalogService,
                    actor,
                    normalized.clientMutationId()
            );
            return cancelled;
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayPrice(worksite, concurrentReplay.receipt(), hash);
        } catch (ServicePriceCancellationException conflict) {
            throw conflict(conflict.getMessage());
        } catch (DataIntegrityViolationException race) {
            throw conflict("SERVICE_PRICE_WRITE_CONFLICT");
        }
    }

    @Transactional(readOnly = true)
    public ServiceCatalogPage list(
            String obraId,
            String query,
            String cursor,
            Integer limit
    ) {
        String worksite = uuid(obraId, "obraId");
        requireWorksite(worksite);
        String normalizedQuery = query == null ? null
                : FinanceValidation.optionalText(query, "query", 200);
        String normalizedCursor = cursor == null ? null
                : FinanceValidation.optionalText(cursor, "cursor", 1024);
        int normalizedLimit = limit == null ? 50 : limit;
        if (normalizedLimit < 1 || normalizedLimit > MAX_PAGE_SIZE) {
            throw FinanceValidation.badRequest("limit deve estar entre 1 e 100.");
        }
        return repository.list(
                worksite, normalizedQuery, normalizedCursor, normalizedLimit
        );
    }

    static String requestHash(String obraId, CreateServiceCommand command) {
        CreateServiceCommand normalized = normalize(command);
        return hash(Map.of(
                "operation", "SERVICE_CREATED",
                "id", nullText(normalized.id()),
                "obraId", uuid(obraId, "obraId"),
                "code", normalized.code(),
                "name", normalized.name(),
                "description", nullText(normalized.description())
        ));
    }

    static String requestHash(
            String obraId,
            String serviceId,
            CreateServicePriceCommand command
    ) {
        CreateServicePriceCommand normalized = normalize(command);
        return hash(Map.ofEntries(
                Map.entry("operation", "SERVICE_PRICE_VERSION_CREATED"),
                Map.entry("id", nullText(normalized.id())),
                Map.entry("obraId", uuid(obraId, "obraId")),
                Map.entry("serviceId", uuid(serviceId, "serviceId")),
                Map.entry("unit", normalized.unit()),
                Map.entry("currency", normalized.currency()),
                Map.entry("unitPrice", normalized.unitPrice().toPlainString()),
                Map.entry(
                        "contractedQuantity",
                        normalized.contractedQuantity().toPlainString()
                ),
                Map.entry("validFrom", normalized.validFrom().toString()),
                Map.entry("validTo", nullText(normalized.validTo())),
                Map.entry("source", normalized.source())
        ));
    }

    private static String requestHash(
            String obraId,
            String serviceId,
            UpdateServiceCommand command
    ) {
        return hash(Map.of(
                "operation", "SERVICE_UPDATED",
                "obraId", obraId,
                "serviceId", serviceId,
                "code", command.code(),
                "name", command.name(),
                "description", nullText(command.description())
        ));
    }

    private static String requestHash(
            String obraId,
            String priceId,
            UpdateServicePriceCommand command
    ) {
        return hash(Map.of(
                "operation", "SERVICE_PRICE_VERSION_UPDATED",
                "obraId", obraId,
                "priceId", priceId,
                "unit", command.unit(),
                "unitPrice", command.unitPrice().toPlainString(),
                "contractedQuantity", command.contractedQuantity() == null
                        ? ""
                        : command.contractedQuantity().toPlainString(),
                "validFrom", command.validFrom().toString(),
                "validTo", nullText(command.validTo()),
                "source", command.source()
        ));
    }

    private static String requestHash(
            String obraId,
            String previousId,
            SupersedeServicePriceCommand command
    ) {
        return hash(Map.of(
                "operation", "SERVICE_PRICE_VERSION_SUPERSEDED",
                "id", nullText(command.id()),
                "obraId", obraId,
                "previousId", previousId,
                "unitPrice", command.unitPrice().toPlainString(),
                "contractedQuantity",
                command.contractedQuantity().toPlainString(),
                "validFrom", command.validFrom().toString(),
                "validTo", nullText(command.validTo()),
                "source", command.source()
        ));
    }

    private static String requestHash(
            String obraId,
            String priceId,
            CancelServicePriceCommand command
    ) {
        return hash(Map.of(
                "operation", "SERVICE_PRICE_VERSION_CANCELLED",
                "obraId", obraId,
                "priceId", priceId,
                "effectiveAt", command.effectiveAt().toString(),
                "reason", command.reason()
        ));
    }

    private ServiceCatalogEntry replayService(CatalogMutation receipt, String hash) {
        requireReplay(receipt, "SERVICE_CREATED", hash);
        return repository.findService(receipt.entityId())
                .orElseThrow(() -> conflict("SERVICE_CATALOG_REPLAY_MISSING"));
    }

    private ServicePriceVersion replayPrice(
            String obraId,
            CatalogMutation receipt,
            String hash
    ) {
        if (!receipt.operationType().startsWith("SERVICE_PRICE_VERSION_")) {
            throw conflict("SERVICE_CATALOG_IDEMPOTENCY_CONFLICT");
        }
        requireReplay(receipt, receipt.operationType(), hash);
        return repository.findPrice(obraId, receipt.entityId())
                .orElseThrow(() -> conflict("SERVICE_PRICE_REPLAY_MISSING"));
    }

    private static void requireReplay(
            CatalogMutation receipt,
            String operation,
            String hash
    ) {
        if (!operation.equals(receipt.operationType())
                || !MessageDigest.isEqual(
                        hash.getBytes(StandardCharsets.US_ASCII),
                        receipt.requestHash().getBytes(StandardCharsets.US_ASCII)
                )) {
            throw conflict("SERVICE_CATALOG_IDEMPOTENCY_CONFLICT");
        }
    }

    private static CreateServiceCommand normalize(CreateServiceCommand command) {
        if (command == null) {
            throw FinanceValidation.badRequest("Dados do serviço são obrigatórios.");
        }
        String mutation = FinanceValidation.mutationId(command.clientMutationId());
        String code = FinanceValidation.requiredText(command.code(), "codigo", 80)
                .toUpperCase(Locale.ROOT);
        if (!SERVICE_CODE.matcher(code).matches()) {
            throw FinanceValidation.badRequest("codigo de serviço inválido.");
        }
        return new CreateServiceCommand(
                FinanceValidation.optionalUuid(command.id(), "id"),
                mutation,
                code,
                FinanceValidation.requiredText(command.name(), "nome", 160),
                FinanceValidation.optionalText(command.description(), "descricao", 500)
        );
    }

    private static UpdateServiceCommand normalize(UpdateServiceCommand command) {
        if (command == null) {
            throw FinanceValidation.badRequest(
                    "Dados da correção do serviço são obrigatórios."
            );
        }
        String code = FinanceValidation.requiredText(command.code(), "codigo", 80)
                .toUpperCase(Locale.ROOT);
        if (!SERVICE_CODE.matcher(code).matches()) {
            throw FinanceValidation.badRequest("codigo de serviço inválido.");
        }
        return new UpdateServiceCommand(
                FinanceValidation.mutationId(command.clientMutationId()),
                code,
                FinanceValidation.requiredText(command.name(), "nome", 160),
                FinanceValidation.optionalText(command.description(), "descricao", 500)
        );
    }

    private static UpdateServicePriceCommand normalize(
            UpdateServicePriceCommand command
    ) {
        if (command == null) {
            throw FinanceValidation.badRequest(
                    "Dados da correção do preço são obrigatórios."
            );
        }
        LocalDate from = requiredDate(command.validFrom(), "vigenciaInicio");
        LocalDate to = command.validTo();
        if (to != null && to.isBefore(from)) {
            throw FinanceValidation.badRequest(
                    "vigenciaFim não pode ser anterior à vigenciaInicio."
            );
        }
        String unit = FinanceValidation.requiredText(command.unit(), "unidade", 30)
                .toUpperCase(Locale.ROOT);
        if (!UNIT.matcher(unit).matches()) {
            throw FinanceValidation.badRequest("unidade inválida.");
        }
        return new UpdateServicePriceCommand(
                FinanceValidation.mutationId(command.clientMutationId()),
                unit,
                price(command.unitPrice()),
                /*
                 * Ausência continua sendo ausência. Versões anteriores à V60 não
                 * têm quantidade contratada, e exigir uma aqui obrigaria quem só
                 * quer corrigir o valor a inventar um número de contrato.
                 */
                command.contractedQuantity() == null
                        ? null
                        : contractedQuantity(command.contractedQuantity()),
                from,
                to,
                source(command.source())
        );
    }

    private static CreateServicePriceCommand normalize(
            CreateServicePriceCommand command
    ) {
        if (command == null) {
            throw FinanceValidation.badRequest("Dados do preço são obrigatórios.");
        }
        String unit = FinanceValidation.requiredText(command.unit(), "unidade", 30)
                .toUpperCase(Locale.ROOT);
        if (!UNIT.matcher(unit).matches()) {
            throw FinanceValidation.badRequest("unidade inválida.");
        }
        LocalDate from = requiredDate(command.validFrom(), "vigenciaInicio");
        LocalDate to = command.validTo();
        if (to != null && to.isBefore(from)) {
            throw FinanceValidation.badRequest(
                    "vigenciaFim não pode ser anterior à vigenciaInicio."
            );
        }
        String currency = FinanceValidation.currency(command.currency());
        if (!"BRL".equals(currency)) {
            throw FinanceValidation.badRequest("moeda deve ser BRL.");
        }
        return new CreateServicePriceCommand(
                FinanceValidation.optionalUuid(command.id(), "id"),
                FinanceValidation.mutationId(command.clientMutationId()),
                unit,
                currency,
                price(command.unitPrice()),
                contractedQuantity(command.contractedQuantity()),
                from,
                to,
                source(command.source())
        );
    }

    private static SupersedeServicePriceCommand normalize(
            SupersedeServicePriceCommand command,
            LocalDate previousFrom,
            BigDecimal previousContractedQuantity
    ) {
        if (command == null) {
            throw FinanceValidation.badRequest("Dados da substituição são obrigatórios.");
        }
        LocalDate from = requiredDate(command.validFrom(), "vigenciaInicio");
        if (!from.isAfter(previousFrom)) {
            throw FinanceValidation.badRequest(
                    "A substituição deve iniciar depois da versão anterior."
            );
        }
        if (command.validTo() != null && command.validTo().isBefore(from)) {
            throw FinanceValidation.badRequest(
                    "vigenciaFim não pode ser anterior à vigenciaInicio."
            );
        }
        return new SupersedeServicePriceCommand(
                FinanceValidation.optionalUuid(command.id(), "id"),
                FinanceValidation.mutationId(command.clientMutationId()),
                price(command.unitPrice()),
                contractedQuantity(
                        command.contractedQuantity() == null
                                ? previousContractedQuantity
                                : command.contractedQuantity()
                ),
                from,
                command.validTo(),
                source(command.source())
        );
    }

    private static CancelServicePriceCommand normalize(
            CancelServicePriceCommand command,
            LocalDate priceFrom
    ) {
        if (command == null) {
            throw FinanceValidation.badRequest("Dados do cancelamento são obrigatórios.");
        }
        LocalDate effectiveAt = requiredDate(command.effectiveAt(), "vigenciaCancelamento");
        if (effectiveAt.isBefore(priceFrom)) {
            throw FinanceValidation.badRequest(
                    "vigenciaCancelamento não pode ser anterior à versão."
            );
        }
        return new CancelServicePriceCommand(
                FinanceValidation.mutationId(command.clientMutationId()),
                effectiveAt,
                FinanceValidation.requiredText(command.reason(), "motivo", 500)
        );
    }

    private static BigDecimal price(BigDecimal value) {
        BigDecimal validated = FinanceValidation.money(value, "valorUnitario");
        if (validated.precision() - validated.scale() > 14) {
            throw FinanceValidation.badRequest(
                    "valorUnitario deve ter no máximo 14 dígitos inteiros."
            );
        }
        return validated.setScale(4);
    }

    private static BigDecimal contractedQuantity(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.scale() > 3
                || value.precision() - value.scale() > 15) {
            throw FinanceValidation.badRequest(
                    "quantidadeContratada deve ser positiva e ter no máximo "
                            + "três casas decimais."
            );
        }
        return value.setScale(3);
    }

    private void requireWorksite(String worksiteId) {
        if (!repository.worksiteExists(worksiteId)) {
            throw notFound("SERVICE_CATALOG_WORKSITE_NOT_FOUND");
        }
    }

    private static String source(String value) {
        String normalized = FinanceValidation.requiredText(value, "fonte", 80)
                .toUpperCase(Locale.ROOT);
        if (!SOURCE.matcher(normalized).matches()) {
            throw FinanceValidation.badRequest("fonte inválida.");
        }
        return normalized;
    }

    private static LocalDate requiredDate(LocalDate value, String field) {
        if (value == null) {
            throw FinanceValidation.badRequest(field + " é obrigatória.");
        }
        return value;
    }

    private static String uuid(String value, String field) {
        return FinanceValidation.uuid(value, field);
    }

    private static String hash(Map<String, ?> values) {
        String canonical = values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> framed(entry.getKey()) + framed(entry.getValue()))
                .reduce((left, right) -> left + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível.", impossible);
        }
    }

    private static String nullText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String framed(Object value) {
        String text = String.valueOf(value);
        int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return utf8Bytes + ":" + text;
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private static ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }
}
