package com.projeto.cortex.financeiro.catalog;

public record CreateServiceCommand(
        String id,
        String clientMutationId,
        String code,
        String name,
        String description
) {
    public CreateServiceCommand(
            String clientMutationId,
            String code,
            String name,
            String description
    ) {
        this(null, clientMutationId, code, name, description);
    }
}
