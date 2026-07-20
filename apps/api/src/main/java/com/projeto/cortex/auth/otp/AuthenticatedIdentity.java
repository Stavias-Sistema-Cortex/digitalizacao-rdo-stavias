package com.projeto.cortex.auth.otp;

import com.projeto.cortex.auth.PapelAcesso;

/** Minimal internal result that can safely seed an opaque server session. */
public record AuthenticatedIdentity(
        String colaboradorId,
        String nome,
        PapelAcesso papelAcesso
) {

    public AuthenticatedIdentity {
        if (colaboradorId == null
                || colaboradorId.isBlank()
                || nome == null
                || nome.isBlank()
                || papelAcesso == null) {
            throw new IllegalArgumentException(
                    "Identidade autenticada inválida."
            );
        }
        colaboradorId = colaboradorId.strip();
        nome = nome.strip();
    }
}
