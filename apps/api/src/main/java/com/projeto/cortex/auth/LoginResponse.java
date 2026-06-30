package com.projeto.cortex.auth;

public record LoginResponse(
        String token,
        String colaboradorId,
        String nome,
        String cpfMascarado,
        String perfil,
        String grupo
) {
    public LoginResponse withToken(String novoToken) {
        return new LoginResponse(
                novoToken, colaboradorId, nome, cpfMascarado, perfil, grupo
        );
    }
}
