package com.projeto.cortex.intelligence.stavia.knowledge.pdor;

import com.projeto.cortex.intelligence.PdorContextBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Snapshot exclusivamente local para validação técnica da integração
 * Stav.IA -> PdorKnowledgeSource -> PdorEngine.
 *
 * Os valores não representam dados financeiros reais da Stavias.
 * Este bean nunca deve ser ativado em produção.
 */
@Component
@Profile("local")
@ConditionalOnProperty(
        prefix = "cortex.pdor",
        name = "local-snapshot-enabled",
        havingValue = "true"
)
public final class LocalSimulatedPdorSnapshotProvider
        implements PdorSnapshotProvider {

    private static final String VALIDATION_WORKSITE_ID =
            "2357081c-7bd8-4e6c-8118-1e25da03461b";

    @Override
    public Optional<PdorContextBuilder.PdorSourceSnapshot>
    findLatestByWorksiteId(String worksiteId) {
        if (!VALIDATION_WORKSITE_ID.equals(worksiteId)) {
            return Optional.empty();
        }

        return Optional.of(
                new PdorContextBuilder.PdorSourceSnapshot(
                        VALIDATION_WORKSITE_ID,
                        LocalDate.of(2026, 6, 22),

                        new BigDecimal("10000000.00"),
                        new BigDecimal("4900000.00"),
                        new BigDecimal("6100000.00"),

                        100.0,
                        55.0,
                        45.0,

                        1000.0,
                        1120.0,

                        10.0,
                        8.5,

                        18.0,
                        240.0,

                        2,
                        1,
                        3,
                        8,

                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,

                        true,
                        true,
                        true,

                        10_000
                )
        );
    }
}
