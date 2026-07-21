package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CpfNormalizerTest {

    @Test
    void normalizesAValidSyntheticCpfGeneratedAtRuntime() {
        String canonical = generatedSyntheticCpf();
        String formatted = canonical.substring(0, 3)
                + "." + canonical.substring(3, 6)
                + "." + canonical.substring(6, 9)
                + "-" + canonical.substring(9);

        assertThat(CpfNormalizer.requireValid(formatted)).isEqualTo(canonical);
    }

    @Test
    void rejectsAnAlteredSyntheticCpfWithoutEchoingIt() {
        String canonical = generatedSyntheticCpf();
        String altered = canonical.substring(0, 10)
                + ((canonical.charAt(10) - '0' + 1) % 10);

        assertThatThrownBy(() -> CpfNormalizer.requireValid(altered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identificador inválido.")
                .hasMessageNotContaining(altered);
    }

    private String generatedSyntheticCpf() {
        String base = "581729364";
        String first = String.valueOf(checkDigit(base, 10));
        return base + first + checkDigit(base + first, 11);
    }

    private int checkDigit(String digits, int initialWeight) {
        int sum = 0;
        for (int index = 0; index < digits.length(); index++) {
            sum += (digits.charAt(index) - '0') * (initialWeight - index);
        }
        int value = 11 - (sum % 11);
        return value >= 10 ? 0 : value;
    }
}
