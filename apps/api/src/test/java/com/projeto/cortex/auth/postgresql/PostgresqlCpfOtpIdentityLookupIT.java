package com.projeto.cortex.auth.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.identity.AuthenticationChallengeLookup;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlCpfOtpIdentityLookupIT
        extends PostgresqlAuthPersistenceTestSupport {

    private static final String SYNTHETIC_CPF = "111.444.777-35";
    private static final String CURRENT_SECRET =
            "test-only-current-cpf-otp-lookup-material-0001";
    private static final String PREVIOUS_SECRET =
            "test-only-previous-cpf-otp-lookup-material-0001";

    @Test
    void findsActiveIdentityWithAuthenticationEmailThroughCurrentAndPreviousCpfHmacCandidates() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            HmacCpfLookupDigestService digests = digests();
            String collaboratorId =
                    "00000000-0000-4000-8000-000000000551";
            String authenticationEmail =
                    "cpf.otp.lookup@fixture.invalid";
            insertIdentity(
                    jdbc,
                    collaboratorId,
                    authenticationEmail,
                    "ATIVA",
                    true
            );
            AuthenticationChallengeLookup lookup = cpfLookup(jdbc, digests);

            bindCpf(
                    jdbc,
                    collaboratorId,
                    digests.challengeLookup(SYNTHETIC_CPF)
                            .candidates().get(0)
            );
            assertThat(lookup.find(SYNTHETIC_CPF))
                    .hasValueSatisfying(identity -> {
                        assertThat(identity.colaboradorId())
                                .isEqualTo(collaboratorId);
                        assertThat(identity.emailAutenticacao())
                                .isEqualTo(authenticationEmail);
                    });

            bindCpf(
                    jdbc,
                    collaboratorId,
                    digests.challengeLookup(SYNTHETIC_CPF)
                            .candidates().get(1)
            );
            assertThat(lookup.find(SYNTHETIC_CPF))
                    .hasValueSatisfying(identity -> assertThat(
                            identity.emailAutenticacao()
                    ).isEqualTo(authenticationEmail));
        }
    }

    @Test
    void returnsEmptyForInvalidOrUnknownCpf() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            AuthenticationChallengeLookup lookup = cpfLookup(jdbc, digests());

            assertThat(lookup.find("not-a-cpf")).isEmpty();
            assertThat(lookup.find("529.982.247-25")).isEmpty();
        }
    }

    private AuthenticationChallengeLookup cpfLookup(
            JdbcTemplate jdbc,
            CpfLookupDigestService digests
    ) {
        try {
            Object lookup = Class.forName(
                    "com.projeto.cortex.auth.identity."
                            + "PostgresqlCpfOtpIdentityLookup"
            ).getConstructor(JdbcTemplate.class, CpfLookupDigestService.class)
                    .newInstance(jdbc, digests);
            assertThat(lookup).isInstanceOf(AuthenticationChallengeLookup.class);
            return (AuthenticationChallengeLookup) lookup;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "PostgreSQL CPF OTP lookup must resolve protected CPF "
                            + "candidates without querying source MySQL.",
                    exception
            );
        }
    }

    private HmacCpfLookupDigestService digests() {
        return new HmacCpfLookupDigestService(
                "synthetic-current",
                null,
                CURRENT_SECRET,
                new HmacCpfLookupDigestService.PreviousKey(
                        "synthetic-previous",
                        null,
                        PREVIOUS_SECRET
                )
        );
    }

    private void bindCpf(
            JdbcTemplate jdbc,
            String collaboratorId,
            CpfLookupDigest digest
    ) {
        jdbc.update("""
                UPDATE auth_identity
                SET cpf_lookup_key_id = ?, cpf_lookup_hmac = ?
                WHERE colaborador_id = ?
                """, digest.keyId(), digest.value(), collaboratorId);
    }
}
