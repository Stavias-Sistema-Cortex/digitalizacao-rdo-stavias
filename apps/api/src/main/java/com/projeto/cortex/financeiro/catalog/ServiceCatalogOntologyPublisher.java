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

    /**
     * O serviço saiu do catálogo — ou voltou para ele.
     *
     * <p>Publicar isto não é registro por registro: o valor contratual da obra
     * conta apenas serviço vigente, e é a observação na ontologia que acorda o
     * PDOR para recalcular. Enquanto a exclusão era só um {@code UPDATE} de
     * status, ela mudava o banco e não avisava ninguém: nenhum snapshot novo
     * era publicado, e o teto do contrato calculado com o serviço ainda vivo
     * seguia na tela do Financeiro sem nada explicando por quê. Restaurar tinha
     * o mesmo buraco no sentido contrário.
     */
    void serviceExclusionChanged(
            ServiceCatalogEntry service,
            String obraId,
            boolean excluded,
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
