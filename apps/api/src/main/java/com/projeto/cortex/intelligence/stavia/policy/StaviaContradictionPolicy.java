package com.projeto.cortex.intelligence.stavia.policy;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
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

    public StaviaContradictionAssessment assess(
            List<StaviaEvidence> evidences
    ) {
        if (evidences == null || evidences.size() < 2) {
            return StaviaContradictionAssessment.none();
        }

        Map<String, Set<String>> valuesByField =
                collectValuesByField(evidences);

        List<String> conflictingFields = new ArrayList<>();
        boolean critical = false;

        for (Map.Entry<String, Set<String>> entry
                : valuesByField.entrySet()) {

            if (entry.getValue().size() <= 1) {
                continue;
            }

            conflictingFields.add(entry.getKey());

            if (CRITICAL_FIELDS.contains(
                    entry.getKey().toLowerCase(Locale.ROOT)
            )) {
                critical = true;
            }
        }

        if (conflictingFields.isEmpty()) {
            return StaviaContradictionAssessment.none();
        }

        List<String> warnings = new ArrayList<>();

        warnings.add(
                "Foram encontradas informações contraditórias nos campos: "
                        + String.join(", ", conflictingFields)
                        + "."
        );

        if (critical) {
            warnings.add(
                    "A contradição afeta campos críticos e impede uma conclusão segura."
            );
        }

        return new StaviaContradictionAssessment(
                true,
                critical,
                conflictingFields,
                warnings
        );
    }

    private Map<String, Set<String>> collectValuesByField(
            List<StaviaEvidence> evidences
    ) {
        Map<String, Set<String>> valuesByField =
                new LinkedHashMap<>();

        for (StaviaEvidence evidence : evidences) {
            if (evidence == null) {
                continue;
            }

            for (Map.Entry<String, Object> attribute
                    : evidence.attributes().entrySet()) {

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

        return valuesByField;
    }

    private String normalizeValue(Object value) {
        if (value == null) {
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
