package com.projeto.cortex.auth;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import com.projeto.cortex.auth.otp.AuthRateLimitStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared PostgreSQL gate for the only public direct-CPF route.
 */
@Service
public class AuthLoginRateLimiter {

    private static final String BUCKET_DOMAIN =
            "cortex.auth.login-rate-limit.v1";

    private final AuthRateLimitStore buckets;
    private final CpfLookupDigestService cpfDigests;
    private final int maxRequests;
    private final int globalMaxRequests;
    private final int windowSeconds;

    @Autowired
    public AuthLoginRateLimiter(
            AuthRateLimitStore buckets,
            CpfLookupDigestService cpfDigests,
            @Value("${cortex.auth.login-rate-limit.max-requests:20}")
            int maxRequests,
            @Value("${cortex.auth.login-rate-limit.global-max-requests:1000}")
            int globalMaxRequests,
            @Value("${cortex.auth.login-rate-limit.window-seconds:60}")
            int windowSeconds
    ) {
        if (buckets == null || cpfDigests == null
                || maxRequests < 1 || maxRequests > 10_000
                || globalMaxRequests < 1 || globalMaxRequests > 100_000
                || windowSeconds < 1 || windowSeconds > 3_600) {
            throw new IllegalArgumentException("Limite de login inválido.");
        }
        this.buckets = buckets;
        this.cpfDigests = cpfDigests;
        this.maxRequests = maxRequests;
        this.globalMaxRequests = globalMaxRequests;
        this.windowSeconds = windowSeconds;
    }

    public void check(String cpfRaw, String clientIp) {
        AuthChallengeLookupMaterial material = cpfDigests.challengeLookup(cpfRaw);
        String source = canonicalClientIp(clientIp);
        String global = bucketKey("global", material, "global");
        if (!buckets.hasCapacity(global, globalMaxRequests, windowSeconds)
                || !consume(bucketKey("source", material, source), maxRequests)
                || !consume(global, globalMaxRequests)
                || !consume(bucketKey("identifier", material, "cpf"), maxRequests)) {
            throw rejected();
        }
    }

    private boolean consume(String key, int limit) {
        return buckets.consume(List.of(key), limit, windowSeconds);
    }

    private String canonicalClientIp(String clientIp) {
        return clientIp == null || clientIp.isBlank()
                ? "invalid"
                : clientIp.strip();
    }

    private String bucketKey(
            String scope,
            AuthChallengeLookupMaterial material,
            String subject
    ) {
        String protectedCpf = material.candidates().stream()
                .map(candidate -> candidate.keyId() + ":" + candidate.value())
                .sorted()
                .reduce("", (left, right) -> left + "|" + right);
        return sha256(BUCKET_DOMAIN + "\0" + scope + "\0"
                + subject + "\0" + protectedCpf + "\0"
                + material.legacyDigest());
    }

    private String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private ResponseStatusException rejected() {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Limite de tentativas atingido. Tente novamente em instantes."
        );
    }
}
