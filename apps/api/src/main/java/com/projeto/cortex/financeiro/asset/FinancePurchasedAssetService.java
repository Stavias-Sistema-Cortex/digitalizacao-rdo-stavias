package com.projeto.cortex.financeiro.asset;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.AssetRecord;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.NewAssetWrite;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedAssetMutation;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedItem;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedLinkHistoryRecord;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedLinkHistoryWrite;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedLinkRecord;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedLinkWrite;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinanceOntologyRelation;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import java.math.BigDecimal;
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
public class FinancePurchasedAssetService {

    private final FinancePurchasedAssetRepository repository;
    private final FinancialUnitService units;
    private final FinancialAccessService access;
    private final FinanceOntologyProjector ontology;
    private final ObjectMapper objectMapper;

    public FinancePurchasedAssetService(
            FinancePurchasedAssetRepository repository,
            FinancialUnitService units,
            FinancialAccessService access,
            FinanceOntologyProjector ontology,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.units = units;
        this.access = access;
        this.ontology = ontology;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PurchasedAssetResponse confirm(
            String purchaseId,
            String itemId,
            ConfirmPurchasedAssetsRequest rawRequest,
            FinanceAuditContext rawAudit
    ) {
        String normalizedPurchaseId = requiredId(purchaseId, "compraId");
        String normalizedItemId = requiredId(itemId, "itemId");
        ConfirmPurchasedAssetsRequest request = validateRequest(rawRequest);
        FinanceAuditContext audit = validateAudit(rawAudit, request);

        Optional<PurchasedAssetResponse> repeated = repeatedMutation(
                normalizedPurchaseId,
                normalizedItemId,
                request.clientMutationId(),
                audit.actorId(),
                false
        );
        if (repeated.isPresent()) {
            return repeated.orElseThrow();
        }
        try {
            repository.claimMutation(
                    audit.actorId(),
                    request.clientMutationId(),
                    normalizedItemId
            );
        } catch (DuplicateKeyException exception) {
            return repeatedMutation(
                    normalizedPurchaseId,
                    normalizedItemId,
                    request.clientMutationId(),
                    audit.actorId(),
                    true
            ).orElseThrow(() -> conflict(
                    "A confirmação concorrente não pôde ser recuperada."
            ));
        }

        PurchasedItem item = repository.lockItem(
                normalizedPurchaseId,
                normalizedItemId
        ).orElseThrow(() -> notFound("Item de compra não encontrado."));
        access.requirePermission(
                item.worksiteId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        validateItem(item, request);
        if (!repository.findLinks(item.itemId()).isEmpty()) {
            throw conflict(
                    "Os ativos deste item já foram confirmados."
            );
        }

        List<ResolvedTarget> resolved = resolveTargets(
                item,
                request.ativos(),
                audit
        );
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<PurchasedLinkRecord> fallback = new ArrayList<>();
        for (ResolvedTarget target : resolved) {
            String linkId = UUID.randomUUID().toString();
            PurchasedLinkWrite link = new PurchasedLinkWrite(
                    linkId,
                    item.itemId(),
                    target.asset().id(),
                    target.unit().id(),
                    target.sequence(),
                    audit.actorId()
            );
            repository.insertLink(link);
            PurchasedAssetLinkResponse state = new PurchasedAssetLinkResponse(
                    linkId,
                    target.asset().id(),
                    target.unit().id(),
                    target.sequence(),
                    target.asset().externalCode(),
                    target.asset().name(),
                    target.asset().category(),
                    audit.actorId(),
                    now
            );
            repository.insertHistory(new PurchasedLinkHistoryWrite(
                    UUID.randomUUID().toString(),
                    linkId,
                    request.clientMutationId(),
                    null,
                    json(state),
                    audit.actorId(),
                    audit.correlationId(),
                    audit.deviceId()
            ));
            fallback.add(new PurchasedLinkRecord(
                    linkId,
                    target.asset().id(),
                    target.unit().id(),
                    target.sequence(),
                    target.asset().externalCode(),
                    target.asset().name(),
                    target.asset().category(),
                    audit.actorId(),
                    now
            ));
        }
        if (!repository.incrementItemVersion(
                item.itemId(),
                item.version(),
                audit.actorId()
        )) {
            throw conflict(
                    "O item foi alterado por outra operação. Recarregue os dados."
            );
        }
        repository.completeMutation(
                audit.actorId(),
                request.clientMutationId(),
                item.itemId()
        );

        List<PurchasedLinkRecord> canonical = repository.findLinksCurrent(
                item.itemId()
        );
        if (canonical.size() != fallback.size()) {
            canonical = fallback;
        }
        PurchasedAssetResponse response = response(
                item,
                item.version() + 1,
                canonical
        );
        ontology.successWithRelations(
                "COMPRA_ITEM",
                item.itemId(),
                "Item capitalizável " + item.itemId(),
                "ATIVOS_COMPRADOS_CONFIRMADOS",
                item.worksiteId(),
                audit,
                Map.of(),
                state(response),
                resolved.stream().map(target -> new FinanceOntologyRelation(
                        "ATIVO",
                        target.asset().id(),
                        "ORIGINOU_ATIVO",
                        "Ativo individual confirmado explicitamente na compra."
                )).toList()
        );
        return response;
    }

    @Transactional(readOnly = true)
    public PurchasedAssetResponse find(String purchaseId, String itemId) {
        PurchasedItem item = repository.findItem(
                requiredId(purchaseId, "compraId"),
                requiredId(itemId, "itemId")
        ).orElseThrow(() -> notFound("Item de compra não encontrado."));
        access.requirePermission(
                item.worksiteId(),
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return response(item, item.version(), repository.findLinks(item.itemId()));
    }

    @Transactional(readOnly = true)
    public PurchasedAssetHistoryResponse history(
            String purchaseId,
            String itemId
    ) {
        PurchasedItem item = repository.findItem(
                requiredId(purchaseId, "compraId"),
                requiredId(itemId, "itemId")
        ).orElseThrow(() -> notFound("Item de compra não encontrado."));
        access.requirePermission(
                item.worksiteId(),
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        List<PurchasedAssetHistoryEntry> entries = repository.history(
                item.itemId()
        ).stream().map(this::historyEntry).toList();
        return new PurchasedAssetHistoryResponse(
                item.purchaseId(),
                item.itemId(),
                entries
        );
    }

    private Optional<PurchasedAssetResponse> repeatedMutation(
            String purchaseId,
            String itemId,
            String clientMutationId,
            String actorId,
            boolean currentRead
    ) {
        Optional<PurchasedAssetMutation> found = currentRead
                ? repository.findMutationCurrent(actorId, clientMutationId)
                : repository.findMutation(actorId, clientMutationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PurchasedAssetMutation mutation = found.orElseThrow();
        if (!itemId.equals(mutation.itemId())) {
            throw conflict(
                    "clientMutationId já foi usado em outro item de compra."
            );
        }
        if (!mutation.completed()) {
            throw conflict("A confirmação idempotente ainda está em andamento.");
        }
        PurchasedItem item = currentRead
                ? repository.lockItem(purchaseId, itemId).orElseThrow(() ->
                        notFound("Item de compra não encontrado."))
                : repository.findItem(purchaseId, itemId).orElseThrow(() ->
                        notFound("Item de compra não encontrado."));
        access.requirePermission(
                item.worksiteId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        List<PurchasedLinkRecord> links = currentRead
                ? repository.findLinksCurrent(itemId)
                : repository.findLinks(itemId);
        return Optional.of(response(item, item.version(), links));
    }

    private List<ResolvedTarget> resolveTargets(
            PurchasedItem item,
            List<PurchasedAssetTarget> targets,
            FinanceAuditContext audit
    ) {
        Set<String> assetIds = new LinkedHashSet<>();
        Set<String> newCodes = new LinkedHashSet<>();
        List<PreparedTarget> prepared = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            PurchasedAssetTarget target = targets.get(index);
            if (target == null) {
                throw badRequest("Cada alvo de ativo é obrigatório.");
            }
            boolean existing = !blank(target.ativoExistenteId());
            boolean created = target.novoAtivo() != null;
            if (existing == created) {
                throw badRequest(
                        "Cada alvo deve informar exatamente um ativo existente ou um novo ativo."
                );
            }
            if (existing) {
                String assetId = target.ativoExistenteId().trim();
                if (!assetIds.add(assetId)) {
                    throw badRequest("Ativo duplicado na confirmação: " + assetId);
                }
                AssetRecord asset = repository.findActiveAsset(assetId)
                        .orElseThrow(() -> badRequest(
                                "O ativo existente deve estar ativo: " + assetId
                        ));
                prepared.add(new PreparedTarget(index + 1, asset, null));
                continue;
            }
            NewAssetRequest input = target.novoAtivo();
            String name = requiredText(input.nome(), "novoAtivo.nome", 255);
            String code = requiredText(
                    input.codigoExterno(),
                    "novoAtivo.codigoExterno",
                    120
            );
            String category = optionalText(
                    input.categoria(),
                    "novoAtivo.categoria",
                    120
            );
            if (!newCodes.add(code)) {
                throw badRequest("Código de ativo duplicado na confirmação: " + code);
            }
            if (repository.findActiveAssetByExternalCode(code).isPresent()) {
                throw badRequest(
                        "Já existe ativo com esse código; selecione-o explicitamente."
                );
            }
            String id = UUID.randomUUID().toString();
            AssetRecord asset = new AssetRecord(id, code, name, category);
            prepared.add(new PreparedTarget(
                    index + 1,
                    asset,
                    new NewAssetWrite(
                            id,
                            item.itemId() + ":" + (index + 1),
                            code,
                            name,
                            category
                    )
            ));
        }

        List<ResolvedTarget> resolved = new ArrayList<>();
        for (PreparedTarget target : prepared) {
            if (target.newAsset() != null) {
                repository.insertAsset(target.newAsset());
            }
            FinancialUnitResponse unit = units.ensureAssetUnit(
                    target.asset().id(),
                    audit
            );
            resolved.add(new ResolvedTarget(
                    target.sequence(),
                    target.asset(),
                    unit
            ));
        }
        return List.copyOf(resolved);
    }

    private void validateItem(
            PurchasedItem item,
            ConfirmPurchasedAssetsRequest request
    ) {
        if (item.archived()) {
            throw badRequest("O item de compra está arquivado.");
        }
        if (!"CAPITALIZAVEL".equals(item.nature())) {
            throw badRequest("Somente item capitalizável pode originar ativos.");
        }
        int quantity;
        try {
            quantity = item.quantity().stripTrailingZeros().intValueExact();
        } catch (ArithmeticException exception) {
            throw badRequest(
                    "A quantidade capitalizável deve ser inteira e positiva."
            );
        }
        if (quantity <= 0) {
            throw badRequest(
                    "A quantidade capitalizável deve ser inteira e positiva."
            );
        }
        if (request.ativos().size() != quantity) {
            throw badRequest(
                    "A quantidade de ativos deve ser igual à quantidade comprada."
            );
        }
        if (item.version() != request.baseVersion()) {
            throw conflict(
                    "Versão do item desatualizada. Recarregue os dados."
            );
        }
    }

    private ConfirmPurchasedAssetsRequest validateRequest(
            ConfirmPurchasedAssetsRequest request
    ) {
        if (request == null) {
            throw badRequest("A confirmação dos ativos é obrigatória.");
        }
        String mutation = requiredText(
                request.clientMutationId(),
                "clientMutationId",
                120
        );
        if (request.baseVersion() == null || request.baseVersion() < 0) {
            throw badRequest("baseVersion é obrigatório e não pode ser negativo.");
        }
        if (request.ativos() == null || request.ativos().isEmpty()) {
            throw badRequest("Informe ao menos um ativo.");
        }
        if (request.ativos().size() > 500) {
            throw badRequest("A confirmação excede o limite de 500 ativos.");
        }
        return new ConfirmPurchasedAssetsRequest(
                mutation,
                request.baseVersion(),
                List.copyOf(request.ativos()),
                optionalText(request.correlacaoId(), "correlacaoId", 120),
                optionalText(request.dispositivoId(), "dispositivoId", 120)
        );
    }

    private FinanceAuditContext validateAudit(
            FinanceAuditContext audit,
            ConfirmPurchasedAssetsRequest request
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

    private PurchasedAssetResponse response(
            PurchasedItem item,
            long version,
            List<PurchasedLinkRecord> links
    ) {
        return new PurchasedAssetResponse(
                item.purchaseId(),
                item.itemId(),
                item.nature(),
                item.quantity(),
                version,
                links.stream().map(link -> new PurchasedAssetLinkResponse(
                        link.id(),
                        link.assetId(),
                        link.unitId(),
                        link.sequence(),
                        link.externalCode(),
                        link.name(),
                        link.category(),
                        link.createdBy(),
                        link.createdAt()
                )).toList()
        );
    }

    private PurchasedAssetHistoryEntry historyEntry(
            PurchasedLinkHistoryRecord record
    ) {
        return new PurchasedAssetHistoryEntry(
                record.id(),
                record.linkId(),
                record.clientMutationId(),
                record.operation(),
                parseJson(record.previousStateJson()),
                parseJson(record.newStateJson()),
                record.actorId(),
                record.at(),
                record.correlationId(),
                record.deviceId()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Não foi possível serializar o vínculo do ativo.",
                    exception
            );
        }
    }

    private JsonNode parseJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Histórico de ativo contém JSON inválido.",
                    exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state(PurchasedAssetResponse response) {
        return objectMapper.convertValue(response, LinkedHashMap.class);
    }

    private String requiredId(String value, String field) {
        return requiredText(value, field, 36);
    }

    private String requiredText(String value, String field, int max) {
        if (blank(value)) {
            throw badRequest(field + " é obrigatório.");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw badRequest(field + " excede " + max + " caracteres.");
        }
        return normalized;
    }

    private String optionalText(String value, String field, int max) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw badRequest(field + " excede " + max + " caracteres.");
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

    private record PreparedTarget(
            int sequence,
            AssetRecord asset,
            NewAssetWrite newAsset
    ) {
    }

    private record ResolvedTarget(
            int sequence,
            AssetRecord asset,
            FinancialUnitResponse unit
    ) {
    }
}
