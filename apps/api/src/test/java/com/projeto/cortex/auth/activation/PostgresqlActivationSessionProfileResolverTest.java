package com.projeto.cortex.auth.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PostgresqlActivationSessionProfileResolverTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";

    @Test
    void buildsTheInitialAlfaProfileWithGlobalScopeOnly() {
        PostgresqlActivationSessionProfileResolver resolver =
                new PostgresqlActivationSessionProfileResolver();
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintética",
                PapelAcesso.ALFA
        );

        resolver.requireEligibleForSessionIssue(identity);
        var profile = resolver.profileForIssuedSession(
                identity,
                Instant.parse("2030-01-02T03:04:05Z")
        );

        assertThat(profile.papelAcesso()).isEqualTo("ALFA");
        assertThat(profile.escopoGlobal()).isTrue();
        assertThat(profile.obraIds()).isEmpty();
    }

    @Test
    void rejectsBetaBeforeSessionOrCookieCanBeIssued() {
        PostgresqlActivationSessionProfileResolver resolver =
                new PostgresqlActivationSessionProfileResolver();

        assertThatThrownBy(() -> resolver.requireEligibleForSessionIssue(
                new AuthenticatedIdentity(
                        COLLABORATOR_ID,
                        "Pessoa Sintética",
                        PapelAcesso.BETA
                )
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode().value()
                ).isEqualTo(401));
    }
}
