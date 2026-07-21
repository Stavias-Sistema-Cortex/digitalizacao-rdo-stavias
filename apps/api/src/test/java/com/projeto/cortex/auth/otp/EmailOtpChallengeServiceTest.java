package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityChallengeLookup;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class EmailOtpChallengeServiceTest {

    private static final String CHALLENGE_ID =
            "00000000-0000-4000-8000-000000000001";
    private static final String SYNTHETIC_CPF = "11144477735";
    private static final String SYNTHETIC_EMAIL =
            "collaborator@example.invalid";
    private static final String CODE = "123456";
    private static final Instant DATABASE_EXPIRES_AT = Instant.parse(
            "2026-07-13T18:10:00.123456Z"
    );

    private AuthIdentityChallengeLookup identities;
    private AuthRateLimiter rateLimiter;
    private EmailOtpChallengeRepository challenges;
    private ApplicationEventPublisher events;
    private OtpCryptography cryptography;
    private EmailOtpChallengeService service;

    @BeforeEach
    void setUp() {
        identities = mock(AuthIdentityChallengeLookup.class);
        rateLimiter = mock(AuthRateLimiter.class);
        challenges = mock(EmailOtpChallengeRepository.class);
        when(challenges.create(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyInt(),
                anyInt()
        )).thenReturn(DATABASE_EXPIRES_AT);
        events = mock(ApplicationEventPublisher.class);
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(123456);
        cryptography = new OtpCryptography(
                "test-only-otp-hmac-key-material-00000001".getBytes(),
                random
        );
        service = new EmailOtpChallengeService(
                identities,
                rateLimiter,
                challenges,
                cryptography,
                new OtpPolicy(600, 5, 5, 1000, 900),
                events,
                () -> CHALLENGE_ID
        );
    }

    @Test
    void knownAndUnknownIdentifiersHaveTheSamePublicContractAndStorageShape() {
        when(rateLimiter.allow(anyString(), anyString())).thenReturn(true);
        when(identities.find(SYNTHETIC_CPF))
                .thenReturn(Optional.of(activeIdentity()))
                .thenReturn(Optional.empty());

        OtpChallengeResponse known = service.request(
                SYNTHETIC_CPF,
                "203.0.113.10"
        );
        OtpChallengeResponse unknown = service.request(
                SYNTHETIC_CPF,
                "203.0.113.11"
        );

        assertThat(known.message()).isEqualTo(unknown.message());
        assertThat(known.expiresInSeconds())
                .isEqualTo(unknown.expiresInSeconds());
        assertThat(known.challengeId()).isEqualTo(CHALLENGE_ID);
        verify(challenges, times(2)).create(
                eq(CHALLENGE_ID),
                any(),
                anyString(),
                anyString(),
                eq(600),
                eq(5)
        );
        verify(events, times(2)).publishEvent(
                any(OtpDeliveryRequested.class)
        );
    }

    @Test
    void persistsOnlyDigestsAndPublishesASecretSanitizedDeliveryEvent() {
        when(rateLimiter.allow(anyString(), anyString())).thenReturn(true);
        when(identities.find(SYNTHETIC_CPF))
                .thenReturn(Optional.of(activeIdentity()));

        service.request(SYNTHETIC_CPF, "203.0.113.10");

        ArgumentCaptor<String> identifierDigest =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> codeDigest =
                ArgumentCaptor.forClass(String.class);
        verify(challenges).create(
                eq(CHALLENGE_ID),
                eq("collaborator-id"),
                identifierDigest.capture(),
                codeDigest.capture(),
                eq(600),
                eq(5)
        );
        assertThat(identifierDigest.getValue()).matches("[0-9a-f]{64}")
                .doesNotContain(SYNTHETIC_CPF);
        assertThat(codeDigest.getValue()).matches("[0-9a-f]{64}")
                .doesNotContain(CODE)
                .doesNotContain(SYNTHETIC_EMAIL);

        ArgumentCaptor<OtpDeliveryRequested> delivery =
                ArgumentCaptor.forClass(OtpDeliveryRequested.class);
        verify(events).publishEvent(delivery.capture());
        assertThat(delivery.getValue().deliverable()).isTrue();
        assertThat(delivery.getValue().expiresAt())
                .isEqualTo(DATABASE_EXPIRES_AT);
        assertThat(delivery.getValue().toString())
                .doesNotContain(CODE)
                .doesNotContain(SYNTHETIC_EMAIL)
                .doesNotContain(CHALLENGE_ID);
    }

    @Test
    void invalidOrEmailLessIdentityBecomesAPersistedDecoy() {
        when(rateLimiter.allow(anyString(), anyString())).thenReturn(true);
        when(identities.find(SYNTHETIC_CPF))
                .thenReturn(Optional.of(new AuthIdentity(
                        "collaborator-id",
                        "Colaborador Sintético",
                        null,
                        "ALFA"
                )));

        OtpChallengeResponse response = service.request(
                SYNTHETIC_CPF,
                "203.0.113.10"
        );

        assertThat(response.challengeId()).isEqualTo(CHALLENGE_ID);
        verify(challenges).create(
                eq(CHALLENGE_ID),
                eq(null),
                anyString(),
                anyString(),
                eq(600),
                eq(5)
        );
        ArgumentCaptor<OtpDeliveryRequested> delivery =
                ArgumentCaptor.forClass(OtpDeliveryRequested.class);
        verify(events).publishEvent(delivery.capture());
        assertThat(delivery.getValue().deliverable()).isFalse();
    }

    @Test
    void sharedRateLimitReturnsTheSameShapeWithoutCreatingMoreRows() {
        when(rateLimiter.allow(anyString(), anyString())).thenReturn(false);

        OtpChallengeResponse response = service.request(
                SYNTHETIC_CPF,
                "203.0.113.10"
        );

        assertThat(response.challengeId()).isEqualTo(CHALLENGE_ID);
        verify(identities, never()).find(anyString());
        verify(challenges, never()).create(
                anyString(), any(), anyString(), anyString(),
                anyInt(), anyInt()
        );
        verify(events, never()).publishEvent(any(OtpDeliveryRequested.class));
    }

    @Test
    void validCodeConsumesOnceAndActivatesWithoutChangingTheRole() {
        EmailOtpChallengeRepository.LockedChallenge candidate =
                realCandidate(CODE, Instant.parse("2026-07-13T18:00:00Z"));
        when(challenges.lockForVerification(CHALLENGE_ID))
                .thenReturn(Optional.of(candidate));
        when(challenges.consume(
                eq(CHALLENGE_ID),
                eq("collaborator-id"),
                anyString()
        )).thenReturn(1, 0);
        when(challenges.activateIdentity(
                "collaborator-id",
                SYNTHETIC_EMAIL
        )).thenReturn(1);

        Optional<AuthenticatedIdentity> first = service.verify(
                CHALLENGE_ID,
                CODE
        );
        Optional<AuthenticatedIdentity> replay = service.verify(
                CHALLENGE_ID,
                CODE
        );

        assertThat(first).contains(new AuthenticatedIdentity(
                "collaborator-id",
                "Colaborador Sintético",
                PapelAcesso.ALFA
        ));
        assertThat(replay).isEmpty();
        verify(challenges, times(2)).consume(
                eq(CHALLENGE_ID),
                eq("collaborator-id"),
                anyString()
        );
        verify(challenges).activateIdentity(
                "collaborator-id",
                SYNTHETIC_EMAIL
        );
    }

    @Test
    void activationRaceFailsTheTransactionInsteadOfCommittingConsumption() {
        Instant now = Instant.parse("2026-07-13T18:00:00Z");
        when(challenges.lockForVerification(CHALLENGE_ID))
                .thenReturn(Optional.of(realCandidate(CODE, now)));
        when(challenges.consume(
                eq(CHALLENGE_ID), eq("collaborator-id"), anyString()
        )).thenReturn(1);
        when(challenges.activateIdentity("collaborator-id", SYNTHETIC_EMAIL))
                .thenReturn(0);

        assertThatThrownBy(() -> service.verify(CHALLENGE_ID, CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade não pôde ser ativada.");
    }

    @Test
    void wrongMalformedExpiredAndDecoyCodesNeverAuthenticate() {
        Instant now = Instant.parse("2026-07-13T18:00:00Z");
        when(challenges.lockForVerification(CHALLENGE_ID))
                .thenReturn(
                        Optional.of(realCandidate(CODE, now)),
                        Optional.of(realCandidate(CODE, now)),
                        Optional.of(expiredCandidate(now)),
                        Optional.of(decoyCandidate(CODE, now))
                );

        assertThat(service.verify(CHALLENGE_ID, "654321")).isEmpty();
        assertThat(service.verify(CHALLENGE_ID, "not-a-code")).isEmpty();
        assertThat(service.verify(CHALLENGE_ID, CODE)).isEmpty();
        assertThat(service.verify(CHALLENGE_ID, CODE)).isEmpty();

        verify(challenges, times(3)).recordFailedAttempt(CHALLENGE_ID);
        verify(challenges).markExpired(CHALLENGE_ID);
        verify(challenges, never()).activateIdentity(anyString(), anyString());
    }

    private AuthIdentity activeIdentity() {
        return new AuthIdentity(
                "collaborator-id",
                "Colaborador Sintético",
                SYNTHETIC_EMAIL,
                "ALFA"
        );
    }

    private EmailOtpChallengeRepository.LockedChallenge realCandidate(
            String code,
            Instant now
    ) {
        return new EmailOtpChallengeRepository.LockedChallenge(
                CHALLENGE_ID,
                "collaborator-id",
                cryptography.codeDigest(
                        CHALLENGE_ID,
                        code,
                        SYNTHETIC_EMAIL
                ),
                now.plusSeconds(300),
                0,
                5,
                "PENDENTE",
                now,
                SYNTHETIC_EMAIL,
                "Colaborador Sintético",
                "ALFA",
                "PENDENTE",
                true,
                false
        );
    }

    private EmailOtpChallengeRepository.LockedChallenge expiredCandidate(
            Instant now
    ) {
        EmailOtpChallengeRepository.LockedChallenge candidate =
                realCandidate(CODE, now);
        return new EmailOtpChallengeRepository.LockedChallenge(
                candidate.challengeId(),
                candidate.collaboratorId(),
                candidate.codeDigest(),
                now,
                candidate.attempts(),
                candidate.maxAttempts(),
                candidate.challengeStatus(),
                now,
                candidate.authenticationEmail(),
                candidate.collaboratorName(),
                candidate.persistedRole(),
                candidate.identityStatus(),
                candidate.collaboratorActive(),
                candidate.collaboratorDeleted()
        );
    }

    private EmailOtpChallengeRepository.LockedChallenge decoyCandidate(
            String code,
            Instant now
    ) {
        return new EmailOtpChallengeRepository.LockedChallenge(
                CHALLENGE_ID,
                null,
                cryptography.decoyCodeDigest(CHALLENGE_ID, code),
                now.plusSeconds(300),
                0,
                5,
                "PENDENTE",
                now,
                null,
                null,
                null,
                null,
                false,
                false
        );
    }
}
