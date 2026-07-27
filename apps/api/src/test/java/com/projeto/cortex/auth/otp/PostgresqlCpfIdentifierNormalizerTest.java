package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresqlCpfIdentifierNormalizerTest {

    private final PostgresqlCpfIdentifierNormalizer normalizer =
            new PostgresqlCpfIdentifierNormalizer();

    @Test
    void rejectsOversizedOrLineBrokenInputBeforeCpfCanonicalization() {
        assertThat(normalizer.canonicalize(
                " ".repeat(502) + "11144477735"
        )).isEqualTo(AuthenticationIdentifierNormalizer.INVALID_VALUE);
        assertThat(normalizer.canonicalize("11144477735\n"))
                .isEqualTo(AuthenticationIdentifierNormalizer.INVALID_VALUE);
    }
}
