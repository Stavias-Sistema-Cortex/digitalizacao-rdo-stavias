package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class AuthReadinessIndicatorTest {

    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(
            JdbcTemplate.class
    );

    @Test
    void productionRefusesCutoverWithoutVerifiedActiveAlfa() {
        when(jdbc.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(0);

        var readiness = readiness("production");

        assertThatThrownBy(readiness::verifyProductionReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALFA")
                .hasMessageContaining("verificado");
    }

    @Test
    void productionAcceptsVerifiedActiveAlfa() {
        when(jdbc.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(1);

        assertThatCode(readiness("production")
                ::verifyProductionReadiness).doesNotThrowAnyException();
    }

    @Test
    void localProfileDoesNotRequireProductionCutoverState() {
        assertThatCode(readiness("local")
                ::verifyProductionReadiness).doesNotThrowAnyException();

        verify(jdbc, never()).queryForObject(
                anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class)
        );
    }

    @Test
    void runtimeReadinessRequiresLiveDatabase() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertThatCode(readiness("local")
                ::verifyRuntimeReadiness).doesNotThrowAnyException();
    }

    private AuthReadinessIndicator readiness(String profile) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new AuthReadinessIndicator(
                jdbc,
                environment
        );
    }
}
