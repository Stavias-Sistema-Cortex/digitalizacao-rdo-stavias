package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationHeader;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationHeaderWrite;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationHistoryRecord;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationHistoryWrite;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationItemRecord;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationItemWrite;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationMutation;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationSnapshot;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationSource;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationRepository.AllocationUnit;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinanceOntologyRelation;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinanceAllocationService {

    private static final int VALUE_SCALE = 4;
    private static final int PERCENTAGE_SCALE = 6;

    private final FinanceAllocationRepository repository;
    private final FinancialAccessService access;
    private final FinanceOntologyProjector ontology;
    private final ObjectMapper objectMapper;

    public FinanceAllocationService(
            FinanceAllocationRepository repository,
            FinancialAccessService access,
            FinanceOntologyProjector ontology,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.access = access;
        this.ontology = ontology;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AllocationResponse save(
            SaveAllocationRequest rawRequest,
            FinanceAuditContext rawAudit
    ) {
        SaveAllocationRequest request = validateRequest(rawRequest);
        FinanceAuditContext audit = validateAudit(rawAudit, request);
        String actorId = audit.actorId();

        Optional<AllocationResponse> repeated = repeatedMutation(
                request,
                actorId
        );
        if (repeated.isPresent()) {
            return repeated.orElseThrow();
        }
        try {
            repository.claimMutation(
                    actorId,
                    request.clientMutationId(),
                    request.origemTipo(),
                    request.origemId()
            );
        } catch (DuplicateKeyException exception) {
            return repeatedMutation(request, actorId, true).orElseThrow(() ->
                    conflict(
                            "A mutação idempotente concorrente não pôde ser recuperada."
                    )
            );
        }

        AllocationSource source = repository.lockSource(
                request.origemTipo(),
                request.origemId()
        ).orElseThrow(() -> notFound("Origem financeira não encontrada."));
        if (source.archived()) {
            throw badRequest("Não é possível ratear uma origem arquivada.");
        }
        BigDecimal authoritativeTotal = exactValue(source.total(), "valor da origem");
        if (authoritativeTotal.signum() <= 0) {
            throw badRequest("O valor da origem deve ser maior que zero.");
        }

        Optional<AllocationSnapshot> current = repository.lockBySource(
                request.origemTipo(),
                request.origemId()
        );
        validateVersionAndCurrency(request, source, current);

        List<NormalizedItem> normalizedItems = normalizeItems(
                request.itens(),
                authoritativeTotal
        );
        Map<String, AllocationUnit> units = validateUnits(normalizedItems);
        validateCatalogReferences(normalizedItems);
        units.keySet().forEach(unitId -> access.requireUnitPermission(
                unitId,
                FinancialPermission.FINANCEIRO_OPERAR
        ));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AllocationSnapshot next = current.isEmpty()
                ? create(request, source, normalizedItems, actorId, now)
                : update(
                        request,
                        source,
                        current.orElseThrow(),
                        normalizedItems,
                        actorId,
                        now
                );

        String previousJson = current.map(this::json).orElse(null);
        String nextJson = json(next);
        repository.completeMutation(
                actorId,
                request.clientMutationId(),
                next.header().id()
        );
        repository.insertHistory(new AllocationHistoryWrite(
                UUID.randomUUID().toString(),
                next.header().id(),
                request.clientMutationId(),
                current.isEmpty() ? "CRIAR" : "ATUALIZAR",
                current.map(snapshot -> snapshot.header().version())
                        .orElse(null),
                next.header().version(),
                previousJson,
                nextJson,
                actorId,
                audit.correlationId(),
                audit.deviceId()
        ));

        Set<String> desiredUnitIds = units.keySet();
        current.ifPresent(previous -> {
            Set<String> removedUnitIds = new LinkedHashSet<>();
            previous.items().forEach(item -> removedUnitIds.add(item.unitId()));
            removedUnitIds.removeAll(desiredUnitIds);
            removedUnitIds.forEach(unitId -> ontology.endRelation(
                    "RATEIO_FINANCEIRO",
                    next.header().id(),
                    "UNIDADE_FINANCEIRA",
                    unitId,
                    "RATEADO_PARA"
            ));
        });

        AllocationResponse response = response(next);
        ontology.successWithRelations(
                "RATEIO_FINANCEIRO",
                response.id(),
                "Rateio " + request.origemTipo() + " " + request.origemId(),
                current.isEmpty()
                        ? "RATEIO_FINANCEIRO_CRIADO"
                        : "RATEIO_FINANCEIRO_ATUALIZADO",
                source.worksiteId(),
                audit,
                state(current.map(this::response).orElse(null)),
                state(response),
                units.keySet().stream()
                        .map(unitId -> new FinanceOntologyRelation(
                                "UNIDADE_FINANCEIRA",
                                unitId,
                                "RATEADO_PARA",
                                "Destino explícito do rateio financeiro."
                        ))
                        .toList()
        );
        return response;
    }

    private Optional<AllocationResponse> repeatedMutation(
            SaveAllocationRequest request,
            String actorId
    ) {
        return repeatedMutation(request, actorId, false);
    }

    private Optional<AllocationResponse> repeatedMutation(
            SaveAllocationRequest request,
            String actorId,
            boolean currentRead
    ) {
        Optional<AllocationMutation> repeated = currentRead
                ? repository.findMutationCurrent(
                        actorId,
                        request.clientMutationId()
                )
                : repository.findMutation(
                        actorId,
                        request.clientMutationId()
                );
        if (repeated.isEmpty()) {
            return Optional.empty();
        }
        AllocationMutation mutation = repeated.orElseThrow();
        if (mutation.sourceType() != request.origemTipo()
                || !mutation.sourceId().equals(request.origemId())) {
            throw conflict("clientMutationId já foi usado em outra origem.");
        }
        if (blank(mutation.allocationId())) {
            throw conflict(
                    "A mutação idempotente ainda não possui resultado canônico."
            );
        }
        Optional<AllocationSnapshot> storedResult = currentRead
                ? repository.findByIdCurrent(mutation.allocationId())
                : repository.findById(mutation.allocationId());
        AllocationSnapshot stored = storedResult.orElseThrow(() -> conflict(
                "A mutação existe, mas o rateio correspondente não foi encontrado."
        ));
        requirePermissions(
                stored.items(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        return Optional.of(response(stored));
    }

    @Transactional(readOnly = true)
    public AllocationResponse findBySource(
            AllocationSourceType type,
            String sourceId
    ) {
        if (type == null || blank(sourceId)) {
            throw badRequest("Tipo e id da origem são obrigatórios.");
        }
        AllocationSnapshot snapshot = repository.findBySource(
                type,
                sourceId.trim()
        ).orElseThrow(() -> notFound("Rateio não encontrado."));
        requirePermissions(
                snapshot.items(),
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return response(snapshot);
    }

    @Transactional(readOnly = true)
    public AllocationHistoryResponse history(String allocationId) {
        if (blank(allocationId)) {
            throw badRequest("rateioId é obrigatório.");
        }
        AllocationSnapshot snapshot = repository.findById(allocationId.trim())
                .orElseThrow(() -> notFound("Rateio não encontrado."));
        requirePermissions(
                snapshot.items(),
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        List<AllocationHistoryEntry> entries = repository.history(
                allocationId.trim()
        ).stream().map(this::historyEntry).toList();
        return new AllocationHistoryResponse(allocationId.trim(), entries);
    }

    private AllocationSnapshot create(
            SaveAllocationRequest request,
            AllocationSource source,
            List<NormalizedItem> items,
            String actorId,
            LocalDateTime now
    ) {
        String id = UUID.randomUUID().toString();
        repository.insertHeader(new AllocationHeaderWrite(
                id,
                request.clientMutationId(),
                request.origemTipo(),
                request.origemId(),
                source.currency(),
                source.total().setScale(VALUE_SCALE),
                actorId
        ));
        List<AllocationItemWrite> writes = itemWrites(items);
        repository.replaceItems(id, writes, actorId);
        AllocationSnapshot fallback = new AllocationSnapshot(
                new AllocationHeader(
                        id,
                        request.clientMutationId(),
                        request.origemTipo(),
                        request.origemId(),
                        source.currency(),
                        source.total().setScale(VALUE_SCALE),
                        "ATIVO",
                        actorId,
                        now,
                        actorId,
                        now,
                        0L
                ),
                itemRecords(writes)
        );
        return repository.findById(id).orElse(fallback);
    }

    private AllocationSnapshot update(
            SaveAllocationRequest request,
            AllocationSource source,
            AllocationSnapshot current,
            List<NormalizedItem> items,
            String actorId,
            LocalDateTime now
    ) {
        AllocationHeader old = current.header();
        if (!repository.updateHeader(
                old.id(),
                old.version(),
                source.total().setScale(VALUE_SCALE),
                actorId
        )) {
            throw conflict(
                    "O rateio foi alterado por outra operação. Recarregue os dados."
            );
        }
        List<AllocationItemWrite> writes = itemWrites(items);
        repository.replaceItems(old.id(), writes, actorId);
        AllocationSnapshot fallback = new AllocationSnapshot(
                new AllocationHeader(
                        old.id(),
                        old.clientMutationId(),
                        old.sourceType(),
                        old.sourceId(),
                        old.currency(),
                        source.total().setScale(VALUE_SCALE),
                        old.status(),
                        old.createdBy(),
                        old.createdAt(),
                        actorId,
                        now,
                        old.version() + 1
                ),
                itemRecords(writes)
        );
        return repository.findById(old.id()).orElse(fallback);
    }

    private SaveAllocationRequest validateRequest(
            SaveAllocationRequest request
    ) {
        if (request == null) {
            throw badRequest("O rateio é obrigatório.");
        }
        if (blank(request.clientMutationId())
                || request.clientMutationId().trim().length() > 120) {
            throw badRequest("clientMutationId é obrigatório e deve ter até 120 caracteres.");
        }
        if (request.baseVersion() == null || request.baseVersion() < 0) {
            throw badRequest("baseVersion é obrigatório e não pode ser negativo.");
        }
        if (request.origemTipo() == null || blank(request.origemId())) {
            throw badRequest("Tipo e id da origem são obrigatórios.");
        }
        if (request.itens() == null || request.itens().isEmpty()) {
            throw badRequest("O rateio deve possuir ao menos um item.");
        }
        if (request.itens().size() > 500) {
            throw badRequest("O rateio excede o limite de 500 itens.");
        }
        return new SaveAllocationRequest(
                request.clientMutationId().trim(),
                request.baseVersion(),
                request.origemTipo(),
                request.origemId().trim(),
                request.itens(),
                normalizeOptional(request.correlacaoId(), 120, "correlacaoId"),
                normalizeOptional(request.dispositivoId(), 120, "dispositivoId")
        );
    }

    private FinanceAuditContext validateAudit(
            FinanceAuditContext audit,
            SaveAllocationRequest request
    ) {
        if (audit == null || blank(audit.actorId())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Ator autenticado é obrigatório."
            );
        }
        return new FinanceAuditContext(
                audit.actorId().trim(),
                request.dispositivoId() == null
                        ? audit.deviceId()
                        : request.dispositivoId(),
                request.correlacaoId() == null
                        ? audit.correlationId()
                        : request.correlacaoId(),
                blank(audit.origin()) ? "ONLINE" : audit.origin()
        );
    }

    private void validateVersionAndCurrency(
            SaveAllocationRequest request,
            AllocationSource source,
            Optional<AllocationSnapshot> current
    ) {
        if (current.isEmpty()) {
            if (request.baseVersion() != 0L) {
                throw conflict(
                        "Um novo rateio deve começar com baseVersion igual a zero."
                );
            }
            return;
        }
        AllocationHeader header = current.orElseThrow().header();
        if (!"ATIVO".equals(header.status())) {
            throw conflict("O rateio não está ativo.");
        }
        if (header.version() != request.baseVersion()) {
            throw conflict(
                    "Versão do rateio desatualizada. Recarregue os dados."
            );
        }
        if (!header.currency().equals(source.currency())) {
            throw badRequest(
                    "A moeda da origem diverge da moeda do rateio existente."
            );
        }
    }

    private List<NormalizedItem> normalizeItems(
            List<AllocationItemRequest> rawItems,
            BigDecimal total
    ) {
        List<NormalizedItem> normalized = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(VALUE_SCALE);
        Set<String> identities = new LinkedHashSet<>();
        for (int index = 0; index < rawItems.size(); index++) {
            AllocationItemRequest raw = rawItems.get(index);
            if (raw == null || blank(raw.unidadeControleId())) {
                throw badRequest(
                        "Cada item deve informar unidadeControleId."
                );
            }
            BigDecimal value = exactValue(raw.valor(), "valor do item");
            if (value.signum() <= 0) {
                throw badRequest("O valor de cada item deve ser maior que zero.");
            }
            BigDecimal percentage = value.divide(
                    total,
                    PERCENTAGE_SCALE,
                    RoundingMode.HALF_UP
            );
            if (percentage.signum() <= 0) {
                throw badRequest(
                        "O item é menor que a precisão percentual suportada."
                );
            }
            String unitId = raw.unidadeControleId().trim();
            String costCenterId = normalizeOptional(
                    raw.centroCustoId(), 36, "centroCustoId"
            );
            String categoryId = normalizeOptional(
                    raw.categoriaId(), 36, "categoriaId"
            );
            String identity = unitId + "\u0000"
                    + String.valueOf(costCenterId) + "\u0000"
                    + String.valueOf(categoryId);
            if (!identities.add(identity)) {
                throw badRequest(
                        "Há itens duplicados para a mesma unidade e classificação."
                );
            }
            normalized.add(new NormalizedItem(
                    unitId,
                    costCenterId,
                    categoryId,
                    value,
                    percentage,
                    index
            ));
            sum = sum.add(value);
        }
        if (sum.compareTo(total) != 0) {
            throw badRequest(
                    "O rateio deve ser integral: a soma dos itens deve ser exatamente igual ao valor da origem."
            );
        }
        return List.copyOf(normalized);
    }

    private Map<String, AllocationUnit> validateUnits(
            List<NormalizedItem> items
    ) {
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(item -> ids.add(item.unitId()));
        Map<String, AllocationUnit> units = repository.lockUnits(ids);
        if (units.size() != ids.size()) {
            throw badRequest("Uma ou mais unidades financeiras não existem.");
        }
        for (String id : ids) {
            AllocationUnit unit = units.get(id);
            if (unit == null) {
                throw badRequest("Unidade financeira não encontrada: " + id);
            }
            if (!"ATIVA".equals(unit.status())) {
                throw badRequest("Toda unidade de destino deve estar ativa.");
            }
            if (unit.type() == FinancialUnitType.CORPORATIVO) {
                throw badRequest(
                        "A unidade corporativa é apenas de consulta e não pode receber rateio."
                );
            }
        }
        return units;
    }

    private void validateCatalogReferences(List<NormalizedItem> items) {
        Set<String> costCenters = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        items.forEach(item -> {
            if (item.costCenterId() != null) {
                costCenters.add(item.costCenterId());
            }
            if (item.categoryId() != null) {
                categories.add(item.categoryId());
            }
        });
        costCenters.forEach(id -> {
            if (!repository.activeCostCenterExists(id)) {
                throw badRequest(
                        "Centro de custo inexistente ou arquivado: " + id
                );
            }
        });
        categories.forEach(id -> {
            if (!repository.activeCategoryExists(id)) {
                throw badRequest(
                        "Categoria inexistente ou arquivada: " + id
                );
            }
        });
    }

    private List<AllocationItemWrite> itemWrites(
            List<NormalizedItem> items
    ) {
        return items.stream().map(item -> new AllocationItemWrite(
                UUID.randomUUID().toString(),
                item.unitId(),
                item.costCenterId(),
                item.categoryId(),
                item.value(),
                item.percentage(),
                item.order()
        )).toList();
    }

    private List<AllocationItemRecord> itemRecords(
            List<AllocationItemWrite> writes
    ) {
        return writes.stream().map(item -> new AllocationItemRecord(
                item.id(),
                item.unitId(),
                item.costCenterId(),
                item.categoryId(),
                item.value(),
                item.percentage(),
                item.order()
        )).toList();
    }

    private void requirePermissions(
            List<AllocationItemRecord> items,
            FinancialPermission permission
    ) {
        Set<String> unitIds = new LinkedHashSet<>();
        items.forEach(item -> unitIds.add(item.unitId()));
        unitIds.forEach(unitId -> access.requireUnitPermission(
                unitId,
                permission
        ));
    }

    private AllocationResponse response(AllocationSnapshot snapshot) {
        AllocationHeader header = snapshot.header();
        return new AllocationResponse(
                header.id(),
                header.sourceType(),
                header.sourceId(),
                header.currency(),
                header.total(),
                header.status(),
                snapshot.items().stream()
                        .map(item -> new AllocationItemResponse(
                                item.id(),
                                item.unitId(),
                                item.costCenterId(),
                                item.categoryId(),
                                item.value(),
                                item.percentage(),
                                item.order()
                        ))
                        .toList(),
                header.createdBy(),
                header.createdAt(),
                header.updatedBy(),
                header.updatedAt(),
                header.version()
        );
    }

    private AllocationHistoryEntry historyEntry(
            AllocationHistoryRecord record
    ) {
        return new AllocationHistoryEntry(
                record.id(),
                record.operation(),
                record.previousVersion(),
                record.newVersion(),
                parseJson(record.previousStateJson()),
                parseJson(record.newStateJson()),
                record.actorId(),
                record.at(),
                record.clientMutationId(),
                record.correlationId(),
                record.deviceId()
        );
    }

    private JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Histórico de rateio contém JSON inválido.",
                    exception
            );
        }
    }

    private String json(AllocationSnapshot snapshot) {
        return json(response(snapshot));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Não foi possível serializar o estado do rateio.",
                    exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state(AllocationResponse response) {
        if (response == null) {
            return Map.of();
        }
        return objectMapper.convertValue(response, LinkedHashMap.class);
    }

    private BigDecimal exactValue(BigDecimal value, String field) {
        if (value == null) {
            throw badRequest(field + " é obrigatório.");
        }
        try {
            return value.setScale(VALUE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw badRequest(field + " deve possuir no máximo 4 casas decimais.");
        }
    }

    private String normalizeOptional(
            String value,
            int maxLength,
            String field
    ) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " excede " + maxLength + " caracteres.");
        }
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record NormalizedItem(
            String unitId,
            String costCenterId,
            String categoryId,
            BigDecimal value,
            BigDecimal percentage,
            int order
    ) {
    }
}
