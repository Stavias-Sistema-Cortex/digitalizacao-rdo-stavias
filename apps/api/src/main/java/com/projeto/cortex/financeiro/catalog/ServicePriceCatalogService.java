package com.projeto.cortex.financeiro.catalog;

import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CancelPriceRecord;
import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CreatePriceRecord;
import com.projeto.cortex.financeiro.catalog.ServicePriceCatalogRepository.CreateServiceRecord;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
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
    private static final Pattern UNIT = Pattern.compile("[A-Z0-9][A-Z0-9._/-]{0,29}");
    private static final Pattern SOURCE = Pattern.compile("[A-Z0-9][A-Z0-9._:-]{0,79}");
    private static final int MAX_PAGE_SIZE = 100;

    private final ServicePriceCatalogRepository repository;
    private final ServiceCatalogOntologyPublisher ontology;
    private final Clock clock;

    @Autowired
    public ServicePriceCatalogService(
            ServicePriceCatalogRepository repository,
            ServiceCatalogOntologyPublisher ontology
    ) {
        this(repository, ontology, Clock.systemUTC());
    }

    ServicePriceCatalogService(
            ServicePriceCatalogRepository repository,
            ServiceCatalogOntologyPublisher ontology,
            Clock clock
    ) {
        this.repository = repository;
        this.ontology = ontology;
        this.clock = clock;
    }

    @Transactional
    public ServiceCatalogEntry createService(
            String obraId,
            String actorId,
            CreateServiceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        String actor = uuid(actorId, "actorId");
        CreateServiceCommand normalized = normalize(command);
        String hash = requestHash(worksite, normalized);
        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayService(replay.orElseThrow(), hash);
        }

        String id = UUID.randomUUID().toString();
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

    @Transactional
    public ServicePriceVersion createPrice(
            String obraId,
            String actorId,
            String serviceId,
            CreateServicePriceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
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

        ServicePriceVersion created;
        try {
            created = repository.createPrice(new CreatePriceRecord(
                    UUID.randomUUID().toString(), worksite, serviceIdNormalized,
                    actor, normalized.clientMutationId(), hash,
                    normalized.unit(), normalized.currency(), normalized.unitPrice(),
                    normalized.validFrom(), normalized.validTo(), normalized.source(),
                    null, clock.instant()
            ));
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayPrice(worksite, concurrentReplay.receipt(), hash);
        } catch (ServicePriceValidityOverlapException overlap) {
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        } catch (DataIntegrityViolationException race) {
            Optional<CatalogMutation> receipt = repository.findMutation(
                    actor, normalized.clientMutationId()
            );
            if (receipt.isPresent()) {
                return replayPrice(worksite, receipt.orElseThrow(), hash);
            }
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        }
        ontology.priceVersionPublished(
                created, service, actor, normalized.clientMutationId()
        );
        return created;
    }

    @Transactional
    public ServicePriceVersion supersedePrice(
            String obraId,
            String actorId,
            String priceId,
            SupersedeServicePriceCommand command
    ) {
        String worksite = uuid(obraId, "obraId");
        String actor = uuid(actorId, "actorId");
        String previousId = uuid(priceId, "priceId");
        ServicePriceVersion previous = repository.findPrice(worksite, previousId)
                .orElseThrow(() -> notFound("SERVICE_PRICE_VERSION_NOT_FOUND"));
        SupersedeServicePriceCommand normalized = normalize(command, previous.validFrom());
        String hash = requestHash(worksite, previousId, normalized);
        Optional<CatalogMutation> replay = repository.findMutation(
                actor, normalized.clientMutationId()
        );
        if (replay.isPresent()) {
            return replayPrice(worksite, replay.orElseThrow(), hash);
        }
        ServiceCatalogEntry catalogService = repository.findService(previous.serviceId())
                .orElseThrow(() -> notFound("SERVICE_CATALOG_NOT_FOUND"));

        ServicePriceVersion replacement;
        try {
            replacement = repository.supersedePrice(new CreatePriceRecord(
                    UUID.randomUUID().toString(), worksite, previous.serviceId(), actor,
                    normalized.clientMutationId(), hash, previous.unit(),
                    previous.currency(), normalized.unitPrice(), normalized.validFrom(),
                    normalized.validTo(), normalized.source(), previous.id(), clock.instant()
            ));
        } catch (CatalogMutationReplayException concurrentReplay) {
            return replayPrice(worksite, concurrentReplay.receipt(), hash);
        } catch (ServicePriceValidityOverlapException overlap) {
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        } catch (DataIntegrityViolationException race) {
            Optional<CatalogMutation> receipt = repository.findMutation(
                    actor, normalized.clientMutationId()
            );
            if (receipt.isPresent()) {
                return replayPrice(worksite, receipt.orElseThrow(), hash);
            }
            throw conflict("SERVICE_PRICE_VALIDITY_OVERLAP");
        }
        ontology.priceVersionPublished(
                replacement, catalogService, actor, normalized.clientMutationId()
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
            Optional<CatalogMutation> receipt = repository.findMutation(
                    actor, normalized.clientMutationId()
            );
            if (receipt.isPresent()) {
                return replayPrice(worksite, receipt.orElseThrow(), hash);
            }
            throw conflict("SERVICE_PRICE_ALREADY_TERMINATED");
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
        return hash(Map.of(
                "operation", "SERVICE_PRICE_VERSION_CREATED",
                "obraId", uuid(obraId, "obraId"),
                "serviceId", uuid(serviceId, "serviceId"),
                "unit", normalized.unit(),
                "currency", normalized.currency(),
                "unitPrice", normalized.unitPrice().toPlainString(),
                "validFrom", normalized.validFrom().toString(),
                "validTo", nullText(normalized.validTo()),
                "source", normalized.source()
        ));
    }

    private static String requestHash(
            String obraId,
            String previousId,
            SupersedeServicePriceCommand command
    ) {
        return hash(Map.of(
                "operation", "SERVICE_PRICE_VERSION_SUPERSEDED",
                "obraId", obraId,
                "previousId", previousId,
                "unitPrice", command.unitPrice().toPlainString(),
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
                mutation,
                code,
                FinanceValidation.requiredText(command.name(), "nome", 160),
                FinanceValidation.optionalText(command.description(), "descricao", 500)
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
        return new CreateServicePriceCommand(
                FinanceValidation.mutationId(command.clientMutationId()),
                unit,
                FinanceValidation.currency(command.currency()),
                price(command.unitPrice()),
                from,
                to,
                source(command.source())
        );
    }

    private static SupersedeServicePriceCommand normalize(
            SupersedeServicePriceCommand command,
            LocalDate previousFrom
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
                FinanceValidation.mutationId(command.clientMutationId()),
                price(command.unitPrice()),
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
        return validated.setScale(4);
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
