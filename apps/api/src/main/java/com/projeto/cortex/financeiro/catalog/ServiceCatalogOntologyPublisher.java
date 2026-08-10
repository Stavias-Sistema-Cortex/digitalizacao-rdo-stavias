package com.projeto.cortex.financeiro.catalog;

public interface ServiceCatalogOntologyPublisher {

    void serviceCreated(
            ServiceCatalogEntry service,
            String obraId,
            String actorId,
            String clientMutationId
    );

    /**
     * O cadastro foi corrigido: o objeto na memória passa a se chamar o que
     * sempre se quis que ele se chamasse, e fica registrado quem corrigiu.
     */
    void serviceUpdated(
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

    void priceVersionSuperseded(
            ServicePriceVersion predecessor,
            ServicePriceVersion replacement,
            ServiceCatalogEntry service,
            String actorId,
            String clientMutationId
    );

    /**
     * A versão de preço foi corrigida no lugar, sem virar versão nova.
     *
     * <p>Ela só chega aqui enquanto nenhuma execução a citou — o que garante
     * que nenhum evento de receita já publicado carrega o valor antigo.
     */
    void priceVersionCorrected(
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
