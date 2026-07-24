package com.projeto.cortex.financeiro.catalog;

import java.util.List;

public record ServiceCatalogRow(
        ServiceCatalogEntry service,
        List<ServicePriceVersion> priceVersions
) {
    public ServiceCatalogRow {
        priceVersions = priceVersions == null ? List.of() : List.copyOf(priceVersions);
    }
}
