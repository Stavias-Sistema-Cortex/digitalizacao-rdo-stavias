package com.projeto.cortex.auth;

public record LoginRequest(String cpf, String senha) {

    @Override
    public String toString() {
        return "LoginRequest[credentials=REDACTED]";
    }
}
