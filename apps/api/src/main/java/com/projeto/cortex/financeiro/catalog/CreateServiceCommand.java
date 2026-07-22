package com.projeto.cortex.financeiro.catalog;

public record CreateServiceCommand(
        String clientMutationId,
        String code,
        String name,
        String description
) {
}
