package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

class AuthSessionServiceTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String SESSION_ID =
            "20000000-0000-0000-0000-000000000002";

    @Test
    void issuesIndependent32ByteTokensAndPersistsOnlyTheirHashes() {
        JdbcAuthSessionRepository repository =
                mock(JdbcAuthSessionRepository.class);
        SecureRandom random = deterministicRandom();
        Instant databaseExpiry = Instant.parse("2030-01-02T03:04:05Z");
        when(repository.create(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(databaseExpiry);
        AuthSessionService service = new AuthSessionService(
                repository,
                new AuthSessionProperties(43_200),
                random,
                () -> SESSION_ID
        );

        IssuedAuthSession issued = service.issue(new AuthenticatedIdentity(
                COLLABORATOR_ID,
                "Pessoa Sintética",
                PapelAcesso.ALFA
        ));

        assertThat(issued.sessionToken())
                .matches("[A-Za-z0-9_-]{43}")
                .doesNotContain("=");
        assertThat(java.util.Base64.getUrlDecoder().decode(
                issued.sessionToken()
        )).hasSize(32);
        assertThat(issued.csrfToken())
                .matches("[A-Za-z0-9_-]{43}")
                .doesNotContain("=")
                .isNotEqualTo(issued.sessionToken());
        assertThat(java.util.Base64.getUrlDecoder().decode(
                issued.csrfToken()
        )).hasSize(32);
        assertThat(issued.sessionId()).isEqualTo(SESSION_ID);
        assertThat(issued.expiresAt()).isEqualTo(databaseExpiry);

        ArgumentCaptor<String> tokenHash =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> csrfHash =
                ArgumentCaptor.forClass(String.class);
        verify(repository).create(
                org.mockito.ArgumentMatchers.eq(SESSION_ID),
                org.mockito.ArgumentMatchers.eq(COLLABORATOR_ID),
                tokenHash.capture(),
                csrfHash.capture(),
                org.mockito.ArgumentMatchers.eq(43_200)
        );
        assertThat(tokenHash.getValue())
                .matches("[0-9a-f]{64}")
                .isEqualTo(SessionTokenHash.sha256(issued.sessionToken()));
        assertThat(csrfHash.getValue())
                .matches("[0-9a-f]{64}")
                .isEqualTo(SessionTokenHash.sha256(issued.csrfToken()));
        assertThat(tokenHash.getValue())
                .isNotEqualTo(issued.sessionToken());
        assertThat(csrfHash.getValue())
                .isNotEqualTo(issued.csrfToken());
    }

    @Test
    void issuedSessionStringRepresentationRedactsBothRawTokens() {
        String rawSession = SessionTokenFixtures.token((byte) 21);
        String rawCsrf = SessionTokenFixtures.token((byte) 22);
        IssuedAuthSession issued = new IssuedAuthSession(
                SESSION_ID,
                rawSession,
                rawCsrf,
                Instant.parse("2030-01-02T03:04:05Z")
        );

        assertThat(issued.toString())
                .doesNotContain(rawSession)
                .doesNotContain(rawCsrf)
                .contains("[REDACTED]");
    }

    @Test
    void resolvesOnlyThroughTheHashOfAWellFormedRawToken() {
        JdbcAuthSessionRepository repository =
                mock(JdbcAuthSessionRepository.class);
        AuthSessionService service = service(repository);
        String rawToken = SessionTokenFixtures.token((byte) 7);
        ResolvedAuthSession resolved = SessionTokenFixtures.resolved(
                SessionTokenFixtures.token((byte) 8)
        );
        when(repository.findActiveByTokenHash(
                SessionTokenHash.sha256(rawToken)
        )).thenReturn(Optional.of(resolved));

        assertThat(service.resolve(rawToken)).containsSame(resolved);
        assertThat(service.resolve(" ")).isEmpty();
        assertThat(service.resolve("not-a-32-byte-token")).isEmpty();

        verify(repository).findActiveByTokenHash(
                SessionTokenHash.sha256(rawToken)
        );
    }

    @Test
    void validatesCsrfAgainstTheStoredHashAndFailsClosed() {
        JdbcAuthSessionRepository repository =
                mock(JdbcAuthSessionRepository.class);
        AuthSessionService service = service(repository);
        String validCsrf = SessionTokenFixtures.token((byte) 11);
        ResolvedAuthSession resolved =
                SessionTokenFixtures.resolved(validCsrf);

        assertThat(service.matchesCsrf(resolved, validCsrf)).isTrue();
        assertThat(service.matchesCsrf(
                resolved,
                SessionTokenFixtures.token((byte) 12)
        )).isFalse();
        assertThat(service.matchesCsrf(resolved, "")).isFalse();
        assertThat(service.matchesCsrf(null, validCsrf)).isFalse();
    }

    @Test
    void revocationHashesTheRawTokenAndIsSafeToRepeat() {
        JdbcAuthSessionRepository repository =
                mock(JdbcAuthSessionRepository.class);
        AuthSessionService service = service(repository);
        String rawToken = SessionTokenFixtures.token((byte) 17);

        service.revoke(rawToken, "LOGOUT");
        service.revoke(rawToken, "LOGOUT");

        verify(repository, org.mockito.Mockito.times(2)).revokeByTokenHash(
                SessionTokenHash.sha256(rawToken),
                "LOGOUT"
        );
    }

    private AuthSessionService service(
            JdbcAuthSessionRepository repository
    ) {
        return new AuthSessionService(
                repository,
                new AuthSessionProperties(43_200),
                deterministicRandom(),
                () -> SESSION_ID
        );
    }

    private SecureRandom deterministicRandom() {
        SecureRandom random = mock(SecureRandom.class);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer((Answer<Void>) invocation -> {
            byte[] bytes = invocation.getArgument(0);
            byte fill = (byte) calls.incrementAndGet();
            java.util.Arrays.fill(bytes, fill);
            return null;
        }).when(random).nextBytes(org.mockito.ArgumentMatchers.any(byte[].class));
        return random;
    }
}
