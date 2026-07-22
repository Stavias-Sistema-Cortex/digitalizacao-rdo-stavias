package com.projeto.cortex.ontology.graph;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explicit data-minimisation boundary for the queryable operational graph.
 * The canonical ledger retains the authoritative payload; the graph receives
 * only bounded structural values required by its projections.
 */
final class OperationalGraphPayloadPolicy {

    private static final int MAX_TEXT_LENGTH = 512;
    private static final Set<String> BASE_KEYS = Set.of(
            "schemaVersion",
            "status",
            "description",
            "worksiteId",
            "obraId",
            "rdoId"
    );
    private static final Set<String> RDO_KEYS = Set.of(
            "number",
            "numeroRdo",
            "rdoNumber",
            "previousRdoId",
            "programacaoId",
            "role",
            "papel",
            "tipoVinculo"
    );
    private static final Set<String> WORKSITE_KEYS = Set.of(
            "name",
            "worksiteName",
            "obraName"
    );
    private static final Set<String> ASSET_KEYS = Set.of(
            "assetId",
            "assetName"
    );
    private static final Set<String> SERVICE_KEYS = Set.of(
            "serviceId",
            "serviceName",
            "servicoNome",
            "nomeServico"
    );
    private static final Set<String> PRICE_KEYS = Set.of(
            "priceVersionId",
            "unitPrice",
            "unit"
    );
    private static final Set<String> REVENUE_KEYS = Set.of(
            "acceptedQuantity",
            "quantity",
            "unitPrice",
            "revenue",
            "unit"
    );
    private static final Set<String> BUSINESS_LABEL_KEYS = Set.of(
            "description",
            "name",
            "worksiteName",
            "obraName",
            "assetName",
            "serviceName",
            "servicoNome",
            "nomeServico"
    );
    private static final Set<String> PROTECTED_ENTITY_TYPES = Set.of(
            "ACTOR",
            "COLABORADOR",
            "COLLABORATOR",
            "PERSON",
            "PESSOA",
            "USER",
            "USUARIO",
            "MENSAGEM",
            "MESSAGE",
            "CONVERSA",
            "CONVERSATION"
    );

    private OperationalGraphPayloadPolicy() {
    }

    static Map<String, Object> project(
            String eventType,
            String entityType,
            Map<String, Object> canonicalPayload
    ) {
        if (canonicalPayload == null || canonicalPayload.isEmpty()) {
            return Map.of();
        }
        boolean protectedEntity = PROTECTED_ENTITY_TYPES.contains(normalize(entityType));
        Set<String> allowedKeys = allowedKeys(eventType, entityType);
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : allowedKeys.stream().sorted().toList()) {
            if (!canonicalPayload.containsKey(key)
                    || (protectedEntity && BUSINESS_LABEL_KEYS.contains(key))) {
                continue;
            }
            Object value = safeScalar(canonicalPayload.get(key));
            if (value != UnsupportedValue.INSTANCE) {
                safe.put(key, value);
            }
        }
        return safe;
    }

    private static Set<String> allowedKeys(String eventType, String entityType) {
        java.util.HashSet<String> keys = new java.util.HashSet<>(BASE_KEYS);
        String event = normalize(eventType);
        String entity = normalize(entityType);
        if (event.startsWith("RDO_") || "RDO".equals(entity)) {
            keys.addAll(RDO_KEYS);
        }
        if (event.contains("WORKSITE") || event.startsWith("OBRA_")
                || Set.of("WORKSITE", "OBRA").contains(entity)) {
            keys.addAll(WORKSITE_KEYS);
        }
        if (event.contains("ASSET") || event.contains("EQUIPMENT")
                || Set.of("ASSET", "ATIVO", "EQUIPAMENTO").contains(entity)) {
            keys.addAll(ASSET_KEYS);
        }
        if (event.contains("SERVICE") || event.contains("SERVICO")
                || Set.of("SERVICE", "SERVICO", "ITEM_CONTRATUAL").contains(entity)) {
            keys.addAll(SERVICE_KEYS);
        }
        if (event.contains("PRICE") || event.contains("PRECO")
                || Set.of("SERVICE_PRICE_VERSION", "PRECO_SERVICO").contains(entity)) {
            keys.addAll(PRICE_KEYS);
        }
        if ("RDO_SERVICE_EXECUTED".equals(event)
                || "SERVICO_RDO_EXECUTADO".equals(event)) {
            keys.addAll(REVENUE_KEYS);
            keys.addAll(PRICE_KEYS);
            keys.addAll(SERVICE_KEYS);
        }
        return Set.copyOf(keys);
    }

    private static Object safeScalar(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return text.length() <= MAX_TEXT_LENGTH
                    ? text
                    : text.substring(0, MAX_TEXT_LENGTH);
        }
        return UnsupportedValue.INSTANCE;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private enum UnsupportedValue {
        INSTANCE
    }
}
