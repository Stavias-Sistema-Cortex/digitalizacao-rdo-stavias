package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.PdocContextBuilder;
import com.projeto.cortex.intelligence.stavia.knowledge.pdoc.PdocSnapshotProvider;
import com.projeto.cortex.intelligence.stavia.knowledge.pdoc.PdocSnapshotProviderConfiguration;
import com.projeto.cortex.intelligence.stavia.knowledge.pdoc.UnavailablePdocSnapshotProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PdocSnapshotProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            PdocSnapshotProviderConfiguration.class
                    );

    @Test
    void shouldCreateUnavailableProviderWhenNoRealProviderExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(PdocSnapshotProvider.class);

            assertThat(
                    context.getBean(PdocSnapshotProvider.class)
            ).isInstanceOf(
                    UnavailablePdocSnapshotProvider.class
            );
        });
    }

    @Test
    void shouldBackOffWhenCustomProviderExists() {
        contextRunner
                .withBean(
                        PdocSnapshotProvider.class,
                        TestPdocSnapshotProvider::new
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(PdocSnapshotProvider.class);

                    assertThat(
                            context.getBean(PdocSnapshotProvider.class)
                    ).isInstanceOf(
                            TestPdocSnapshotProvider.class
                    );
                });
    }

    private static final class TestPdocSnapshotProvider
            implements PdocSnapshotProvider {

        @Override
        public Optional<PdocContextBuilder.PdocSourceSnapshot>
        findLatestByWorksiteId(String worksiteId) {
            return Optional.empty();
        }
    }
}
