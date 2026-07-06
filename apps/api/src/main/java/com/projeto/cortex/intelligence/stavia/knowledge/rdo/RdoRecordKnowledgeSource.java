package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic knowledge source for the RDO child tables. The ontology decides
 * which table and columns a plan refers to; this source reads matching rows
 * (or SQL-computed aggregations) and emits one evidence per attribute value
 * in the same shape the deterministic generator already understands.
 */
@Component
public class RdoRecordKnowledgeSource implements StaviaKnowledgeSource {

    public static final String SOURCE_NAME = "registros-rdo";

    private static final Pattern SELECTED_RDO_CONTEXT =
            Pattern.compile("(?iu)\\brdoId\\s*=\\s*([^\\s]+)");

    private static final int DEFAULT_LIMIT = 50;
    private static final int FALLBACK_LIMIT = 5;

    private final RdoOntology ontology;
    private final RdoEntityRecordReader reader;

    public RdoRecordKnowledgeSource(
            RdoOntology ontology,
            RdoEntityRecordReader reader
    ) {
        this.ontology = ontology;
        this.reader = reader;
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-RDO-RECORDS-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        Set<String> attributes = new LinkedHashSet<>();

        for (RdoOntologyEntity entity : ontology.entities()) {
            for (RdoOntologyAttribute attribute : entity.attributes()) {
                attributes.add(
                        entity.name() + "." + attribute.name()
                );
            }
        }

        return new StaviaSourceDescriptor(
                SOURCE_NAME,
                sourceVersion(),
                Set.of(QueryDomain.RDO),
                Set.of(),
                attributes,
                Set.of(),
                Set.of(
                        QueryOperation.READ_ATTRIBUTE,
                        QueryOperation.LIST_OBJECTS,
                        QueryOperation.AGGREGATE,
                        QueryOperation.COMPARE
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Tempo real",
                10,
                200
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null
                || !request.permissions().contains(
                        StaviaEngine.REQUIRED_PERMISSION
                )) {
            return false;
        }

        StaviaQueryPlan plan = request.plan();

        if (plan == null
                || !plan.planned()
                || plan.domain() != QueryDomain.RDO) {
            return false;
        }

        if (plan.requiredSources().contains(SOURCE_NAME)) {
            return true;
        }

        return qualifiedAttributes(plan).stream()
                .anyMatch(attribute ->
                        resolve(attribute).isPresent()
                );
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        StaviaQueryPlan plan = request.plan();

        if (plan == null || !plan.planned()) {
            return List.of();
        }

        if (!plan.aggregations().isEmpty()) {
            return retrieveAggregations(request, plan);
        }

        return retrieveRecords(request, plan);
    }

    private List<StaviaEvidence> retrieveRecords(
            StaviaKnowledgeRequest request,
            StaviaQueryPlan plan
    ) {
        Map<RdoOntologyEntity, List<RdoOntologyAttribute>> requested =
                new LinkedHashMap<>();

        for (String qualified : qualifiedAttributes(plan)) {
            resolve(qualified).ifPresent(resolved ->
                    requested.computeIfAbsent(
                            resolved.entity(),
                            key -> new ArrayList<>()
                    ).add(resolved.attribute())
            );
        }

        List<StaviaEvidence> evidences = new ArrayList<>();

        for (Map.Entry<RdoOntologyEntity, List<RdoOntologyAttribute>> entry
                : requested.entrySet()) {
            RdoOntologyEntity entity = entry.getKey();
            Integer ordinal = ordinal(plan, entity);
            String identity = ordinal == null
                    ? identityTerm(plan, entity)
                    : null;

            RdoRecordQuery query = new RdoRecordQuery(
                    request.worksiteId(),
                    plan.temporalFilter().startDate(),
                    plan.temporalFilter().endDate(),
                    selectedRdoId(request),
                    identity,
                    DEFAULT_LIMIT
            );

            List<Map<String, Object>> records =
                    reader.findRecords(entity, query);

            if (ordinal != null) {
                records = recordAtOrdinal(records, ordinal);
            }

            for (Map<String, Object> record : records) {
                evidences.addAll(
                        recordEvidences(
                                entity,
                                entry.getValue(),
                                record,
                                ordinal
                        )
                );
            }

            if (evidences.isEmpty()
                    && (identity != null || ordinal != null)) {
                evidences.addAll(
                        availableItemsEvidences(request, entity, query)
                );
            }
        }

        return evidences;
    }

    private List<StaviaEvidence> retrieveAggregations(
            StaviaKnowledgeRequest request,
            StaviaQueryPlan plan
    ) {
        List<StaviaEvidence> evidences = new ArrayList<>();

        for (AggregationSpec spec : plan.aggregations()) {
            Optional<ResolvedAttribute> resolved =
                    resolve(spec.attribute());

            if (resolved.isEmpty()) {
                continue;
            }

            RdoOntologyEntity entity = resolved.get().entity();
            RdoOntologyAttribute attribute = resolved.get().attribute();

            RdoRecordQuery query = new RdoRecordQuery(
                    request.worksiteId(),
                    plan.temporalFilter().startDate(),
                    plan.temporalFilter().endDate(),
                    selectedRdoId(request),
                    identityTerm(plan, entity),
                    DEFAULT_LIMIT
            );

            List<Map<String, Object>> rows =
                    reader.aggregate(entity, attribute, spec, query);

            int position = 1;

            for (Map<String, Object> row : rows) {
                evidences.add(
                        aggregationEvidence(
                                plan,
                                spec,
                                entity,
                                attribute,
                                row,
                                rows.size() > 1 ? position : 0
                        )
                );
                position++;
            }
        }

        return evidences;
    }

    private List<StaviaEvidence> recordEvidences(
            RdoOntologyEntity entity,
            List<RdoOntologyAttribute> attributes,
            Map<String, Object> record,
            Integer ordinal
    ) {
        List<StaviaEvidence> evidences = new ArrayList<>();
        String itemLabel = itemLabel(entity, record, ordinal);

        for (RdoOntologyAttribute attribute : attributes) {
            String value = text(record, attribute.name());

            if (value == null) {
                continue;
            }

            Map<String, Object> evidenceAttributes =
                    new LinkedHashMap<>();

            evidenceAttributes.put(
                    "campo",
                    entity.name() + "." + attribute.name()
            );
            evidenceAttributes.put("rotulo", attribute.label());
            evidenceAttributes.put("valor", value);

            String unit = unit(attribute, record);
            if (unit != null) {
                evidenceAttributes.put("unidade", unit);
            }

            if (itemLabel != null) {
                evidenceAttributes.put("itemRotulo", itemLabel);
            }

            putContext(evidenceAttributes, record);

            evidences.add(
                    new StaviaEvidence(
                            entity.evidenceType(),
                            text(record, "registroId")
                                    + ":" + attribute.name(),
                            summary(
                                    attribute.label(),
                                    itemLabel,
                                    value,
                                    unit,
                                    record
                            ),
                            Instant.now(),
                            true,
                            evidenceAttributes
                    )
            );
        }

        return evidences;
    }

    private List<StaviaEvidence> availableItemsEvidences(
            StaviaKnowledgeRequest request,
            RdoOntologyEntity entity,
            RdoRecordQuery original
    ) {
        List<RdoOntologyAttribute> identityAttributes =
                entity.identityAttributes();

        if (identityAttributes.isEmpty()) {
            return List.of();
        }

        RdoRecordQuery fallback = new RdoRecordQuery(
                original.worksiteId(),
                original.startDate(),
                original.endDate(),
                original.rdoId(),
                null,
                FALLBACK_LIMIT
        );

        List<StaviaEvidence> evidences = new ArrayList<>();

        for (Map<String, Object> record
                : reader.findRecords(entity, fallback)) {
            String itemLabel = itemLabel(entity, record, null);

            if (itemLabel == null) {
                continue;
            }

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("campo", entity.name() + ".disponiveis");
            attributes.put(
                    "rotulo",
                    identityAttributes.getFirst().label()
            );
            attributes.put("itemRotulo", itemLabel);
            putContext(attributes, record);

            evidences.add(
                    new StaviaEvidence(
                            entity.evidenceType(),
                            text(record, "registroId") + ":disponivel",
                            "Registrado: " + itemLabel,
                            Instant.now(),
                            true,
                            attributes
                    )
            );
        }

        return evidences;
    }

    private StaviaEvidence aggregationEvidence(
            StaviaQueryPlan plan,
            AggregationSpec spec,
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute,
            Map<String, Object> row,
            int position
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();

        attributes.put(
                "funcao",
                spec.function() == null
                        ? "SUM"
                        : spec.function().toUpperCase(Locale.ROOT)
        );
        attributes.put("campo", spec.attribute());
        attributes.put("rotulo", attribute.label());
        putText(attributes, "valor", text(row, "valor"));
        putText(attributes, "linhas", text(row, "linhas"));

        if (attribute.unit() != null) {
            attributes.put("unidade", attribute.unit());
        }

        if (plan.temporalFilter().startDate() != null) {
            attributes.put(
                    "periodoInicio",
                    plan.temporalFilter().startDate().toString()
            );
        }

        if (plan.temporalFilter().endDate() != null) {
            attributes.put(
                    "periodoFim",
                    plan.temporalFilter().endDate().toString()
            );
        }

        putText(
                attributes,
                "criterio",
                plan.temporalFilter().relativeDate()
        );

        putText(attributes, "rdoId", text(row, "rdoId"));
        putText(attributes, "rdoNumero", text(row, "numeroRdo"));
        putText(attributes, "dataRdo", text(row, "dataRdo"));

        if (position > 0) {
            attributes.put("posicao", String.valueOf(position));
        }

        String id = entity.name()
                + ":" + attributes.get("funcao")
                + ":" + spec.attribute()
                + (text(row, "rdoId") == null
                        ? ""
                        : ":" + text(row, "rdoId"));

        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_AGREGACAO,
                id,
                "Agregação " + attributes.get("funcao")
                        + " de " + attribute.label()
                        + ": " + text(row, "valor"),
                Instant.now(),
                true,
                attributes
        );
    }

    private String summary(
            String label,
            String itemLabel,
            String value,
            String unit,
            Map<String, Object> record
    ) {
        StringBuilder summary = new StringBuilder(label);

        if (itemLabel != null) {
            summary.append(" de ").append(itemLabel);
        }

        summary.append(": ").append(value);

        if (unit != null) {
            summary.append(" ").append(unit);
        }

        String rdoNumber = text(record, "numeroRdo");
        String rdoDate = text(record, "dataRdo");

        if (rdoNumber != null) {
            summary.append(" (RDO ").append(rdoNumber);

            if (rdoDate != null) {
                summary.append(" de ").append(rdoDate);
            }

            summary.append(")");
        }

        return summary.toString();
    }

    private void putContext(
            Map<String, Object> attributes,
            Map<String, Object> record
    ) {
        putText(attributes, "rdoId", text(record, "rdoId"));
        putText(attributes, "rdoNumero", text(record, "numeroRdo"));
        putText(attributes, "dataRdo", text(record, "dataRdo"));
        putText(attributes, "statusRdo", text(record, "statusRdo"));
        putText(attributes, "obraId", text(record, "obraId"));
    }

    private String itemLabel(
            RdoOntologyEntity entity,
            Map<String, Object> record,
            Integer ordinal
    ) {
        String ordinalLabel = ordinalLabel(entity, ordinal);

        for (RdoOntologyAttribute attribute
                : entity.identityAttributes()) {
            String value = text(record, attribute.name());

            if (value != null) {
                if ("rdo".equals(entity.name())
                        && "numeroRdo".equals(attribute.name())) {
                    return "RDO " + value;
                }

                if (ordinalLabel != null) {
                    return ordinalLabel + " (" + value + ")";
                }

                return value;
            }
        }

        return ordinalLabel;
    }

    private String ordinalLabel(
            RdoOntologyEntity entity,
            Integer ordinal
    ) {
        if (ordinal == null || ordinal < 1) {
            return null;
        }

        return switch (entity.name()) {
            case "controleGeometrico" -> "Trecho " + ordinal;
            case "attachment" -> "Foto " + ordinal;
            case "alocacaoColaborador" -> "Alocação " + ordinal;
            case "operationalEvent" -> "Evento " + ordinal;
            default -> null;
        };
    }

    private String unit(
            RdoOntologyAttribute attribute,
            Map<String, Object> record
    ) {
        if (attribute.unit() != null) {
            return attribute.unit();
        }

        if (attribute.unitColumn() != null) {
            return text(record, attribute.unitColumn());
        }

        return null;
    }

    private List<String> qualifiedAttributes(StaviaQueryPlan plan) {
        List<String> qualified = new ArrayList<>();

        for (String attribute : plan.requestedAttributes()) {
            if (attribute.contains(".")) {
                qualified.add(attribute);
            }
        }

        for (AggregationSpec spec : plan.aggregations()) {
            if (spec.attribute() != null
                    && spec.attribute().contains(".")) {
                qualified.add(spec.attribute());
            }
        }

        return qualified;
    }

    private record ResolvedAttribute(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute
    ) {
    }

    private Optional<ResolvedAttribute> resolve(String qualified) {
        if (qualified == null || !qualified.contains(".")) {
            return Optional.empty();
        }

        String entityName =
                qualified.substring(0, qualified.indexOf('.'));
        String attributeName =
                qualified.substring(qualified.indexOf('.') + 1);

        return ontology.entityByName(entityName)
                .flatMap(entity ->
                        entity.attributeByName(attributeName)
                                .map(attribute ->
                                        new ResolvedAttribute(
                                                entity,
                                                attribute
                                        )
                                )
                );
    }

    private String identityTerm(
            StaviaQueryPlan plan,
            RdoOntologyEntity entity
    ) {
        String expectedType =
                entity.name().toUpperCase(Locale.ROOT);

        return plan.entities().stream()
                .filter(resolved ->
                        expectedType.equals(resolved.type())
                                && "NOME".equals(resolved.resolvedBy())
                                && resolved.value() != null
                )
                .map(ResolvedEntity::value)
                .findFirst()
                .orElse(null);
    }

    private Integer ordinal(
            StaviaQueryPlan plan,
            RdoOntologyEntity entity
    ) {
        String expectedType =
                entity.name().toUpperCase(Locale.ROOT);

        return plan.entities().stream()
                .filter(resolved ->
                        expectedType.equals(resolved.type())
                                && "ORDINAL".equals(resolved.resolvedBy())
                                && resolved.value() != null
                )
                .map(ResolvedEntity::value)
                .map(this::parsePositiveInteger)
                .filter(value -> value != null && value > 0)
                .findFirst()
                .orElse(null);
    }

    private Integer parsePositiveInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> recordAtOrdinal(
            List<Map<String, Object>> records,
            int ordinal
    ) {
        if (ordinal < 1 || ordinal > records.size()) {
            return List.of();
        }

        return List.of(records.get(ordinal - 1));
    }

    private String selectedRdoId(StaviaKnowledgeRequest request) {
        if (request.question() == null
                || request.question().text() == null) {
            return null;
        }

        Matcher matcher = SELECTED_RDO_CONTEXT.matcher(
                request.question().text()
        );

        if (!matcher.find()) {
            return null;
        }

        String value = matcher.group(1);

        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private String text(Map<String, Object> record, String key) {
        Object value = record.get(key);

        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }

        return String.valueOf(value).trim();
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }
}
