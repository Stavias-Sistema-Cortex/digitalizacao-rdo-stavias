package com.projeto.cortex.financeiro.catalog;

/**
 * Invalida projeções financeiras derivadas afetadas pela transição de um
 * serviço global do catálogo.
 */
public interface ServiceCatalogProjectionInvalidator {

    ServiceCatalogProjectionInvalidator NOOP =
            new ServiceCatalogProjectionInvalidator() {
                @Override
                public void invalidateService(String serviceId) {
                }

                @Override
                public void invalidateWorksite(String obraId) {
                }
            };

    void invalidateService(String serviceId);

    void invalidateWorksite(String obraId);
}
