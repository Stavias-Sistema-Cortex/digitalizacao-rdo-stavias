package com.projeto.cortex.financeiro.catalog;

public interface ServiceCatalogOntologyPublisher {

    void serviceCreated(
            ServiceCatalogEntry service,
            String obraId,
            String actorId,
            String clientMutationId
    );

    void priceVersionPublished(
            ServicePriceVersion price,
            ServiceCatalogEntry service,
            String actorId,
            String clientMutationId
    );

    void priceVersionCancelled(
            ServicePriceVersion price,
            ServiceCatalogEntry service,
            String actorId,
            String clientMutationId
    );
}
