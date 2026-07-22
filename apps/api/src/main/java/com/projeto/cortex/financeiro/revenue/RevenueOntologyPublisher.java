package com.projeto.cortex.financeiro.revenue;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RevenueOntologyPublisher {

    private static final String SOURCE = "CORTEX_FINANCEIRO";

    private final CortexOperationalMemoryService memory;

    public RevenueOntologyPublisher(CortexOperationalMemoryService memory) {
        this.memory = memory;
    }

    public void publishAccepted(RevenueEvidence evidence) {
        memory.registrarObjeto(
                "SERVICE", evidence.serviceId(), evidence.serviceCode(),
                evidence.serviceName(), "ACTIVE", SOURCE,
                "catalogo_servico", Map.of()
        );
        memory.registrarObjeto(
                "SERVICE_PRICE_VERSION", evidence.priceVersionId(), null,
                evidence.serviceCode() + " · " + evidence.unit() + " · "
                        + evidence.currency() + " · v" + evidence.priceVersion(),
                "ACTIVE", SOURCE, "service_price_version", Map.of()
        );
        memory.registrarObjeto(
                "RDO_SERVICE_EXECUTED", evidence.executionId(), evidence.executionId(),
                evidence.serviceName(), "ACCEPTED", SOURCE,
                "execucao_servico_rdo", Map.of(
                        "rdoId", evidence.rdoId(),
                        "obraId", evidence.worksiteId()
                )
        );
        memory.registrarObjeto(
                "REVENUE_EVIDENCE", evidence.evidenceId(), evidence.evidenceId(),
                "Revenue evidence " + evidence.executionId(), "ACCEPTED", SOURCE,
                "execucao_servico_rdo", Map.of(
                        "executionId", evidence.executionId(),
                        "rdoId", evidence.rdoId(),
                        "obraId", evidence.worksiteId()
                )
        );
        relation(
                "RDO_SERVICE_EXECUTED", evidence.executionId(),
                "SERVICE_PRICE_VERSION", evidence.priceVersionId(),
                "PRICED_BY"
        );
        relation(
                "RDO_SERVICE_EXECUTED", evidence.executionId(),
                "RDO", evidence.rdoId(), "EXECUTED_IN"
        );
        relation(
                "RDO_SERVICE_EXECUTED", evidence.executionId(),
                "SERVICE", evidence.serviceId(), "EXECUTES_SERVICE"
        );
        relation(
                "RDO_SERVICE_EXECUTED", evidence.executionId(),
                "REVENUE_EVIDENCE", evidence.evidenceId(), "GENERATES_REVENUE"
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("rdoId", evidence.rdoId());
        payload.put("obraId", evidence.worksiteId());
        payload.put("serviceId", evidence.serviceId());
        payload.put("serviceName", evidence.serviceName());
        payload.put("priceVersionId", evidence.priceVersionId());
        payload.put("acceptedQuantity", evidence.acceptedQuantity().toPlainString());
        payload.put("unit", evidence.unit());
        payload.put("currency", evidence.currency());
        payload.put("unitPrice", evidence.unitPriceSnapshot().toPlainString());
        payload.put("revenue", evidence.revenueAmount().toPlainString());
        payload.put("revenueEvidenceId", evidence.evidenceId());
        payload.put("status", "ACCEPTED");
        LocalDateTime occurredAt = LocalDateTime.ofInstant(
                evidence.acceptedAt(), ZoneOffset.UTC
        );
        memory.registrarEventoAuditado(
                evidence.eventId(),
                "RDO_EXECUTION",
                evidence.executionId(),
                "RDO_SERVICE_EXECUTED",
                SOURCE,
                evidence.worksiteId(),
                evidence.rdoId(),
                null,
                List.of(
                        Map.of("tipo", "RDO", "id", evidence.rdoId()),
                        Map.of("tipo", "WORKSITE", "id", evidence.worksiteId()),
                        Map.of("tipo", "SERVICE", "id", evidence.serviceId()),
                        Map.of(
                                "tipo", "SERVICE_PRICE_VERSION",
                                "id", evidence.priceVersionId()
                        ),
                        Map.of(
                                "tipo", "REVENUE_EVIDENCE",
                                "id", evidence.evidenceId()
                        )
                ),
                "ONLINE", "SYNCED", occurredAt, occurredAt, 1, payload,
                null, null, evidence.executionId(), null,
                Map.of(), Map.of(
                        "status", "ACCEPTED",
                        "revenueEvidenceId", evidence.evidenceId()
                ), "SUCESSO", null
        );
    }

    private void relation(
            String sourceType,
            String sourceId,
            String targetType,
            String targetId,
            String relationType
    ) {
        memory.registrarRelacaoAtiva(
                sourceType, sourceId, targetType, targetId,
                relationType, SOURCE, relationType
        );
    }
}
