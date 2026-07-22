package com.projeto.cortex.financeiro.catalog;

import java.time.Instant;

public record ServiceCatalogEntry(
        String id,
        String code,
        String name,
        String description,
        String status,
        Instant createdAt
) {
}
