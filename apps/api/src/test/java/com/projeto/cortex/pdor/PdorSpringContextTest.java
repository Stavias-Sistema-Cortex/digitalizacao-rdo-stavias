package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PdorSpringContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PdorTestConfiguration.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(ObraRepository.class, () -> mock(ObraRepository.class))
                    .withBean(PdorInputLoader.class, () -> mock(PdorInputLoader.class))
                    .withBean(PdorSnapshotRepository.class, () -> mock(PdorSnapshotRepository.class))
                    .withBean(CurrentUserService.class, () -> mock(CurrentUserService.class))
                    .withBean(CortexOperationalMemoryService.class, () -> mock(CortexOperationalMemoryService.class));

    @Test
    void shouldStartPdorSpringSlice() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PdorApplicationService.class);
            assertThat(context).hasSingleBean(PdorController.class);
            assertThat(context).hasSingleBean(PdorExceptionHandler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            PdorApplicationService.class,
            PdorController.class,
            PdorExceptionHandler.class
    })
    static class PdorTestConfiguration {
    }
}
