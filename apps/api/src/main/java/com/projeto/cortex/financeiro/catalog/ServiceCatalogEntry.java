package com.projeto.cortex.financeiro.catalog;

import java.time.Instant;

/**
 * Um serviço do catálogo, vivo ou excluído.
 *
 * <p>A exclusão é um estado, não a ausência da linha: o RDO que já apontou este
 * serviço, as versões de preço e as medições continuam existindo e legíveis. O
 * que muda é que o serviço deixa de ser oferecido para lançamento novo.
 */
public record ServiceCatalogEntry(
        String id,
        String code,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant excludedAt,
        String excludedBy
) {
    /** Assinatura anterior ao estado de exclusão criado pela V72. */
    public ServiceCatalogEntry(
            String id,
            String code,
            String name,
            String description,
            String status,
            Instant createdAt
    ) {
        this(id, code, name, description, status, createdAt, null, null);
    }

    public boolean excluded() {
        return "EXCLUIDO".equals(status);
    }
}
