package com.projeto.cortex.financeiro.catalog;

import java.time.Instant;
import java.util.List;

public record ServiceCatalogPage(
        List<ServiceCatalogRow> items,
        String nextCursor,
        long authorizedItemCount,
        int returnedItemCount,
        String coverage,
        Instant highWaterMark
) {
    public ServiceCatalogPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
