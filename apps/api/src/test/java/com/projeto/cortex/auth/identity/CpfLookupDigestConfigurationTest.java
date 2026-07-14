package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CpfLookupDigestConfigurationTest {

    private static final String TEST_SECRET =
            "test-only-context-hmac-secret-material-0001";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CpfLookupDigestConfiguration.class
                    );

    @Test
    void wiresSecretBackedDigestFromExplicitTestConfiguration() {
        contextRunner.withPropertyValues(
                "cortex.auth.cpf-hmac.current-key-id=test-current",
                "cortex.auth.cpf-hmac.current-key-inline=" + TEST_SECRET
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(CpfLookupDigestService.class);
            assertThat(context.getBean(CpfLookupDigestService.class)
                    .current("111.444.777-35").keyId())
                    .isEqualTo("test-current");
        });
    }

    @Test
    void failsClosedWhenCurrentSecretConfigurationIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Key ID de CPF HMAC inválido.");
        });
    }

    @Test
    void failsClosedForPartiallyConfiguredPreviousKey() {
        contextRunner.withPropertyValues(
                "cortex.auth.cpf-hmac.current-key-id=test-current",
                "cortex.auth.cpf-hmac.current-key-inline=" + TEST_SECRET,
                "cortex.auth.cpf-hmac.previous-key-id=test-previous"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "CPF HMAC anterior deve conter pelo menos 32 caracteres."
                    );
        });
    }

    @Test
    void productionRejectsInlineCpfHmacMaterial() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=production",
                "cortex.auth.cpf-hmac.current-key-id=prod-current",
                "cortex.auth.cpf-hmac.current-key-inline=" + TEST_SECRET
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "CPF HMAC em produção exige chave atual em arquivo secreto."
                    );
        });
    }
}
