package com.projeto.cortex.auth;

public record LoginRequest(String cpf) {

    @Override
    public String toString() {
        return "LoginRequest[authentication=REDACTED]";
    }
}
