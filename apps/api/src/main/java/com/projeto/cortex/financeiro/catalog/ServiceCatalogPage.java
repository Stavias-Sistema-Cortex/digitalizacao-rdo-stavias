package com.projeto.cortex.financeiro.catalog;

import java.util.List;

public record ServiceCatalogPage(
        List<ServiceCatalogRow> items,
        String nextCursor,
        long authorizedItemCount,
        long authorizedPriceVersionCount,
        long authorizedCancellationCount,
        int returnedItemCount,
        int returnedPriceVersionCount,
        int returnedCancellationCount,
        String coverage,
        long highWaterMark
) {
    public ServiceCatalogPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
