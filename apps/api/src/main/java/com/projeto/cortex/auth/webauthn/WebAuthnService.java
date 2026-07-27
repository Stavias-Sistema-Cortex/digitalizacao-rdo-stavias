package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.databind.JsonNode;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.yubico.webauthn.data.ByteArray;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Orchestrates one-use WebAuthn ceremonies and canonical user resolution. */
@Service
public class WebAuthnService {

    private static final int PRF_SALT_BYTES = 32;
    private static final int MAX_CREDENTIAL_JSON_CHARACTERS = 65_536;
    private static final String DEFAULT_PASSKEY_NAME = "Passkey";

    private final WebAuthnCredentialRepository repository;
    private final WebAuthnCeremonyEngine engine;
    private final CpfPasskeyIdentityLookup cpfIdentities;
    private final WebAuthnProperties properties;
    private final SecureRandom secureRandom;
    private final Supplier<String> idSupplier;

    @Autowired
    public WebAuthnService(
            WebAuthnCredentialRepository repository,
            WebAuthnCeremonyEngine engine,
            CpfPasskeyIdentityLookup cpfIdentities,
            WebAuthnProperties properties
    ) {
        this(
                repository,
                engine,
                cpfIdentities,
                properties,
                new SecureRandom(),
                () -> UUID.randomUUID().toString()
        );
    }

    WebAuthnService(
            WebAuthnCredentialRepository repository,
            WebAuthnCeremonyEngine engine,
            CpfPasskeyIdentityLookup cpfIdentities,
            WebAuthnProperties properties,
            SecureRandom secureRandom,
            Supplier<String> idSupplier
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.engine = Objects.requireNonNull(engine);
        this.cpfIdentities = Objects.requireNonNull(cpfIdentities);
        this.properties = Objects.requireNonNull(properties);
        this.secureRandom = Objects.requireNonNull(secureRandom);
        this.idSupplier = Objects.requireNonNull(idSupplier);
    }

    public WebAuthnOptionsResponse startRegistration(String collaboratorId) {
        AuthenticatedIdentity identity = repository.findActiveIdentity(
                collaboratorId
        ).orElseThrow(this::invalidSession);
        ByteArray prfSalt = randomPrfSalt();
        StartedWebAuthnCeremony started = engine.startRegistration(
                identity,
                prfSalt
        );
        String challengeId = nextId();
        repository.createChallenge(
                challengeId,
                identity.colaboradorId(),
                WebAuthnCeremony.REGISTRATION,
                started.challenge(),
                started.requestJson(),
                properties.challengeTtlSeconds()
        );
        return new WebAuthnOptionsResponse(
                challengeId,
                started.publicKey(),
                properties.rpId()
        );
    }

    public PasskeySummary finishRegistration(
            String collaboratorId,
            String challengeId,
            JsonNode credentialResponse
    ) {
        StoredWebAuthnChallenge challenge = consume(
                challengeId,
                WebAuthnCeremony.REGISTRATION
        );
        if (challenge.collaboratorId() == null
                || !challenge.collaboratorId().equals(collaboratorId)) {
            throw invalidPasskey();
        }
        requireCredentialShape(credentialResponse);
        repository.findActiveIdentity(collaboratorId)
                .orElseThrow(this::invalidSession);

        VerifiedWebAuthnRegistration verified;
        try {
            verified = engine.finishRegistration(
                    challenge.requestJson(),
                    credentialResponse
            );
        } catch (IllegalArgumentException exception) {
            throw invalidPasskey();
        }
        String handleOwner = WebAuthnUserHandle.toCollaboratorId(
                verified.credential().userHandle()
        ).orElseThrow(this::invalidPasskey);
        if (!collaboratorId.equals(handleOwner)) {
            throw invalidPasskey();
        }

        String credentialRecordId = nextId();
        Instant createdAt = repository.saveCredential(
                credentialRecordId,
                collaboratorId,
                verified.credential(),
                DEFAULT_PASSKEY_NAME
        );
        return new PasskeySummary(
                verified.credential().credentialId().getBase64Url(),
                DEFAULT_PASSKEY_NAME,
                createdAt,
                verified.prfSupported()
        );
    }

    public WebAuthnOptionsResponse startCpfBoundAuthentication(String cpf) {
        StartedWebAuthnCeremony started = engine.startAuthentication();
        String challengeId = nextId();
        repository.createChallenge(
                challengeId,
                cpfIdentities.resolveOrDecoy(cpf).orElse(null),
                WebAuthnCeremony.AUTHENTICATION,
                started.challenge(),
                started.requestJson(),
                properties.challengeTtlSeconds()
        );
        return new WebAuthnOptionsResponse(
                challengeId,
                started.publicKey(),
                properties.rpId()
        );
    }

    public AuthenticatedIdentity finishCpfBoundAuthentication(
            String challengeId,
            JsonNode credentialResponse
    ) {
        StoredWebAuthnChallenge challenge = consume(
                challengeId,
                WebAuthnCeremony.AUTHENTICATION
        );
        String challengeOwner = challenge.collaboratorId();
        if (challengeOwner == null) {
            throw invalidCredential();
        }
        requireCredentialShape(credentialResponse);

        VerifiedWebAuthnAuthentication verified;
        try {
            verified = engine.finishAuthentication(
                    challenge.requestJson(),
                    credentialResponse
            );
        } catch (IllegalArgumentException exception) {
            throw invalidCredential();
        }
        String handleOwner = WebAuthnUserHandle.toCollaboratorId(
                verified.userHandle()
        ).orElseThrow(this::invalidCredential);
        if (!challengeOwner.equals(verified.collaboratorId())
                || !challengeOwner.equals(handleOwner)) {
            throw invalidCredential();
        }
        String persistedOwner = repository.findActiveCredentialOwner(
                verified.credentialId()
        ).orElseThrow(this::invalidCredential);
        if (!challengeOwner.equals(persistedOwner)) {
            throw invalidCredential();
        }
        AuthenticatedIdentity identity = repository.findActiveIdentity(
                challengeOwner
        ).orElseThrow(this::invalidCredential);
        if (!repository.recordAuthentication(
                verified.credentialId(),
                challengeOwner,
                verified.signatureCount(),
                verified.backedUp()
        )) {
            throw invalidCredential();
        }
        return identity;
    }

    private StoredWebAuthnChallenge consume(
            String challengeId,
            WebAuthnCeremony ceremony
    ) {
        return repository.consumeChallenge(challengeId, ceremony)
                .orElseThrow(this::invalidPasskey);
    }

    private void requireCredentialShape(JsonNode response) {
        if (response == null
                || !response.isObject()
                || response.isEmpty()
                || exceedsCredentialBudget(response)) {
            throw invalidPasskey();
        }
    }

    private boolean exceedsCredentialBudget(JsonNode response) {
        int remaining = MAX_CREDENTIAL_JSON_CHARACTERS;
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(response);
        while (!pending.isEmpty()) {
            JsonNode current = pending.removeFirst();
            if (--remaining < 0) {
                return true;
            }
            if (current.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields =
                        current.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    remaining -= field.getKey().length();
                    if (remaining < 0) {
                        return true;
                    }
                    pending.addLast(field.getValue());
                }
            } else if (current.isArray()) {
                current.elements().forEachRemaining(pending::addLast);
            } else if (current.isTextual()) {
                remaining -= current.textValue().length();
            } else {
                remaining -= 32;
            }
            if (remaining < 0) {
                return true;
            }
        }
        return false;
    }

    private ByteArray randomPrfSalt() {
        byte[] bytes = new byte[PRF_SALT_BYTES];
        try {
            secureRandom.nextBytes(bytes);
            return new ByteArray(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private String nextId() {
        String id = idSupplier.get();
        if (WebAuthnUserHandle.parseCanonical(id).isEmpty()) {
            throw new IllegalStateException(
                    "Gerador de identificadores WebAuthn inválido."
            );
        }
        return id;
    }

    private ResponseStatusException invalidSession() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Sessão inválida para registrar passkey."
        );
    }

    private ResponseStatusException invalidPasskey() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Passkey inválida ou challenge expirado."
        );
    }

    private ResponseStatusException invalidCredential() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Credencial inválida."
        );
    }
}
