package com.projeto.cortex.auth.roles;

public record AdminRoleVersionedChangeRequest(
        String papelAtual,
        String papelNovo,
        Long baseVersao,
        String justificativa
) {
}
