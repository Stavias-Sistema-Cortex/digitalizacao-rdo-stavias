package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.Extensions;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RegistrationExtensionInputs;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Audited Yubico 2.9.0 adapter for the four online WebAuthn operations. */
@Component
final class YubicoWebAuthnCeremonyEngine implements WebAuthnCeremonyEngine {

    private final RelyingParty relyingParty;
    private final ObjectMapper objectMapper;

    YubicoWebAuthnCeremonyEngine(
            RelyingParty relyingParty,
            ObjectMapper objectMapper
    ) {
        this.relyingParty = java.util.Objects.requireNonNull(relyingParty);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    }

    @Override
    public StartedWebAuthnCeremony startRegistration(
            AuthenticatedIdentity identity,
            ByteArray prfSalt
    ) {
        UserIdentity user = UserIdentity.builder()
                .name(identity.colaboradorId())
                .displayName(identity.nome())
                .id(WebAuthnUserHandle.fromCollaboratorId(
                        identity.colaboradorId()
                ))
                .build();
        AuthenticatorSelectionCriteria selection =
                AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build();
        RegistrationExtensionInputs extensions =
                RegistrationExtensionInputs.builder()
                        .credProps()
                        .prf(Extensions.Prf.PrfRegistrationInput.eval(
                                Extensions.Prf.PrfValues.one(prfSalt)
                        ))
                        .build();
        PublicKeyCredentialCreationOptions request =
                relyingParty.startRegistration(
                        StartRegistrationOptions.builder()
                                .user(user)
                                .authenticatorSelection(selection)
                                .extensions(extensions)
                                .build()
                );
        try {
            return new StartedWebAuthnCeremony(
                    request.getChallenge(),
                    request.toJson(),
                    publicKeyFromCredentialsJson(
                            request.toCredentialsCreateJson()
                    )
            );
        } catch (JsonProcessingException exception) {
            throw invalidResponse();
        }
    }

    @Override
    public VerifiedWebAuthnRegistration finishRegistration(
            String requestJson,
            JsonNode credential
    ) {
        try {
            PublicKeyCredentialCreationOptions request =
                    PublicKeyCredentialCreationOptions.fromJson(requestJson);
            PublicKeyCredential<
                    AuthenticatorAttestationResponse,
                    ClientRegistrationExtensionOutputs
            > response = PublicKeyCredential.parseRegistrationResponseJson(
                    objectMapper.writeValueAsString(credential)
            );
            RegistrationResult result = relyingParty.finishRegistration(
                    FinishRegistrationOptions.builder()
                            .request(request)
                            .response(response)
                            .build()
            );
            boolean discoverable = result.isDiscoverable().orElse(false);
            if (!result.isUserVerified() || !discoverable) {
                throw invalidResponse();
            }
            Set<AuthenticatorTransport> transports = result.getKeyId()
                    .getTransports()
                    .<Set<AuthenticatorTransport>>map(Set::copyOf)
                    .orElseGet(Set::of);
            boolean prfSupported = result.getClientExtensionOutputs()
                    .flatMap(ClientRegistrationExtensionOutputs::getPrf)
                    .flatMap(Extensions.Prf.PrfRegistrationOutput::getEnabled)
                    .orElse(false);
            return new VerifiedWebAuthnRegistration(
                    new NewWebAuthnCredential(
                            result.getKeyId().getId(),
                            request.getUser().getId(),
                            result.getPublicKeyCose(),
                            result.getSignatureCount(),
                            transports,
                            aaguid(result.getAaguid()),
                            true,
                            result.isBackedUp()
                    ),
                    prfSupported
            );
        } catch (IOException | RegistrationFailedException exception) {
            throw invalidResponse();
        }
    }

    @Override
    public StartedWebAuthnCeremony startAuthentication() {
        AssertionRequest request = relyingParty.startAssertion(
                StartAssertionOptions.builder()
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()
        );
        try {
            return new StartedWebAuthnCeremony(
                    request.getPublicKeyCredentialRequestOptions()
                            .getChallenge(),
                    request.toJson(),
                    publicKeyFromCredentialsJson(
                            request.toCredentialsGetJson()
                    )
            );
        } catch (JsonProcessingException exception) {
            throw invalidResponse();
        }
    }

    @Override
    public VerifiedWebAuthnAuthentication finishAuthentication(
            String requestJson,
            JsonNode credential
    ) {
        try {
            AssertionRequest request = AssertionRequest.fromJson(requestJson);
            PublicKeyCredential<
                    AuthenticatorAssertionResponse,
                    ClientAssertionExtensionOutputs
            > response = PublicKeyCredential.parseAssertionResponseJson(
                    objectMapper.writeValueAsString(credential)
            );
            AssertionResult result = relyingParty.finishAssertion(
                    FinishAssertionOptions.builder()
                            .request(request)
                            .response(response)
                            .build()
            );
            if (!result.isSuccess()
                    || !result.isUserVerified()
                    || !result.isSignatureCounterValid()) {
                throw invalidResponse();
            }
            return new VerifiedWebAuthnAuthentication(
                    result.getUsername(),
                    result.getCredential().getUserHandle(),
                    result.getCredential().getCredentialId(),
                    result.getSignatureCount(),
                    result.isBackedUp()
            );
        } catch (IOException | AssertionFailedException exception) {
            throw invalidResponse();
        }
    }

    private JsonNode publicKeyFromCredentialsJson(String json)
            throws JsonProcessingException {
        JsonNode wrapper = objectMapper.readTree(json);
        JsonNode publicKey = wrapper == null ? null : wrapper.get("publicKey");
        if (publicKey == null || !publicKey.isObject()) {
            throw invalidResponse();
        }
        return publicKey;
    }

    private String aaguid(ByteArray bytes) {
        if (bytes == null || bytes.size() != 16) {
            throw invalidResponse();
        }
        ByteBuffer value = ByteBuffer.wrap(bytes.getBytes());
        return new UUID(value.getLong(), value.getLong()).toString();
    }

    private IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException(
                "Resposta WebAuthn inválida."
        );
    }
}
