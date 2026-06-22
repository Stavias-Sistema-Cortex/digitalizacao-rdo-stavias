package com.projeto.cortex.intelligence.stavia.policy;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StaviaContradictionPolicy {

    private static final Set<String> CRITICAL_FIELDS =
            Set.of(
                    "status",
                    "estado",
                    "obraid",
                    "programacaoid"
            );

    private static final Set<String> NON_COMPARABLE_FIELDS =
            Set.of(
                    "eventtype",
                    "commitsequence",
                    "relationtype",
                    "source",
                    "payload",
                    "observations",
                    "updatedat",
                    "createdat"
            );

    public StaviaContradictionAssessment assess(
            List<StaviaEvidence> evidences
    ) {
        if (evidences == null || evidences.size() < 2) {
            return StaviaContradictionAssessment.none();
        }

        Map<String, Map<String, Set<String>>> valuesByScope =
                collectComparableValues(evidences);

        Set<String> conflictingFields =
                new LinkedHashSet<>();

        boolean critical = false;

        for (Map<String, Set<String>> valuesByField
                : valuesByScope.values()) {

            for (Map.Entry<String, Set<String>> entry
                    : valuesByField.entrySet()) {

                if (entry.getValue().size() <= 1) {
                    continue;
                }

                conflictingFields.add(entry.getKey());

                if (
                        CRITICAL_FIELDS.contains(
                                entry.getKey()
                                        .toLowerCase(Locale.ROOT)
                        )
                ) {
                    critical = true;
                }
            }
        }

        if (conflictingFields.isEmpty()) {
            return StaviaContradictionAssessment.none();
        }

        List<String> warnings =
                new ArrayList<>();

        warnings.add(
                "Foram encontradas informações contraditórias "
                        + "sobre o mesmo objeto nos campos: "
                        + String.join(", ", conflictingFields)
                        + "."
        );

        if (critical) {
            warnings.add(
                    "A contradição afeta campos críticos e "
                            + "impede uma conclusão segura."
            );
        }

        return new StaviaContradictionAssessment(
                true,
                critical,
                List.copyOf(conflictingFields),
                warnings
        );
    }

    private Map<String, Map<String, Set<String>>>
    collectComparableValues(
            List<StaviaEvidence> evidences
    ) {
        Map<String, Map<String, Set<String>>> valuesByScope =
                new LinkedHashMap<>();

        for (StaviaEvidence evidence : evidences) {
            if (evidence == null) {
                continue;
            }

            /*
             * Eventos representam momentos históricos distintos.
             * Diferenças entre eventos não constituem contradição.
             */
            if (
                    StaviaEvidenceTypes.EVENTO_OPERACIONAL.equals(
                            evidence.type()
                    )
            ) {
                continue;
            }

            String scope =
                    comparisonScope(evidence);

            Map<String, Set<String>> valuesByField =
                    valuesByScope.computeIfAbsent(
                            scope,
                            ignored -> new LinkedHashMap<>()
                    );

            for (Map.Entry<String, Object> attribute
                    : evidence.attributes().entrySet()) {

                String normalizedField =
                        attribute.getKey()
                                .toLowerCase(Locale.ROOT);

                if (
                        NON_COMPARABLE_FIELDS.contains(
                                normalizedField
                        )
                ) {
                    continue;
                }

                String normalizedValue =
                        normalizeValue(attribute.getValue());

                if (normalizedValue == null) {
                    continue;
                }

                valuesByField
                        .computeIfAbsent(
                                attribute.getKey(),
                                ignored -> new LinkedHashSet<>()
                        )
                        .add(normalizedValue);
            }
        }

        return valuesByScope;
    }

    private String comparisonScope(
            StaviaEvidence evidence
    ) {
        Map<String, Object> attributes =
                evidence.attributes();

        if (
                StaviaEvidenceTypes.RELACAO_ONTOLOGICA.equals(
                        evidence.type()
                )
        ) {
            return String.join(
                    ":",
                    evidence.type(),
                    normalizedAttribute(
                            attributes,
                            "originId",
                            evidence.id()
                    ),
                    normalizedAttribute(
                            attributes,
                            "relationType",
                            "RELACAO"
                    )
            );
        }

        String entityId =
                firstPresentAttribute(
                        attributes,
                        "entityId",
                        "originId",
                        "obraId"
                );

        if (entityId == null) {
            entityId = evidence.type();
        }

        return evidence.type() + ":" + entityId;
    }

    private String firstPresentAttribute(
            Map<String, Object> attributes,
            String... fields
    ) {
        for (String field : fields) {
            Object value = attributes.get(field);

            if (value == null) {
                continue;
            }

            String normalized =
                    String.valueOf(value).trim();

            if (!normalized.isEmpty()) {
                return normalized;
            }
        }

        return null;
    }

    private String normalizedAttribute(
            Map<String, Object> attributes,
            String field,
            String fallback
    ) {
        Object value = attributes.get(field);

        if (value == null) {
            return fallback;
        }

        String normalized =
                String.valueOf(value).trim();

        return normalized.isEmpty()
                ? fallback
                : normalized;
    }

    private String normalizeValue(Object value) {
        if (value == null) {
            return null;
        }

        if (
                value instanceof Map<?, ?>
                || value instanceof Iterable<?>
        ) {
            return null;
        }

        String normalized =
                String.valueOf(value).trim();

        if (normalized.isEmpty()) {
            return null;
        }

        return normalized.toUpperCase(Locale.ROOT);
    }
}
