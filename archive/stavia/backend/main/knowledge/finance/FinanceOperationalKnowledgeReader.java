package com.projeto.cortex.intelligence.stavia.knowledge.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Typed, worksite-scoped projection of normalized finance tables. */
public interface FinanceOperationalKnowledgeReader {

    List<OverdueInvoice> overdueInvoices(String worksiteId, LocalDate asOf);

    List<PurchaseHistory> purchaseHistory(
            String worksiteId,
            String purchaseReference
    );

    List<PurchasedTotal> purchasedTotal(
            String worksiteId,
            LocalDate startInclusive,
            LocalDate endExclusive
    );

    List<PendingSupplierCharge> suppliersWithPendingCharges(
            String worksiteId
    );

    record OverdueInvoice(
            String invoiceId,
            String supplierId,
            String supplierName,
            String number,
            String series,
            String currency,
            BigDecimal openAmount,
            LocalDate dueDate,
            Instant freshnessAt
    ) {
    }

    record PurchaseHistory(
            String purchaseId,
            String orderNumber,
            String description,
            String createdById,
            String createdByName,
            Instant createdAt,
            String historyId,
            String previousStatus,
            String newStatus,
            String actorId,
            String actorName,
            String origin,
            String result,
            Instant occurredAt,
            Instant freshnessAt
    ) {
    }

    record PurchasedTotal(
            String currency,
            BigDecimal total,
            int purchaseCount,
            Instant freshnessAt
    ) {
    }

    record PendingSupplierCharge(
            String supplierId,
            String supplierName,
            long pendingCount,
            Instant firstExpectedAt,
            Instant freshnessAt
    ) {
    }
}
