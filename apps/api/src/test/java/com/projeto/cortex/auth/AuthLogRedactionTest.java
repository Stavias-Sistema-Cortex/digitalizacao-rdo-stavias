package com.projeto.cortex.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthLogRedactionTest {

    @Test
    void loginRecordsNeverExposeCredentialsOrJwtThroughToString() {
        LoginRequest request = new LoginRequest("11144477735");
        LoginResponse response = new LoginResponse(
                "header.payload.signature",
                "user-1",
                "Pessoa Teste",
                "***.***.***-35",
                "Operacional",
                "Operação",
                "BETA"
        );

        assertThat(request.toString())
                .contains("REDACTED")
                .doesNotContain("11144477735");
        assertThat(response.toString())
                .contains("REDACTED")
                .doesNotContain(
                        "header.payload.signature",
                        "Pessoa Teste",
                        "***.***.***-35"
                );
    }
}
