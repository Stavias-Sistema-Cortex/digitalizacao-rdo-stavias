package com.projeto.cortex.auth.roles;

public record AdminRoleChangeRequest(
        String papelAcesso,
        String justificativa
) {
}
