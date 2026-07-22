package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface ServicePriceCatalogRepository {

    Optional<CatalogMutation> findMutation(String actorId, String clientMutationId);

    Optional<ServiceCatalogEntry> findService(String serviceId);

    Optional<ServicePriceVersion> findPrice(String obraId, String priceId);

    ServiceCatalogEntry createService(CreateServiceRecord record);

    ServicePriceVersion createPrice(CreatePriceRecord record);

    ServicePriceVersion supersedePrice(CreatePriceRecord record);

    ServicePriceVersion cancelPrice(CancelPriceRecord record);

    ServiceCatalogPage list(String obraId, String query, String cursor, int limit);

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
