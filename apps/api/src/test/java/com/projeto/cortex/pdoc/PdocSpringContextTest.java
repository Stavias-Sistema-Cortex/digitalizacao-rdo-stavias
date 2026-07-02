package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PdocSpringContextTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PdocTestConfiguration.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(ObraRepository.class, () -> mock(ObraRepository.class))
                    .withBean(PdocInputLoader.class, () -> mock(PdocInputLoader.class))
                    .withBean(PdocSnapshotRepository.class, () -> mock(PdocSnapshotRepository.class))
                    .withBean(CurrentUserService.class, () -> mock(CurrentUserService.class));

    @Test
    void shouldStartPdocSpringSlice() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PdocApplicationService.class);
            assertThat(context).hasSingleBean(PdocController.class);
            assertThat(context).hasSingleBean(PdocExceptionHandler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            PdocApplicationService.class,
            PdocController.class,
            PdocExceptionHandler.class
    })
    static class PdocTestConfiguration {
    }
}
