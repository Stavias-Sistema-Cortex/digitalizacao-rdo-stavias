package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Argon2idPasswordHashServiceTest {

    private final Argon2idPasswordHashService hashes =
            new Argon2idPasswordHashService();

    @Test
    void storesArgon2idWithTheExplicitCostAndAUniqueSalt() {
        String first = hashes.hash("Uma frase secreta segura!");
        String second = hashes.hash("Uma frase secreta segura!");

        assertThat(first)
                .startsWith("$argon2id$v=19$m=19456,t=2,p=1$")
                .isNotEqualTo(second);
        assertThat(hashes.matches("Uma frase secreta segura!", first)).isTrue();
        assertThat(hashes.matches("Outra frase secreta!", first)).isFalse();
    }

    @Test
    void malformedOrMissingHashesNeverAuthenticate() {
        assertThat(hashes.matches("Uma frase secreta segura!", null)).isFalse();
        assertThat(hashes.matches("Uma frase secreta segura!", "não-é-hash"))
                .isFalse();
        assertThat(hashes.matches(null, "$argon2id$inválido")).isFalse();
    }
}
