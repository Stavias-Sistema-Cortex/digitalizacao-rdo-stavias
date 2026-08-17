package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPassphrasesWithoutRewritingTheirContent() {
        String password = "Obra segura desde 2026!";

        assertThat(policy.requireValid(password)).isEqualTo(password);
    }

    @Test
    void rejectsShortBlankControlAndOversizedPasswords() {
        assertThatThrownBy(() -> policy.requireValid("Curta#2026"))
                .isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> policy.requireValid("            "))
                .isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> policy.requireValid(
                "Senha valida\nmas com controle"
        )).isInstanceOf(PasswordPolicyViolationException.class);
        assertThatThrownBy(() -> policy.requireValid("x".repeat(129)))
                .isInstanceOf(PasswordPolicyViolationException.class);
    }

    @Test
    void rejectsCommonPasswordsEvenWhenTheyMeetTheLengthFloor() {
        assertThatThrownBy(() -> policy.requireValid("123456789012"))
                .isInstanceOf(PasswordPolicyViolationException.class);
    }
}
