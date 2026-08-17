package com.projeto.cortex.auth;

public record LoginRequest(String cpf, String password) {

    @Override
    public String toString() {
        return "LoginRequest[authentication=REDACTED]";
    }
}
