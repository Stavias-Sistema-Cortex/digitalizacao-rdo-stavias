package com.projeto.cortex.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OperationalMutationCoverageTest {

    private static final Set<String> REQUIRED_MUTATIONS = Set.of(
            "FINANCE_CONTROL_UNIT_CREATE",
            "FINANCE_ALLOCATION_UPSERT",
            "FINANCE_PURCHASE_ASSETS_CONFIRM",
            "FISCAL_DOCUMENT_EXTRACT",
            "FISCAL_DOCUMENT_LINK_CONFIRM",
            "MESSAGE_SEND",
            "WORKSITE_CREATE",
            "ADMIN_ROLE_CHANGE"
    );

    @Test
    void everyMutationInThisRoundHasAnExecutableOntologyContract()
            throws Exception {
        var definitions = OperationalMutationCatalog.definitions();

        assertThat(definitions)
                .extracting(OperationalMutationCatalog.Definition::id)
                .doesNotHaveDuplicates();
        assertThat(definitions.stream()
                .map(OperationalMutationCatalog.Definition::id)
                .collect(Collectors.toSet()))
                .containsAll(REQUIRED_MUTATIONS);

        for (var definition : definitions) {
            assertThat(definition.eventType()).isNotBlank();
            assertThat(definition.payloadSchemaVersion()).isPositive();
            assertThat(definition.projector()).isNotBlank();
            assertThat(definition.accessPolicy()).isNotBlank();
            assertThat(definition.staviaEvidencePolicy()).isNotBlank();
            assertThat(definition.idempotencyContract()).isNotBlank();
            assertThat(definition.traceabilityContract()).isNotBlank();
            assertThat(definition.implementation().getDeclaredMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .contains(definition.method());
            Class.forName(definition.verificationTest());
        }
    }
}
