package com.projeto.cortex.auth.otp;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.PostgresqlEmailOtpIdentityLookup;
import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import com.projeto.cortex.auth.session.AuthSessionProperties;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlEmailOtpChallengeRepositoryIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Test
    void storesOnlyDigestsAndAllowsExactlyOneConcurrentConsumption() throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String collaboratorId = "00000000-0000-4000-8000-000000000201";
            insertIdentity(jdbc, collaboratorId,
                    "operator.challenge@fixture.invalid", "PENDENTE", true);
            String challengeId = "00000000-0000-4000-8000-000000000202";
            PostgresqlEmailOtpChallengeRepository store =
                    new PostgresqlEmailOtpChallengeRepository(jdbc);

            Instant expiry = store.create(challengeId, collaboratorId,
                    digest('a'), digest('b'), 300, 5);
            assertThat(expiry).isAfter(Instant.now().minusSeconds(2));
            assertThat(jdbc.queryForMap("""
                    SELECT identifier_digest, codigo_digest, status
                    FROM auth_email_challenge WHERE id = ?
                    """, challengeId))
                    .containsEntry("identifier_digest", digest('a'))
                    .containsEntry("codigo_digest", digest('b'))
                    .containsEntry("status", "PENDENTE");

            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Callable<Boolean> consume = () -> {
                    start.await();
                    return Boolean.TRUE.equals(transactions(jdbc).execute(status ->
                            store.lockForVerification(challengeId)
                                    .filter(locked -> "PENDENTE".equals(
                                            locked.challengeStatus()))
                                    .map(locked -> store.consume(challengeId,
                                            collaboratorId, digest('b')) == 1)
                                    .orElse(false)
                    ));
                };
                Future<Boolean> first = executor.submit(consume);
                Future<Boolean> second = executor.submit(consume);
                start.countDown();
                assertThat(List.of(first.get(), second.get()))
                        .containsExactlyInAnyOrder(true, false);
            }
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_email_challenge WHERE id = ?
                    """, String.class, challengeId)).isEqualTo("CONSUMIDO");
        }
    }

    @Test
    void completesAnEmailOtpFlowThenIssuesAnOpaqueSession() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String collaboratorId = "00000000-0000-4000-8000-000000000211";
            String challengeId = "00000000-0000-4000-8000-000000000212";
            String syntheticEmail = "operator.flow@fixture.invalid";
            insertIdentity(jdbc, collaboratorId, syntheticEmail, "PENDENTE", true);

            PostgresqlEmailIdentifierNormalizer normalizer =
                    new PostgresqlEmailIdentifierNormalizer();
            OtpCryptography cryptography = new OtpCryptography(
                    new byte[32], new FixedCodeRandom(), normalizer);
            PostgresqlEmailOtpChallengeRepository store =
                    new PostgresqlEmailOtpChallengeRepository(jdbc);
            ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
            EmailOtpChallengeIssuer issuer = new EmailOtpChallengeIssuer(
                    new PostgresqlEmailOtpIdentityLookup(jdbc), store,
                    cryptography, new OtpPolicy(300, 5, 5, 100, 900),
                    events, normalizer);
            AuthRateLimiter limiter = mock(AuthRateLimiter.class);
            when(limiter.allow(anyString(), anyString())).thenReturn(true);
            EmailOtpChallengeService service = new EmailOtpChallengeService(
                    limiter, issuer, store, cryptography,
                    new OtpPolicy(300, 5, 5, 100, 900), () -> challengeId);

            service.request("  OPERATOR.FLOW@FIXTURE.INVALID  ", "203.0.113.1");
            ArgumentCaptor<OtpDeliveryRequested> delivery =
                    ArgumentCaptor.forClass(OtpDeliveryRequested.class);
            verify(events).publishEvent(delivery.capture());
            assertThat(delivery.getValue().deliverable()).isTrue();

            Optional<AuthenticatedIdentity> wrongCode = transactions(jdbc)
                    .execute(status -> service.verify(challengeId, "000000"));
            assertThat(wrongCode).isEmpty();

            Optional<AuthenticatedIdentity> verified = transactions(jdbc)
                    .execute(status -> service.verify(challengeId,
                            delivery.getValue().code()));
            assertThat(verified).contains(new AuthenticatedIdentity(
                    collaboratorId, "Synthetic Operator", PapelAcesso.ALFA));
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_identity WHERE colaborador_id = ?
                    """, String.class, collaboratorId)).isEqualTo("ATIVA");

            AuthSessionService sessions = new AuthSessionService(
                    new PostgresqlAuthSessionRepository(jdbc),
                    new AuthSessionProperties(300));
            var issued = sessions.issue(verified.orElseThrow());
            assertThat(sessions.resolve(issued.sessionToken()))
                    .hasValueSatisfying(resolved -> assertThat(
                            resolved.collaboratorId()).isEqualTo(collaboratorId));
            assertThat(service.verify(challengeId, delivery.getValue().code())).isEmpty();

            String expiredChallengeId = "00000000-0000-4000-8000-000000000213";
            store.create(expiredChallengeId, collaboratorId, digest('g'),
                    cryptography.codeDigest(expiredChallengeId, "123456", syntheticEmail),
                    300, 5);
            jdbc.update("""
                    UPDATE auth_email_challenge
                    SET expira_em = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, expiredChallengeId);
            Optional<AuthenticatedIdentity> expired = transactions(jdbc)
                    .execute(status -> service.verify(expiredChallengeId, "123456"));
            assertThat(expired).isEmpty();
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_email_challenge WHERE id = ?
                    """, String.class, expiredChallengeId)).isEqualTo("EXPIRADO");

            String inactiveId = "00000000-0000-4000-8000-000000000214";
            String inactiveChallengeId = "00000000-0000-4000-8000-000000000215";
            String inactiveEmail = "operator.inactive@fixture.invalid";
            insertIdentity(jdbc, inactiveId, inactiveEmail, "PENDENTE", false);
            store.create(inactiveChallengeId, inactiveId, digest('h'),
                    cryptography.codeDigest(inactiveChallengeId, "123456", inactiveEmail),
                    300, 5);
            Optional<AuthenticatedIdentity> inactive = transactions(jdbc)
                    .execute(status -> service.verify(inactiveChallengeId, "123456"));
            assertThat(inactive).isEmpty();
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_email_challenge WHERE id = ?
                    """, String.class, inactiveChallengeId)).isEqualTo("BLOQUEADO");
        }
    }

    @Test
    void rollsBackConsumptionWhenTheConditionalActivationFails() {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            String collaboratorId = "00000000-0000-4000-8000-000000000221";
            String challengeId = "00000000-0000-4000-8000-000000000222";
            String syntheticEmail = "operator.rollback@fixture.invalid";
            insertIdentity(jdbc, collaboratorId, syntheticEmail, "PENDENTE", true);
            PostgresqlEmailIdentifierNormalizer normalizer =
                    new PostgresqlEmailIdentifierNormalizer();
            OtpCryptography cryptography = new OtpCryptography(
                    new byte[32], new FixedCodeRandom(), normalizer);
            PostgresqlEmailOtpChallengeRepository store = spy(
                    new PostgresqlEmailOtpChallengeRepository(jdbc));
            ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
            EmailOtpChallengeIssuer issuer = new EmailOtpChallengeIssuer(
                    new PostgresqlEmailOtpIdentityLookup(jdbc), store,
                    cryptography, new OtpPolicy(300, 5, 5, 100, 900),
                    events, normalizer);
            AuthRateLimiter limiter = mock(AuthRateLimiter.class);
            when(limiter.allow(anyString(), anyString())).thenReturn(true);
            EmailOtpChallengeService service = new EmailOtpChallengeService(
                    limiter, issuer, store, cryptography,
                    new OtpPolicy(300, 5, 5, 100, 900), () -> challengeId);

            service.request(syntheticEmail, "203.0.113.2");
            ArgumentCaptor<OtpDeliveryRequested> delivery =
                    ArgumentCaptor.forClass(OtpDeliveryRequested.class);
            verify(events).publishEvent(delivery.capture());
            doReturn(0).when(store).activateIdentity(collaboratorId, syntheticEmail);

            assertThatThrownBy(() -> transactions(jdbc).execute(status ->
                    service.verify(challengeId, delivery.getValue().code())
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Identidade não pôde ser ativada.");
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM auth_email_challenge WHERE id = ?
                    """, String.class, challengeId)).isEqualTo("PENDENTE");
        }
    }

    private static final class FixedCodeRandom extends SecureRandom {

        @Override
        public int nextInt(int bound) {
            return 123456;
        }
    }
}
