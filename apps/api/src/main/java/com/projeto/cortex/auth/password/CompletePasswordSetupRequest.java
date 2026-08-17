package com.projeto.cortex.auth.password;

public record CompletePasswordSetupRequest(
        String cpf,
        String code,
        String password
) {

    @Override
    public String toString() {
        return "CompletePasswordSetupRequest[authentication=REDACTED]";
    }
}
