package com.projeto.cortex.auth.identity;

/** Identity data located for an authentication challenge, never proof of login. */
public record AuthIdentity(
        String colaboradorId,
        String nome,
        String emailAutenticacao,
        String papelAcesso
) {
}
