package com.projeto.cortex.financeiro.core;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceOntologyProjector {

    private static final String SOURCE = "CORTEX_FINANCEIRO";

    private final CortexOperationalMemoryService memory;

    public FinanceOntologyProjector(CortexOperationalMemoryService memory) {
        this.memory = memory;
    }

    @Transactional
    public long success(
            String entityType,
            String entityId,
            String entityName,
            String eventType,
            String obraId,
            FinanceAuditContext audit,
            Map<String, Object> previousState,
            Map<String, Object> newState,
            Map<String, String> relatedEntities
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        memory.registrarObjeto(
                entityType,
                entityId,
                null,
                entityName,
                eventType.contains("ARQUIV") ? "ARQUIVADO" : "ATIVO",
                SOURCE
        );
        List<Map<String, Object>> related = new ArrayList<>();
        if (obraId != null) {
            memory.registrarRelacaoAtiva(
                    entityType,
                    entityId,
                    "OBRA",
                    obraId,
                    "VINCULADO_A_OBRA",
                    SOURCE,
                    "Escopo financeiro autorizado."
            );
            related.add(Map.of("tipo", "OBRA", "id", obraId));
        }
        if (relatedEntities != null) {
            relatedEntities.forEach((type, id) -> {
                if (id == null || id.isBlank()) {
                    return;
                }
                memory.registrarRelacaoAtiva(
                        entityType,
                        entityId,
                        type,
                        id,
                        "RELACIONADO_A",
                        SOURCE,
                        "Relação financeira persistida pelo domínio."
                );
                related.add(Map.of("tipo", type, "id", id));
            });
        }
        return memory.registrarEventoAuditado(
                UUID.randomUUID().toString(),
                entityType,
                entityId,
                eventType,
                SOURCE,
                obraId,
                null,
                audit.actorId(),
                related,
                audit.origin(),
                "OFFLINE".equals(audit.origin()) ? "SYNCED" : "ONLINE",
                now,
                now,
                1,
                newState == null ? Map.of() : newState,
                audit.actorId(),
                audit.deviceId(),
                audit.correlationId(),
                null,
                previousState == null ? Map.of() : previousState,
                newState == null ? Map.of() : newState,
                "SUCESSO",
                null
        );
    }

    @Transactional
    public long successWithRelations(
            String entityType,
            String entityId,
            String entityName,
            String eventType,
            String obraId,
            FinanceAuditContext audit,
            Map<String, Object> previousState,
            Map<String, Object> newState,
            List<FinanceOntologyRelation> relations
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        memory.registrarObjeto(
                entityType,
                entityId,
                null,
                entityName,
                eventType.contains("ARQUIV") ? "ARQUIVADO" : "ATIVO",
                SOURCE
        );
        List<Map<String, Object>> related = new ArrayList<>();
        if (obraId != null) {
            memory.registrarRelacaoAtiva(
                    entityType,
                    entityId,
                    "OBRA",
                    obraId,
                    "VINCULADO_A_OBRA",
                    SOURCE,
                    "Escopo financeiro autorizado."
            );
            related.add(Map.of("tipo", "OBRA", "id", obraId));
        }
        if (relations != null) {
            relations.forEach(relation -> {
                if (relation == null
                        || relation.targetType() == null
                        || relation.targetType().isBlank()
                        || relation.targetId() == null
                        || relation.targetId().isBlank()
                        || relation.relationType() == null
                        || relation.relationType().isBlank()) {
                    return;
                }
                memory.registrarRelacaoAtiva(
                        entityType,
                        entityId,
                        relation.targetType(),
                        relation.targetId(),
                        relation.relationType(),
                        SOURCE,
                        relation.evidence()
                );
                related.add(Map.of(
                        "tipo", relation.targetType(),
                        "id", relation.targetId(),
                        "relacao", relation.relationType()
                ));
            });
        }
        return memory.registrarEventoAuditado(
                UUID.randomUUID().toString(),
                entityType,
                entityId,
                eventType,
                SOURCE,
                obraId,
                null,
                audit.actorId(),
                related,
                audit.origin(),
                "OFFLINE".equals(audit.origin()) ? "SYNCED" : "ONLINE",
                now,
                now,
                2,
                newState == null ? Map.of() : newState,
                audit.actorId(),
                audit.deviceId(),
                audit.correlationId(),
                null,
                previousState == null ? Map.of() : previousState,
                newState == null ? Map.of() : newState,
                "SUCESSO",
                null
        );
    }

    @Transactional
    public void endRelation(
            String entityType,
            String entityId,
            String targetType,
            String targetId,
            String relationType
    ) {
        memory.encerrarRelacaoAtiva(
                entityType,
                entityId,
                targetType,
                targetId,
                relationType
        );
    }
}
