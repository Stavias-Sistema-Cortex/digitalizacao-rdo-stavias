package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.PdorContextBuilder;
import com.projeto.cortex.intelligence.stavia.knowledge.pdor.PdorSnapshotProvider;
import com.projeto.cortex.intelligence.stavia.knowledge.pdor.PdorSnapshotProviderConfiguration;
import com.projeto.cortex.intelligence.stavia.knowledge.pdor.UnavailablePdorSnapshotProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PdorSnapshotProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            PdorSnapshotProviderConfiguration.class
                    );

    @Test
    void shouldCreateUnavailableProviderWhenNoRealProviderExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(PdorSnapshotProvider.class);

            assertThat(
                    context.getBean(PdorSnapshotProvider.class)
            ).isInstanceOf(
                    UnavailablePdorSnapshotProvider.class
            );
        });
    }

    @Test
    void shouldBackOffWhenCustomProviderExists() {
        contextRunner
                .withBean(
                        PdorSnapshotProvider.class,
                        TestPdorSnapshotProvider::new
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(PdorSnapshotProvider.class);

                    assertThat(
                            context.getBean(PdorSnapshotProvider.class)
                    ).isInstanceOf(
                            TestPdorSnapshotProvider.class
                    );
                });
    }

    private static final class TestPdorSnapshotProvider
            implements PdorSnapshotProvider {

        @Override
        public Optional<PdorContextBuilder.PdorSourceSnapshot>
        findLatestByWorksiteId(String worksiteId) {
            return Optional.empty();
        }
    }
}
