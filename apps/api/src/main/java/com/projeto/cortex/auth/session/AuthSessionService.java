package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Issues and resolves opaque server-side sessions without persisting secrets. */
@Service
public class AuthSessionService {

    private static final int RAW_TOKEN_BYTES = 32;
    private static final Base64.Encoder TOKEN_ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final AuthSessionRepository repository;
    private final AuthSessionProperties properties;
    private final SecureRandom secureRandom;
    private final Supplier<String> sessionIdSupplier;

    @Autowired
    public AuthSessionService(
            AuthSessionRepository repository,
            AuthSessionProperties properties
    ) {
        this(
                repository,
                properties,
                new SecureRandom(),
                () -> UUID.randomUUID().toString()
        );
    }

    AuthSessionService(
            AuthSessionRepository repository,
            AuthSessionProperties properties,
            SecureRandom secureRandom,
            Supplier<String> sessionIdSupplier
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.secureRandom = Objects.requireNonNull(secureRandom);
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier);
    }

    public IssuedAuthSession issue(
            AuthenticatedIdentity identity,
            ClientInstanceProof clientInstance
    ) {
        Objects.requireNonNull(identity, "Identidade autenticada obrigatória.");
        Objects.requireNonNull(
                clientInstance,
                "Instância do cliente obrigatória."
        );
        String sessionId = sessionIdSupplier.get();
        String rawSessionToken = generateToken();
        String rawCsrfToken = generateToken();
        if (rawSessionToken.equals(rawCsrfToken)) {
            rawCsrfToken = generateDistinctToken(rawSessionToken);
        }
        Instant expiresAt = repository.create(
                sessionId,
                identity.colaboradorId(),
                SessionTokenHash.sha256(rawSessionToken),
                SessionTokenHash.sha256(rawCsrfToken),
                clientInstance.hash(),
                properties.ttlSeconds()
        );
        return new IssuedAuthSession(
                sessionId,
                rawSessionToken,
                rawCsrfToken,
                expiresAt
        );
    }

    public Optional<ResolvedAuthSession> resolve(
            String rawSessionToken,
            ClientInstanceProof clientInstance
    ) {
        if (!hasRawTokenShape(rawSessionToken) || clientInstance == null) {
            return Optional.empty();
        }
        return repository.findActiveByTokenHashAndClientInstanceHash(
                SessionTokenHash.sha256(rawSessionToken),
                clientInstance.hash()
        ).filter(session -> matchesClientInstance(session, clientInstance));
    }

    public boolean matchesCsrf(
            ResolvedAuthSession session,
            String rawCsrfToken
    ) {
        return session != null
                && hasRawTokenShape(rawCsrfToken)
                && SessionTokenHash.matches(
                        rawCsrfToken,
                        session.csrfHash()
                );
    }

    public boolean matchesClientInstance(
            ResolvedAuthSession session,
            ClientInstanceProof clientInstance
    ) {
        return session != null
                && clientInstance != null
                && SessionTokenHash.matchesHash(
                        clientInstance.hash(),
                        session.clientInstanceHash()
                );
    }

    public void revoke(String rawSessionToken, String reason) {
        if (!hasRawTokenShape(rawSessionToken)) {
            return;
        }
        repository.revokeByTokenHash(
                SessionTokenHash.sha256(rawSessionToken),
                reason
        );
    }

    private String generateDistinctToken(String firstToken) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String candidate = generateToken();
            if (!candidate.equals(firstToken)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Gerador seguro não produziu credenciais independentes."
        );
    }

    private String generateToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        try {
            secureRandom.nextBytes(bytes);
            return TOKEN_ENCODER.encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private boolean hasRawTokenShape(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }
}
