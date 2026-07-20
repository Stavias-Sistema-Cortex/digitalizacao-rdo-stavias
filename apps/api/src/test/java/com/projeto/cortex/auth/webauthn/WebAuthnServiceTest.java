package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.ByteArray;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;

class WebAuthnServiceTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String OTHER_COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000009";
    private static final String CHALLENGE_ID =
            "20000000-0000-0000-0000-000000000002";
    private static final String CREDENTIAL_RECORD_ID =
            "30000000-0000-0000-0000-000000000003";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void productionAcceptsOnlyExactHttpsOriginsForTheConfiguredRp() {
        WebAuthnProperties properties = new WebAuthnProperties(
                "cortex.example.invalid",
                "Cortex",
                "https://cortex.example.invalid,"
                        + "https://login.cortex.example.invalid",
                300,
                true
        );

        assertThat(properties.rpId()).isEqualTo("cortex.example.invalid");
        assertThat(properties.origins()).containsExactlyInAnyOrderElementsOf(
                Set.of(
                        "https://cortex.example.invalid",
                        "https://login.cortex.example.invalid"
                )
        );
        assertThatThrownBy(() -> new WebAuthnProperties(
                "cortex.example.invalid",
                "Cortex",
                "http://cortex.example.invalid",
                300,
                true
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebAuthnProperties(
                "cortex.example.invalid",
                "Cortex",
                "https://*.example.invalid",
                300,
                true
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebAuthnProperties(
                "cortex.example.invalid",
                "x".repeat(121),
                "https://cortex.example.invalid",
                300,
                true
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localModeAllowsOnlyLoopbackRpAndOrigins() {
        WebAuthnProperties properties = new WebAuthnProperties(
                "127.0.0.1",
                "Cortex local",
                "http://127.0.0.1:5173",
                300,
                false
        );

        assertThat(properties.origins())
                .containsExactly("http://127.0.0.1:5173");
        assertThatThrownBy(() -> new WebAuthnProperties(
                "dev.example.invalid",
                "Cortex local",
                "http://dev.example.invalid:5173",
                300,
                false
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebAuthnProperties(
                "127.0.0.1",
                "Cortex local",
                "http://localhost:5173",
                300,
                false
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relyingPartyUsesExactOriginsAndStrictVerificationDefaults() {
        WebAuthnProperties properties = new WebAuthnProperties(
                "cortex.example.invalid",
                "Cortex",
                "https://cortex.example.invalid",
                300,
                true
        );

        RelyingParty relyingParty = WebAuthnConfiguration.buildRelyingParty(
                properties,
                mock(CredentialRepository.class)
        );

        assertThat(relyingParty.getIdentity().getId())
                .isEqualTo("cortex.example.invalid");
        assertThat(relyingParty.getIdentity().getName()).isEqualTo("Cortex");
        assertThat(relyingParty.getOrigins())
                .containsExactly("https://cortex.example.invalid");
        assertThat(relyingParty.isAllowOriginPort()).isFalse();
        assertThat(relyingParty.isAllowOriginSubdomain()).isFalse();
        assertThat(relyingParty.isValidateSignatureCounter()).isTrue();
        assertThat(relyingParty.getAttestationConveyancePreference())
                .contains(AttestationConveyancePreference.NONE);
    }

    @Test
    void registrationOptionsUseOneUseChallengeAndRandomPrfSalt() {
        WebAuthnCredentialRepository repository =
                mock(WebAuthnCredentialRepository.class);
        WebAuthnCeremonyEngine engine = mock(WebAuthnCeremonyEngine.class);
        AuthenticatedIdentity identity = identity(COLLABORATOR_ID);
        JsonNode publicKey = JSON.createObjectNode()
                .put("challenge", "public-challenge");
        StartedWebAuthnCeremony started = new StartedWebAuthnCeremony(
                new ByteArray(new byte[]{1, 2, 3}),
                "{\"request\":true}",
                publicKey
        );
        when(repository.findActiveIdentity(COLLABORATOR_ID))
                .thenReturn(java.util.Optional.of(identity));
        when(engine.startRegistration(eq(identity), any(ByteArray.class)))
                .thenReturn(started);
        WebAuthnService service = service(repository, engine);

        WebAuthnOptionsResponse response = service.startRegistration(
                COLLABORATOR_ID
        );

        assertThat(response.challengeId()).isEqualTo(CHALLENGE_ID);
        assertThat(response.rpId()).isEqualTo("cortex.example.invalid");
        assertThat(response.publicKey()).isSameAs(publicKey);
        ArgumentCaptor<ByteArray> salt = ArgumentCaptor.forClass(
                ByteArray.class
        );
        verify(engine).startRegistration(eq(identity), salt.capture());
        assertThat(salt.getValue().size()).isEqualTo(32);
        verify(repository).createChallenge(
                CHALLENGE_ID,
                COLLABORATOR_ID,
                WebAuthnCeremony.REGISTRATION,
                started.challenge(),
                started.requestJson(),
                300
        );
    }

    @Test
    void invalidRegistrationStillConsumesChallengeBeforeVerification() {
        WebAuthnCredentialRepository repository =
                mock(WebAuthnCredentialRepository.class);
        WebAuthnCeremonyEngine engine = mock(WebAuthnCeremonyEngine.class);
        StoredWebAuthnChallenge stored = new StoredWebAuthnChallenge(
                CHALLENGE_ID,
                COLLABORATOR_ID,
                WebAuthnCeremony.REGISTRATION,
                "{\"request\":true}"
        );
        JsonNode credential = JSON.createObjectNode().put("id", "invalid");
        when(repository.consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.REGISTRATION
        )).thenReturn(java.util.Optional.of(stored));
        when(repository.findActiveIdentity(COLLABORATOR_ID))
                .thenReturn(java.util.Optional.of(identity(COLLABORATOR_ID)));
        when(engine.finishRegistration(stored.requestJson(), credential))
                .thenThrow(new IllegalArgumentException("invalid raw response"));
        WebAuthnService service = service(repository, engine);

        assertThatThrownBy(() -> service.finishRegistration(
                COLLABORATOR_ID,
                CHALLENGE_ID,
                credential
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Passkey inválida");

        InOrder order = inOrder(repository, engine);
        order.verify(repository).consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.REGISTRATION
        );
        order.verify(engine).finishRegistration(
                stored.requestJson(),
                credential
        );
        verify(repository, never()).saveCredential(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsRegistrationChallengeOwnedByAnotherCollaborator() {
        WebAuthnCredentialRepository repository =
                mock(WebAuthnCredentialRepository.class);
        WebAuthnCeremonyEngine engine = mock(WebAuthnCeremonyEngine.class);
        JsonNode credential = JSON.createObjectNode().put("id", "synthetic");
        when(repository.consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.REGISTRATION
        )).thenReturn(java.util.Optional.of(new StoredWebAuthnChallenge(
                CHALLENGE_ID,
                OTHER_COLLABORATOR_ID,
                WebAuthnCeremony.REGISTRATION,
                "{\"request\":true}"
        )));

        assertThatThrownBy(() -> service(repository, engine)
                .finishRegistration(
                        COLLABORATOR_ID,
                        CHALLENGE_ID,
                        credential
                )).isInstanceOf(ResponseStatusException.class);

        verify(engine, never()).finishRegistration(any(), any());
    }

    @Test
    void rejectsAssertionWhoseCredentialBelongsToAnotherCollaborator() {
        WebAuthnCredentialRepository repository =
                mock(WebAuthnCredentialRepository.class);
        WebAuthnCeremonyEngine engine = mock(WebAuthnCeremonyEngine.class);
        JsonNode credential = JSON.createObjectNode().put("id", "synthetic");
        ByteArray credentialId = new ByteArray(new byte[]{7, 8, 9});
        ByteArray userHandle = WebAuthnUserHandle.fromCollaboratorId(
                COLLABORATOR_ID
        );
        StoredWebAuthnChallenge stored = new StoredWebAuthnChallenge(
                CHALLENGE_ID,
                null,
                WebAuthnCeremony.AUTHENTICATION,
                "{\"request\":true}"
        );
        when(repository.consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.AUTHENTICATION
        )).thenReturn(java.util.Optional.of(stored));
        when(engine.finishAuthentication(stored.requestJson(), credential))
                .thenReturn(new VerifiedWebAuthnAuthentication(
                        COLLABORATOR_ID,
                        userHandle,
                        credentialId,
                        8,
                        true
                ));
        when(repository.findActiveCredentialOwner(credentialId))
                .thenReturn(java.util.Optional.of(OTHER_COLLABORATOR_ID));

        assertThatThrownBy(() -> service(repository, engine)
                .finishAuthentication(CHALLENGE_ID, credential))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Credencial inválida");

        verify(repository, never()).recordAuthentication(
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    void successfulAssertionResolvesCanonicalUserAndUpdatesCounter() {
        WebAuthnCredentialRepository repository =
                mock(WebAuthnCredentialRepository.class);
        WebAuthnCeremonyEngine engine = mock(WebAuthnCeremonyEngine.class);
        JsonNode credential = JSON.createObjectNode().put("id", "synthetic");
        ByteArray credentialId = new ByteArray(new byte[]{7, 8, 9});
        ByteArray userHandle = WebAuthnUserHandle.fromCollaboratorId(
                COLLABORATOR_ID
        );
        StoredWebAuthnChallenge stored = new StoredWebAuthnChallenge(
                CHALLENGE_ID,
                null,
                WebAuthnCeremony.AUTHENTICATION,
                "{\"request\":true}"
        );
        AuthenticatedIdentity identity = identity(COLLABORATOR_ID);
        when(repository.consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.AUTHENTICATION
        )).thenReturn(java.util.Optional.of(stored));
        when(engine.finishAuthentication(stored.requestJson(), credential))
                .thenReturn(new VerifiedWebAuthnAuthentication(
                        COLLABORATOR_ID,
                        userHandle,
                        credentialId,
                        8,
                        true
                ));
        when(repository.findActiveCredentialOwner(credentialId))
                .thenReturn(java.util.Optional.of(COLLABORATOR_ID));
        when(repository.findActiveIdentity(COLLABORATOR_ID))
                .thenReturn(java.util.Optional.of(identity));
        when(repository.recordAuthentication(
                credentialId,
                COLLABORATOR_ID,
                8,
                true
        )).thenReturn(true);

        assertThat(service(repository, engine).finishAuthentication(
                CHALLENGE_ID,
                credential
        )).isSameAs(identity);
        verify(repository).recordAuthentication(
                credentialId,
                COLLABORATOR_ID,
                8,
                true
        );
    }

    @Test
    void rejectsCredentialMaterialThatCannotFitThePersistedContract() {
        ByteArray userHandle = WebAuthnUserHandle.fromCollaboratorId(
                COLLABORATOR_ID
        );

        assertThatThrownBy(() -> new NewWebAuthnCredential(
                new ByteArray(new byte[1_025]),
                userHandle,
                new ByteArray(new byte[]{1}),
                0,
                Set.of(AuthenticatorTransport.INTERNAL),
                "00000000-0000-0000-0000-000000000000",
                true,
                false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewWebAuthnCredential(
                new ByteArray(new byte[]{1}),
                new ByteArray(new byte[15]),
                new ByteArray(new byte[]{1}),
                0,
                Set.of(),
                "00000000-0000-0000-0000-000000000000",
                true,
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private WebAuthnService service(
            WebAuthnCredentialRepository repository,
            WebAuthnCeremonyEngine engine
    ) {
        SecureRandom random = mock(SecureRandom.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            java.util.Arrays.fill(bytes, (byte) 11);
            return null;
        }).when(random).nextBytes(any(byte[].class));
        java.util.concurrent.atomic.AtomicInteger ids =
                new java.util.concurrent.atomic.AtomicInteger();
        return new WebAuthnService(
                repository,
                engine,
                new WebAuthnProperties(
                        "cortex.example.invalid",
                        "Cortex",
                        "https://cortex.example.invalid",
                        300,
                        true
                ),
                random,
                () -> ids.getAndIncrement() == 0
                        ? CHALLENGE_ID
                        : CREDENTIAL_RECORD_ID
        );
    }

    private AuthenticatedIdentity identity(String collaboratorId) {
        return new AuthenticatedIdentity(
                collaboratorId,
                "Pessoa Sintética",
                PapelAcesso.ALFA
        );
    }
}
