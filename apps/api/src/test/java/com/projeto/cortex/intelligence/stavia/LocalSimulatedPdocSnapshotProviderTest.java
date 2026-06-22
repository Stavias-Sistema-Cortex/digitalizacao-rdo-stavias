package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.knowledge.pdoc.LocalSimulatedPdocSnapshotProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalSimulatedPdocSnapshotProviderTest {

    private final LocalSimulatedPdocSnapshotProvider provider =
            new LocalSimulatedPdocSnapshotProvider();

    @Test
    void shouldReturnSnapshotOnlyForValidationWorksite() {
        assertThat(
                provider.findLatestByWorksiteId(
                        "2357081c-7bd8-4e6c-8118-1e25da03461b"
                )
        ).isPresent();

        assertThat(
                provider.findLatestByWorksiteId(
                        "00000000-0000-0000-0000-000000000000"
                )
        ).isEmpty();
    }
}
