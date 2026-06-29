package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RdoAttributeKnowledgeSource implements StaviaKnowledgeSource {

    private static final List<String> SUPPORTED_ATTRIBUTES =
            List.of(
                    "turno",
                    "condicaoManha",
                    "condicaoTarde",
                    "condicaoNoite",
                    "pluviometriaMm"
            );

    private final RdoAttributeReader reader;

    public RdoAttributeKnowledgeSource(RdoAttributeReader reader) {
        this.reader = reader;
    }

    @Override
    public String sourceName() {
        return "cadastro-rdos";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-RDO-ATTRIBUTE-SOURCE-0.2.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.RDO),
                Set.of("RDO"),
                Set.copyOf(SUPPORTED_ATTRIBUTES),
                Set.of(),
                Set.of(QueryOperation.READ_ATTRIBUTE),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "RDO local, ordenado por status e data operacional",
                10,
                50
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null) {
            return false;
        }

        if (!request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)) {
            return false;
        }

        StaviaQueryPlan plan = request.plan();

        if (plan == null || !plan.planned()) {
            return false;
        }

        return plan.domain() == QueryDomain.RDO
                && plan.operation() == QueryOperation.READ_ATTRIBUTE
                && plan.requestedAttributes()
                        .stream()
                        .anyMatch(SUPPORTED_ATTRIBUTES::contains);
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        StaviaQueryPlan plan = request.plan();
        int limit = 50;

        List<RdoAttributeRecord> records =
                reader.findByWorksiteId(
                        request.worksiteId(),
                        plan.temporalFilter().startDate(),
                        plan.temporalFilter().endDate(),
                        limit
                );

        List<StaviaEvidence> evidences = records.stream()
                .flatMap(record ->
                        evidences(record, plan.requestedAttributes())
                                .stream()
                )
                .toList();

        if (!plan.requiresLatestValue()) {
            return evidences;
        }

        java.util.Set<String> seenAttributes =
                new java.util.LinkedHashSet<>();

        return evidences.stream()
                .filter(evidence -> {
                    String field =
                            String.valueOf(
                                    evidence.attributes().get("campo")
                            );
                    return seenAttributes.add(field);
                })
                .toList();
    }

    private List<StaviaEvidence> evidences(
            RdoAttributeRecord record,
            List<String> requestedAttributes
    ) {
        return requestedAttributes.stream()
                .filter(SUPPORTED_ATTRIBUTES::contains)
                .map(attribute ->
                        evidence(record, attribute)
                )
                .filter(evidence ->
                        evidence.attributes().containsKey("valor")
                )
                .toList();
    }

    private StaviaEvidence evidence(
            RdoAttributeRecord record,
            String attribute
    ) {
        Object value =
                switch (attribute) {
                    case "turno" -> record.turno();
                    case "condicaoManha" -> record.condicaoManha();
                    case "condicaoTarde" -> record.condicaoTarde();
                    case "condicaoNoite" -> record.condicaoNoite();
                    case "pluviometriaMm" -> record.pluviometriaMm();
                    default -> null;
                };

        Map<String, Object> attributes =
                baseAttributes(record, attribute);

        if (value != null
                && !String.valueOf(value).isBlank()) {
            attributes.put("valor", normalizeValue(value));
        }

        String unit =
                "pluviometriaMm".equals(attribute) ? "mm" : null;

        if (unit != null) {
            attributes.put("unidade", unit);
        }

        return new StaviaEvidence(
                StaviaEvidenceTypes.RDO_ATTRIBUTE,
                record.id() + ":" + attribute,
                summary(attribute, value),
                updatedAt(record),
                isValidated(record.status()),
                attributes
        );
    }

    private Map<String, Object> baseAttributes(
            RdoAttributeRecord record,
            String attribute
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        putText(attributes, "obraId", record.worksiteId());
        putText(attributes, "codigoObra", record.worksiteCode());
        putText(attributes, "rdoId", record.id());
        putText(attributes, "rdoNumero", record.numeroRdo());
        putText(attributes, "campo", attribute);
        putText(attributes, "rotulo", label(attribute));
        putDate(attributes, "dataRdo", record.dataRdo());
        putText(attributes, "statusRdo", record.status());
        putText(attributes, "fonteCriacao", record.fonteCriacao());
        attributes.put("sourceSystem", "CORTEX");
        attributes.put("fonte", sourceName());
        attributes.put("criterioMaisRecente", latestCriterion(record.status()));
        attributes.put("validada", isValidated(record.status()));

        return attributes;
    }

    private String summary(String attribute, Object value) {
        return label(attribute)
                + ": "
                + (value == null || String.valueOf(value).isBlank()
                        ? "Não informado"
                        : normalizeValue(value));
    }

    private String latestCriterion(String status) {
        if (isValidated(status)) {
            return "RDO validado mais recente";
        }

        if ("ENVIADO".equals(status)) {
            return "RDO enviado mais recente";
        }

        return "Rascunho mais recente";
    }

    private boolean isValidated(String status) {
        return status != null
                && List.of(
                        "VALIDADO",
                        "VALIDADA",
                        "APROVADO",
                        "APROVADA"
                ).contains(status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private String label(String attribute) {
        return switch (attribute) {
            case "turno" -> "Turno";
            case "condicaoManha" -> "Manhã";
            case "condicaoTarde" -> "Tarde";
            case "condicaoNoite" -> "Noite";
            case "pluviometriaMm" -> "Pluviometria";
            default -> attribute;
        };
    }

    private Object normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }

        return String.valueOf(value).trim();
    }

    private java.time.Instant updatedAt(RdoAttributeRecord record) {
        LocalDateTime value =
                firstNonNull(
                        record.aprovadoEm(),
                        record.enviadoEm(),
                        record.atualizadoEm(),
                        record.criadoEm()
                );

        return value == null
                ? null
                : value.toInstant(ZoneOffset.UTC);
    }

    private LocalDateTime firstNonNull(LocalDateTime... values) {
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value.trim());
        }
    }

    private void putDate(
            Map<String, Object> attributes,
            String key,
            LocalDate value
    ) {
        if (value != null) {
            attributes.put(key, value.toString());
        }
    }
}
