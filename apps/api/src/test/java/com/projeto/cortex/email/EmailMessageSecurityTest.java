package com.projeto.cortex.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMessageSecurityTest {

    @Test
    void stringRepresentationNeverContainsRecipientOrBody() {
        EmailMessage message = new EmailMessage(
                "collaborator@example.invalid",
                "Código de acesso",
                "Código sintético 123456",
                "synthetic-idempotency-key"
        );

        assertThat(message.toString())
                .doesNotContain("collaborator")
                .doesNotContain("123456")
                .doesNotContain("synthetic-idempotency-key")
                .isEqualTo("EmailMessage[redacted]");
    }
}
