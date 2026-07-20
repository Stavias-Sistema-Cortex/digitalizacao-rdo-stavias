package com.projeto.cortex.auth;

public record LoginResponse(
        String token,
        String colaboradorId,
        String nome,
        String cpfMascarado,
        String perfil,
        String grupo,
        String papelAcesso
) {
    public LoginResponse withToken(String novoToken) {
        return new LoginResponse(
                novoToken, colaboradorId, nome, cpfMascarado, perfil, grupo, papelAcesso
        );
    }

    @Override
    public String toString() {
        return "LoginResponse[authentication=REDACTED]";
    }
}
