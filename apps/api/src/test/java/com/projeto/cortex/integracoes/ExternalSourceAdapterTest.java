package com.projeto.cortex.integracoes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSourceAdapterTest {

    @Test
    void academyAdapterShouldRequireCortexReadOnlyConfiguration() {
        AcademySourceAdapter adapter =
                new AcademySourceAdapter("", "", "");

        assertThatThrownBy(() -> adapter.fetchCompleteSnapshot(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_ACADEMY_DB_URL")
                .hasMessageContaining("CORTEX_ACADEMY_DB_USER")
                .hasMessageContaining("CORTEX_ACADEMY_DB_PASSWORD");
    }

    @Test
    void academyBootstrapLookupShouldRequireCortexReadOnlyConfiguration() {
        AcademySourceAdapter adapter =
                new AcademySourceAdapter("", "", "");

        assertThatThrownBy(() ->
                adapter.findSingleActiveUserForBootstrap("synthetic-canonical-id")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_ACADEMY_DB_URL")
                .hasMessageContaining("CORTEX_ACADEMY_DB_USER")
                .hasMessageContaining("CORTEX_ACADEMY_DB_PASSWORD");
    }

    @Test
    void zeladoriaAdapterShouldRequireCortexReadOnlyConfiguration() {
        ZeladoriaSourceAdapter adapter =
                new ZeladoriaSourceAdapter("", "", "");

        assertThatThrownBy(() -> adapter.fetchAssets(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_ZELADORIA_DB_URL")
                .hasMessageContaining("CORTEX_ZELADORIA_DB_USER")
                .hasMessageContaining("CORTEX_ZELADORIA_DB_PASSWORD");
    }
}
