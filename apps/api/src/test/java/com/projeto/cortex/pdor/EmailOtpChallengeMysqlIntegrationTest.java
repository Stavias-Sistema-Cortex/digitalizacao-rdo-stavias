package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityChallengeLookup;
import com.projeto.cortex.auth.otp.AuthRateLimiter;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.EmailOtpChallengeRepository;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.OtpPolicy;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class EmailOtpChallengeMysqlIntegrationTest {

    private static final byte[] TEST_KEY =
            "test-only-otp-hmac-key-material-00000001".getBytes();
    private static final String CODE = "123456";
    private static final String EMAIL = "collaborator@example.invalid";

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void concurrentVerificationHasOneWinnerAndPreservesRoleAndSource()
            throws Exception {
        Fixture fixture = fixture("auth_otp_race");
        DataSource dataSource = fixture.dataSource();
        EmailOtpChallengeService first = service(dataSource);
        EmailOtpChallengeService second = service(dataSource);
        DataSourceTransactionManager firstTransactions =
                new DataSourceTransactionManager(dataSource);
        DataSourceTransactionManager secondTransactions =
                new DataSourceTransactionManager(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<AuthenticatedIdentity>> firstResult =
                    executor.submit(() -> verify(
                            first,
                            firstTransactions,
                            fixture.challengeId(),
                            start
                    ));
            Future<Optional<AuthenticatedIdentity>> secondResult =
                    executor.submit(() -> verify(
                            second,
                            secondTransactions,
                            fixture.challengeId(),
                            start
                    ));
            start.countDown();

            List<Optional<AuthenticatedIdentity>> results = List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS)
            );
            assertThat(results.stream().filter(Optional::isPresent))
                    .hasSize(1);

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("""
                    SELECT status
                    FROM auth_email_challenge
                    WHERE id = ?
                    """, String.class, fixture.challengeId()))
                    .isEqualTo("CONSUMIDO");
            IdentityState identity = jdbc.queryForObject("""
                    SELECT
                        identity.status,
                        identity.email_fonte,
                        identity.email_verificado_em,
                        colaborador.papel_acesso
                    FROM auth_identity identity
                    INNER JOIN colaborador
                        ON colaborador.id = identity.colaborador_id
                    WHERE identity.colaborador_id = ?
                    """,
                    (resultSet, rowNumber) -> new IdentityState(
                            resultSet.getString("status"),
                            resultSet.getString("email_fonte"),
                            resultSet.getTimestamp("email_verificado_em")
                                    != null,
                            resultSet.getString("papel_acesso")
                    ),
                    fixture.collaboratorId()
            );
            assertThat(identity).isEqualTo(new IdentityState(
                    "ATIVA",
                    "MANUAL_PENDENTE",
                    true,
                    "ALFA"
            ));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    @Test
    void activationInvariantFailureRollsBackChallengeConsumption() {
        Fixture fixture = fixture("auth_otp_rollback");
        JdbcTemplate jdbc = new JdbcTemplate(fixture.dataSource());
        EmailOtpChallengeRepository failingActivation =
                new EmailOtpChallengeRepository(jdbc) {
                    @Override
                    public int activateIdentity(
                            String collaboratorId,
                            String authenticationEmail
                    ) {
                        return 0;
                    }
                };
        EmailOtpChallengeService service = service(failingActivation);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(fixture.dataSource())
        );

        assertThatThrownBy(() -> transaction.execute(
                ignored -> service.verify(fixture.challengeId(), CODE)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade não pôde ser ativada.");

        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM auth_email_challenge
                WHERE id = ?
                """, String.class, fixture.challengeId()))
                .isEqualTo("PENDENTE");
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM auth_identity
                WHERE colaborador_id = ?
                """, String.class, fixture.collaboratorId()))
                .isEqualTo("PENDENTE");
    }

    private Optional<AuthenticatedIdentity> verify(
            EmailOtpChallengeService service,
            DataSourceTransactionManager transactions,
            String challengeId,
            CountDownLatch start
    ) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return new TransactionTemplate(transactions).execute(
                ignored -> service.verify(challengeId, CODE)
        );
    }

    private Fixture fixture(String name) {
        database = PdorMysqlTestDatabase.create(name);
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String collaboratorId = UUID.randomUUID().toString();
        String challengeId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    nome,
                    papel_acesso,
                    ativo
                ) VALUES (
                    ?, 'test', 'test', ?,
                    'Colaborador Sintético', 'ALFA', 1
                )
                """,
                collaboratorId,
                "synthetic-" + collaboratorId
        );
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    email_autenticacao,
                    email_fonte,
                    status
                ) VALUES (?, ?, 'MANUAL_PENDENTE', 'PENDENTE')
                """,
                collaboratorId,
                EMAIL
        );
        OtpCryptography cryptography = cryptography();
        new EmailOtpChallengeRepository(jdbc).create(
                challengeId,
                collaboratorId,
                cryptography.identifierDigest("11144477735"),
                cryptography.codeDigest(challengeId, CODE, EMAIL),
                600,
                5
        );
        return new Fixture(
                dataSource,
                collaboratorId,
                challengeId
        );
    }

    private EmailOtpChallengeService service(DataSource dataSource) {
        return service(new EmailOtpChallengeRepository(
                new JdbcTemplate(dataSource)
        ));
    }

    private EmailOtpChallengeService service(
            EmailOtpChallengeRepository challenges
    ) {
        AuthRateLimiter limiter = mock(AuthRateLimiter.class);
        when(limiter.allowVerification(anyString(), anyString()))
                .thenReturn(true);
        return new EmailOtpChallengeService(
                mock(AuthIdentityChallengeLookup.class),
                limiter,
                challenges,
                cryptography(),
                new OtpPolicy(600, 5, 5, 1000, 900),
                mock(ApplicationEventPublisher.class)
        );
    }

    private OtpCryptography cryptography() {
        return new OtpCryptography(TEST_KEY, new SecureRandom());
    }

    private record Fixture(
            DataSource dataSource,
            String collaboratorId,
            String challengeId
    ) {
    }

    private record IdentityState(
            String status,
            String emailSource,
            boolean verified,
            String role
    ) {
    }
}
