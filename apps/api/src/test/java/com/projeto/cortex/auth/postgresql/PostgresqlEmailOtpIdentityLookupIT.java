package com.projeto.cortex.auth.postgresql;

import com.projeto.cortex.auth.identity.PostgresqlEmailOtpIdentityLookup;
import com.projeto.cortex.auth.otp.AuthenticationIdentifierNormalizer;
import com.projeto.cortex.auth.otp.PostgresqlEmailIdentifierNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

class PostgresqlEmailOtpIdentityLookupIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Test
    void findsOneEligibleCanonicalEmailAndNeverQueriesForInvalidIdentifiers() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String collaboratorId = "00000000-0000-4000-8000-000000000101";
            String syntheticEmail = "operator.lookup@fixture.invalid";
            insertIdentity(jdbc, collaboratorId, syntheticEmail, "PENDENTE", true);

            PostgresqlEmailIdentifierNormalizer normalizer =
                    new PostgresqlEmailIdentifierNormalizer();
            JdbcTemplate lookupJdbc = spy(jdbc);
            PostgresqlEmailOtpIdentityLookup lookup =
                    new PostgresqlEmailOtpIdentityLookup(lookupJdbc);

            String canonical = normalizer.canonicalize(
                    "  OPERATOR.LOOKUP@FIXTURE.INVALID  "
            );
            assertThat(lookup.find(canonical))
                    .hasValueSatisfying(identity -> assertThat(
                            identity.colaboradorId()
                    ).isEqualTo(collaboratorId));

            JdbcTemplate invalidLookupJdbc = spy(jdbc);
            PostgresqlEmailOtpIdentityLookup invalidLookup =
                    new PostgresqlEmailOtpIdentityLookup(invalidLookupJdbc);
            assertThat(invalidLookup.find(
                    AuthenticationIdentifierNormalizer.INVALID_VALUE
            )).isEmpty();
            assertThat(invalidLookup.find("not-an-email")).isEmpty();
            assertThat(mockingDetails(invalidLookupJdbc).getInvocations()).isEmpty();

            jdbc.update("UPDATE colaborador SET ativo = FALSE WHERE id = ?", collaboratorId);
            assertThat(lookup.find(canonical)).isEmpty();
        }
    }
}
