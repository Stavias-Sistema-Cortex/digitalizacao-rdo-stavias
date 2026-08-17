package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.session.AuthSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;

class PasswordSetupServiceTest {

    private static final String ACTOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String TARGET_ID =
            "20000000-0000-0000-0000-000000000002";
    private static final String CHALLENGE_ID =
            "30000000-0000-0000-0000-000000000003";
    private static final String CPF = "11144477735";
    private static final String CODE = "12345678";
    private static final String PASSWORD = "Frase secreta individual!";
    private static final Instant NOW = Instant.parse("2026-08-17T13:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(1800);

    private final PasswordSetupStore setups = mock(PasswordSetupStore.class);
    private final PasswordCredentialRepository credentials =
            mock(PasswordCredentialRepository.class);
    private final AuthIdentityRepository identities =
            mock(AuthIdentityRepository.class);
    private final PasswordHashService hashes = mock(PasswordHashService.class);
    private final PasswordPolicy passwords = mock(PasswordPolicy.class);
    private final AuthSessionRepository sessions =
            mock(AuthSessionRepository.class);
    private final PasswordSetupCodeCryptography codes =
            mock(PasswordSetupCodeCryptography.class);
    private PasswordSetupService service;

    @BeforeEach
    void setUp() {
        service = new PasswordSetupService(
                setups,
                credentials,
                identities,
                hashes,
                passwords,
                sessions,
                codes,
                () -> CHALLENGE_ID
        );
    }

    @Test
    void authorizedAdministratorIssuesOneTimeResetCodeForAnActiveAcademyUser() {
        when(setups.canManageAccess(ACTOR_ID)).thenReturn(true);
        when(setups.findActiveAcademyTarget(TARGET_ID)).thenReturn(
                Optional.of(new PasswordSetupTarget(TARGET_ID, "Pessoa Alvo"))
        );
        when(credentials.findHashByCollaboratorId(TARGET_ID))
                .thenReturn(Optional.of("$argon2id$existing"));
        when(codes.generateCode()).thenReturn(CODE);
        when(codes.digest(CHALLENGE_ID, CODE)).thenReturn("a".repeat(64));
        when(setups.create(
                CHALLENGE_ID,
                TARGET_ID,
                "a".repeat(64),
                PasswordSetupPurpose.RESET,
                ACTOR_ID,
                1800,
                5
        )).thenReturn(EXPIRES);

        IssuedPasswordSetupCode issued = service.issue(ACTOR_ID, TARGET_ID);

        assertThat(issued).isEqualTo(new IssuedPasswordSetupCode(
                TARGET_ID,
                "Pessoa Alvo",
                CODE,
                PasswordSetupPurpose.RESET,
                EXPIRES
        ));
        InOrder order = inOrder(setups);
        order.verify(setups).invalidatePending(TARGET_ID, "NOVO_CODIGO_EMITIDO");
        order.verify(setups).create(
                CHALLENGE_ID,
                TARGET_ID,
                "a".repeat(64),
                PasswordSetupPurpose.RESET,
                ACTOR_ID,
                1800,
                5
        );
        order.verify(setups).audit(
                "CODIGO_RESET_EMITIDO",
                ACTOR_ID,
                TARGET_ID,
                CHALLENGE_ID
        );
        verify(sessions).revokeAllByCollaboratorId(
                TARGET_ID,
                "REDEFINICAO_DE_SENHA_SOLICITADA"
        );
    }

    @Test
    void issueFailsClosedWithoutTheAdministrativeCapability() {
        when(setups.canManageAccess(ACTOR_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(ACTOR_ID, TARGET_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");

        verify(setups, never()).findActiveAcademyTarget(anyString());
        verify(codes, never()).generateCode();
    }

    @Test
    void validSingleUseCodeStoresOnlyTheHashActivatesAndRevokesOldSessions() {
        AuthIdentity identity = activeIdentity();
        when(passwords.requireValid(PASSWORD)).thenReturn(PASSWORD);
        when(identities.findAcademyPasswordSetupCandidateByCpf(CPF))
                .thenReturn(Optional.of(identity));
        when(setups.lockLatestPending(TARGET_ID)).thenReturn(Optional.of(
                pending(0, 5, NOW, EXPIRES)
        ));
        when(codes.matches(CHALLENGE_ID, CODE, "a".repeat(64)))
                .thenReturn(true);
        when(hashes.hash(PASSWORD)).thenReturn("$argon2id$new-hash");
        when(setups.activateIdentity(TARGET_ID)).thenReturn(1);
        when(setups.consume(CHALLENGE_ID)).thenReturn(1);

        assertThat(service.complete(CPF, CODE, PASSWORD)).isTrue();

        InOrder order = inOrder(setups, credentials, sessions);
        order.verify(credentials).upsertHash(TARGET_ID, "$argon2id$new-hash");
        order.verify(setups).activateIdentity(TARGET_ID);
        order.verify(setups).consume(CHALLENGE_ID);
        order.verify(sessions).revokeAllByCollaboratorId(
                TARGET_ID,
                "SENHA_DEFINIDA_OU_REDEFINIDA"
        );
        order.verify(setups).audit(
                "SENHA_DEFINIDA",
                TARGET_ID,
                TARGET_ID,
                CHALLENGE_ID
        );
    }

    @Test
    void wrongCodeConsumesAnAttemptWithoutHashingOrChangingThePassword() {
        when(passwords.requireValid(PASSWORD)).thenReturn(PASSWORD);
        when(identities.findAcademyPasswordSetupCandidateByCpf(CPF))
                .thenReturn(Optional.of(activeIdentity()));
        when(setups.lockLatestPending(TARGET_ID)).thenReturn(Optional.of(
                pending(4, 5, NOW, EXPIRES)
        ));
        when(codes.matches(CHALLENGE_ID, "87654321", "a".repeat(64)))
                .thenReturn(false);

        assertThat(service.complete(CPF, "87654321", PASSWORD)).isFalse();

        verify(setups).recordFailedAttempt(CHALLENGE_ID);
        verify(hashes, never()).hash(anyString());
        verify(credentials, never()).upsertHash(anyString(), anyString());
        verify(sessions, never()).revokeAllByCollaboratorId(
                anyString(),
                anyString()
        );
    }

    @Test
    void expiredCodeIsClosedWithoutChangingCredentials() {
        when(passwords.requireValid(PASSWORD)).thenReturn(PASSWORD);
        when(identities.findAcademyPasswordSetupCandidateByCpf(CPF))
                .thenReturn(Optional.of(activeIdentity()));
        when(setups.lockLatestPending(TARGET_ID)).thenReturn(Optional.of(
                pending(0, 5, EXPIRES, EXPIRES)
        ));

        assertThat(service.complete(CPF, CODE, PASSWORD)).isFalse();

        verify(setups).markExpired(CHALLENGE_ID);
        verify(codes, never()).matches(anyString(), anyString(), anyString());
        verify(credentials, never()).upsertHash(anyString(), anyString());
    }

    @Test
    void unknownCpfKeepsTheGenericFailureAndNeverLooksUpAChallenge() {
        when(passwords.requireValid(PASSWORD)).thenReturn(PASSWORD);
        when(identities.findAcademyPasswordSetupCandidateByCpf(CPF))
                .thenReturn(Optional.empty());

        assertThat(service.complete(CPF, CODE, PASSWORD)).isFalse();

        verify(setups, never()).lockLatestPending(anyString());
        verify(credentials, never()).upsertHash(anyString(), anyString());
    }

    private AuthIdentity activeIdentity() {
        return new AuthIdentity(TARGET_ID, "Pessoa Alvo", null, "BETA");
    }

    private LockedPasswordSetupChallenge pending(
            int attempts,
            int maxAttempts,
            Instant databaseNow,
            Instant expiresAt
    ) {
        return new LockedPasswordSetupChallenge(
                CHALLENGE_ID,
                TARGET_ID,
                "a".repeat(64),
                expiresAt,
                attempts,
                maxAttempts,
                "PENDENTE",
                databaseNow
        );
    }
}
