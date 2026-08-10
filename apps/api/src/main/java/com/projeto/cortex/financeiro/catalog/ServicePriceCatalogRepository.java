package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface ServicePriceCatalogRepository {

    boolean worksiteExists(String obraId);

    Optional<CatalogMutation> findMutation(String actorId, String clientMutationId);

    Optional<ServiceCatalogEntry> findService(String serviceId);

    Optional<ServicePriceVersion> findPrice(String obraId, String priceId);

    ServiceCatalogEntry createService(CreateServiceRecord record);

    /**
     * Reescreve código, nome e descrição do serviço, gravando o recibo.
     *
     * <p>O identificador não entra na correção: ele é o endereço, e os RDOs,
     * as versões de preço e as medições já o citam.
     */
    ServiceCatalogEntry updateService(UpdateServiceRecord record);

    /**
     * Move o serviço entre ativo e excluído, gravando o recibo da operação.
     *
     * <p>Uma transição só: excluir e restaurar são o mesmo movimento em
     * sentidos opostos, e separá-los em dois métodos duplicaria o recibo, o
     * carimbo de revisão e a checagem de estado — três lugares para divergir.
     */
    ServiceCatalogEntry updateServiceExclusion(ServiceExclusionRecord record);

    ServicePriceVersion createPrice(CreatePriceRecord record);

    /**
     * Corrige valor, quantidade, vigência e fonte da versão, no lugar.
     *
     * <p>Quem decide se a correção ainda é possível é o banco: o gatilho recusa
     * a escrita quando alguma execução já citou o preço ou quando ele já foi
     * substituído ou cancelado. A regra mora lá porque é lá que a corrida entre
     * dois aparelhos se resolve.
     */
    ServicePriceVersion updatePrice(UpdatePriceRecord record);

    ServicePriceVersion supersedePrice(CreatePriceRecord record);

    ServicePriceVersion cancelPrice(CancelPriceRecord record);

    ServiceCatalogPage list(String obraId, String query, String cursor, int limit);

    record ServiceExclusionRecord(
            String serviceId,
            String actorId,
            String clientMutationId,
            String requestHash,
            boolean excluded,
            java.time.Instant occurredAt
    ) {
    }

    record CreateServiceRecord(
            String id,
            String worksiteId,
            String actorId,
            String clientMutationId,
            String requestHash,
            String code,
            String name,
            String description,
            Instant createdAt
    ) {
    }

    record UpdateServiceRecord(
            String serviceId,
            String actorId,
            String clientMutationId,
            String requestHash,
            String code,
            String name,
            String description,
            Instant occurredAt
    ) {
    }

    record UpdatePriceRecord(
            String id,
            String obraId,
            String actorId,
            String clientMutationId,
            String requestHash,
            BigDecimal unitPrice,
            BigDecimal contractedQuantity,
            LocalDate validFrom,
            LocalDate validTo,
            String source,
            Instant occurredAt
    ) {
    }

    record CreatePriceRecord(
            String id,
            String obraId,
            String serviceId,
            String actorId,
            String clientMutationId,
            String requestHash,
            String unit,
            String currency,
            BigDecimal unitPrice,
            BigDecimal contractedQuantity,
            LocalDate validFrom,
            LocalDate validTo,
            String source,
            String supersedesId,
            Instant createdAt
    ) {
    }

    record CancelPriceRecord(
            String id,
            String obraId,
            String priceId,
            String actorId,
            String clientMutationId,
            String requestHash,
            LocalDate effectiveAt,
            String reason,
            Instant createdAt
    ) {
    }
}
