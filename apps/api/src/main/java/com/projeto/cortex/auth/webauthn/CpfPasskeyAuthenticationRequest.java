package com.projeto.cortex.auth.webauthn;

/** Public CPF input whose string representation never exposes the identifier. */
public record CpfPasskeyAuthenticationRequest(String cpf) {

    @Override
    public String toString() {
        return "CpfPasskeyAuthenticationRequest[cpf=REDACTED]";
    }
}
