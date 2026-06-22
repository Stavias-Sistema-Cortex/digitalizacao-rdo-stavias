package com.projeto.cortex.intelligence.stavia.policy;

import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class StaviaEvidenceQualityPolicy {

    public static final Duration DEFAULT_MAX_AGE =
            Duration.ofDays(7);

    private final Clock clock;
    private final Duration maxAge;

    public StaviaEvidenceQualityPolicy() {
        this(
                Clock.systemUTC(),
                DEFAULT_MAX_AGE
        );
    }

    public StaviaEvidenceQualityPolicy(
            Clock clock,
            Duration maxAge
    ) {
        if (clock == null) {
            throw new IllegalArgumentException(
                    "O relógio da política deve ser informado."
            );
        }

        if (
                maxAge == null
                || maxAge.isNegative()
                || maxAge.isZero()
        ) {
            throw new IllegalArgumentException(
                    "A idade máxima das evidências deve ser positiva."
            );
        }

        this.clock = clock;
        this.maxAge = maxAge;
    }

    public StaviaQualityAssessment assess(
            List<StaviaEvidence> evidences
    ) {
        if (evidences == null || evidences.isEmpty()) {
            return new StaviaQualityAssessment(
                    StaviaEvidenceQuality.INDETERMINADA,
                    false,
                    List.of(
                            "Nenhuma evidência foi fornecida para avaliação."
                    )
            );
        }

        Instant now = clock.instant();
        List<String> warnings = new ArrayList<>();

        long validatedCount = evidences
                .stream()
                .filter(StaviaEvidence::validated)
                .count();

        long missingDateCount = evidences
                .stream()
                .filter(evidence ->
                        evidence.updatedAt() == null
                )
                .count();

        long staleCount = evidences
                .stream()
                .filter(evidence ->
                        isStale(evidence, now)
                )
                .count();

        if (missingDateCount > 0) {
            warnings.add(
                    formatCount(
                            missingDateCount,
                            "evidência não possui data de atualização.",
                            "evidências não possuem data de atualização."
                    )
            );
        }

        if (staleCount > 0) {
            warnings.add(
                    formatCount(
                            staleCount,
                            "evidência está desatualizada.",
                            "evidências estão desatualizadas."
                    )
            );
        }

        if (validatedCount < evidences.size()) {
            long unvalidatedCount =
                    evidences.size() - validatedCount;

            warnings.add(
                    formatCount(
                            unvalidatedCount,
                            "evidência ainda não foi validada.",
                            "evidências ainda não foram validadas."
                    )
            );
        }

        addPdocWarnings(
                evidences,
                warnings
        );

        boolean stale =
                staleCount > 0 || missingDateCount > 0;

        StaviaEvidenceQuality quality =
                calculateQuality(
                        evidences.size(),
                        validatedCount,
                        staleCount,
                        missingDateCount
                );

        return new StaviaQualityAssessment(
                quality,
                stale,
                warnings
        );
    }

    private void addPdocWarnings(
            List<StaviaEvidence> evidences,
            List<String> warnings
    ) {
        boolean hasUncalibratedPdoc =
                evidences.stream()
                        .filter(evidence ->
                                "PDOC".equals(
                                        evidence.type()
                                )
                        )
                        .anyMatch(evidence ->
                                "NOT_CALIBRATED".equals(
                                        String.valueOf(
                                                evidence.attributes()
                                                        .get(
                                                                "calibrationStatus"
                                                        )
                                        )
                                )
                        );

        boolean hasLocalSimulatedPdoc =
                evidences.stream()
                        .filter(evidence ->
                                "PDOC".equals(
                                        evidence.type()
                                )
                        )
                        .anyMatch(evidence ->
                                "LOCAL_SIMULATED".equals(
                                        String.valueOf(
                                                evidence.attributes()
                                                        .get(
                                                                "sourceMode"
                                                        )
                                        )
                                )
                        );

        if (hasUncalibratedPdoc) {
            warnings.add(
                    "O PDOC ainda não foi calibrado com dados históricos reais."
            );
        }

        if (hasLocalSimulatedPdoc) {
            warnings.add(
                    "A análise utiliza um snapshot local simulado "
                            + "e não representa os custos reais da obra."
            );
        }
    }

    private boolean isStale(
            StaviaEvidence evidence,
            Instant now
    ) {
        if (evidence.updatedAt() == null) {
            return false;
        }

        Duration age = Duration.between(
                evidence.updatedAt(),
                now
        );

        return age.compareTo(maxAge) > 0;
    }

    private StaviaEvidenceQuality calculateQuality(
            int total,
            long validatedCount,
            long staleCount,
            long missingDateCount
    ) {
        if (
                validatedCount == total
                && staleCount == 0
                && missingDateCount == 0
        ) {
            return StaviaEvidenceQuality.ALTA;
        }

        if (
                validatedCount > 0
                && staleCount < total
        ) {
            return StaviaEvidenceQuality.MEDIA;
        }

        return StaviaEvidenceQuality.BAIXA;
    }

    private String formatCount(
            long count,
            String singular,
            String plural
    ) {
        return count + " "
                + (count == 1 ? singular : plural);
    }
}
