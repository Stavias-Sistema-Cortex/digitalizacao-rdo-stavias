package com.projeto.cortex.auth.password;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id password hashing with explicit, reviewable resource costs. */
@Component
public final class Argon2idPasswordHashService
        implements PasswordHashService {

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(
            SALT_LENGTH_BYTES,
            HASH_LENGTH_BYTES,
            PARALLELISM,
            MEMORY_KIB,
            ITERATIONS
    );

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("Senha obrigatória.");
        }
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedHash) {
        if (rawPassword == null
                || encodedHash == null
                || !encodedHash.startsWith("$argon2id$")) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, encodedHash);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
