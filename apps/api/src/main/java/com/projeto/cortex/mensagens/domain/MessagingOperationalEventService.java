package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessagingOperationalEventService {

    private static final String SOURCE = "CORTEX_MENSAGENS";

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;

    public MessagingOperationalEventService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
    }

    @Transactional
    public long recordSuccess(
            String entityType,
            String entityId,
            String eventType,
            ConversationScope scope,
            MessagingAuditContext audit,
            Map<String, Object> previousState,
            Map<String, Object> newState
    ) {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversaId", scope.id());
        payload.put("tipoConversa", scope.type().name());
        if (newState != null) {
            payload.putAll(newState);
        }

        memoryService.registrarObjeto(
                entityType,
                entityId,
                null,
                entityType + " " + entityId,
                eventType.contains("EXCLUID") ? "EXCLUIDO" : "ATIVO",
                SOURCE
        );
        if (!"CONVERSA".equals(entityType)) {
            memoryService.registrarRelacaoAtiva(
                    entityType,
                    entityId,
                    "CONVERSA",
                    scope.id(),
                    "PERTENCE_A_CONVERSA",
                    SOURCE,
                    "Relação criada pelo domínio de Mensagens."
            );
        }
        if (scope.obraId() != null) {
            memoryService.registrarRelacaoAtiva(
                    "CONVERSA",
                    scope.id(),
                    "OBRA",
                    scope.obraId(),
                    "VINCULADA_A_OBRA",
                    SOURCE,
                    "Escopo operacional da conversa."
            );
        }
        memoryService.registrarRelacaoAtiva(
                "PESSOA",
                audit.actorId(),
                entityType,
                entityId,
                actorRelation(entityType, eventType),
                SOURCE,
                "Autoria autenticada da mutação de mensagens."
        );

        long commitSeq = memoryService.registrarEventoAuditado(
                eventId,
                entityType,
                entityId,
                eventType,
                SOURCE,
                scope.obraId(),
                null,
                audit.actorId(),
                relatedEntities(scope),
                audit.origin(),
                "OFFLINE".equals(audit.origin()) ? "SYNCED" : "ONLINE",
                now,
                now,
                1,
                payload,
                audit.actorId(),
                audit.deviceId(),
                audit.correlationId(),
                null,
                previousState == null ? Map.of() : previousState,
                newState == null ? Map.of() : newState,
                "SUCESSO",
                null
        );

        jdbcTemplate.update(
                """
                INSERT INTO cortex_evento_visibilidade (
                    id, evento_id, escopo_tipo, escopo_id
                ) VALUES (?, ?, 'CONVERSATION_PARTICIPANT', ?)
                """,
                UUID.randomUUID().toString(),
                eventId,
                scope.id()
        );
        return commitSeq;
    }

    private String actorRelation(String entityType, String eventType) {
        if ("MENSAGEM".equals(entityType)
                && eventType != null
                && eventType.contains("ENVIAD")) {
            return "ENVIOU";
        }
        if ("MENSAGEM_ANEXO".equals(entityType)) {
            return "ANEXOU";
        }
        if ("CONVERSA".equals(entityType)
                && eventType != null
                && eventType.contains("CRIAD")) {
            return "CRIOU";
        }
        return "ALTEROU";
    }

    private List<Map<String, Object>> relatedEntities(
            ConversationScope scope
    ) {
        Map<String, Object> conversation = Map.of(
                "tipo", "CONVERSA",
                "id", scope.id()
        );
        if (scope.obraId() == null) {
            return List.of(conversation);
        }
        return List.of(
                conversation,
                Map.of("tipo", "OBRA", "id", scope.obraId())
        );
    }
}
