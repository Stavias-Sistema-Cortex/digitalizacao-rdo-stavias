package com.projeto.cortex.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies the task aggregate transported by canonical v13 mutations.
 *
 * <p>The envelope owns actor, entity and worksite identity. The payload is a
 * complete task snapshot, while queue/version metadata remains client-local.
 */
@Component
public class TarefaSyncOperationHandler implements SyncOperationHandler {

    private static final Set<String> OPERATIONS = Set.of(
            "CRIAR_TAREFA",
            "ATUALIZAR_TAREFA",
            "CONCLUIR_TAREFA",
            "REABRIR_TAREFA",
            "EXCLUIR_TAREFA"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CortexOperationalMemoryService memoryService;
    private final CurrentUserService currentUserService;

    public TarefaSyncOperationHandler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CortexOperationalMemoryService memoryService,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.currentUserService = currentUserService;
    }

    @Override
    public String entityType() {
        return "TAREFA";
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return !"CRIAR_TAREFA".equals(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        ObjectNode payload = requireEnvelopeAndPayload(mutation, context);
        String taskId = canonicalUuid(mutation.entityId(), "entityId");
        String worksiteId = canonicalUuid(mutation.obraId(), "obraId");
        Instant occurredAt = canonicalInstant(
                mutation.occurredAt(),
                "occurredAt"
        );
        requirePayloadUuid(payload, "id", taskId);
        requirePayloadUuid(payload, "obraId", worksiteId);
        requireSameInstant(payload, "updatedAt", occurredAt);
        currentUserService.requireWorksiteAccess(worksiteId);

        String eventType = switch (mutation.operacao()) {
            case "CRIAR_TAREFA" -> {
                createTask(mutation, context, payload, taskId, worksiteId, occurredAt);
                yield "TAREFA_CRIADA";
            }
            case "ATUALIZAR_TAREFA" -> {
                updateTask(context, payload, taskId, worksiteId, occurredAt);
                yield "TAREFA_ATUALIZADA";
            }
            case "CONCLUIR_TAREFA" -> {
                completeTask(context, payload, taskId, worksiteId, occurredAt);
                yield "TAREFA_CONCLUIDA";
            }
            case "REABRIR_TAREFA" -> {
                reopenTask(context, payload, taskId, worksiteId, occurredAt);
                yield "TAREFA_REABERTA";
            }
            case "EXCLUIR_TAREFA" -> {
                deleteTask(context, payload, taskId, worksiteId, occurredAt);
                yield "TAREFA_EXCLUIDA";
            }
            default -> throw badRequest("Operação de tarefa não suportada.");
        };

        ObjectNode taskSnapshot = canonicalTaskSnapshot(payload);
        memoryService.registrarEvento(
                entityType(),
                taskId,
                eventType,
                "SYNC",
                worksiteId,
                eventPayload(taskSnapshot)
        );
        return new AppliedSyncMutation(
                entityType(),
                taskId,
                taskSnapshot
        );
    }

    private void createTask(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context,
            ObjectNode payload,
            String taskId,
            String worksiteId,
            Instant occurredAt
    ) {
        if (booleanValue(payload, "concluida")
                || !nullOrMissing(payload.get("concluidaEm"))
                || !nullOrMissing(payload.get("deletadaEm"))) {
            throw badRequest("Uma tarefa nova deve estar aberta e ativa.");
        }
        requireSameInstant(payload, "createdAt", occurredAt);
        requirePayloadUuid(
                payload,
                "criadaPorColaboradorId",
                canonicalUuid(context.actorId(), "actorId")
        );

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO tarefa (
                    id, obra_id, equipe, titulo, observacoes,
                    criada_por, criada_por_colaborador_id,
                    responsavel_equipe, responsavel_colaborador_id,
                    prioridade, concluida, concluida_em,
                    criada_em, atualizada_em, deletada_em,
                    criada_mutacao_id, atualizada_por_colaborador_id
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL,
                    ?, ?, NULL, ?, ?
                )
                """,
                taskId,
                worksiteId,
                boundedText(payload, "equipe", 160, true),
                boundedText(payload, "titulo", 255, true),
                boundedText(payload, "observacoes", 20_000, false),
                boundedText(payload, "criadaPor", 255, true),
                context.actorId(),
                boundedText(payload, "responsavelEquipe", 255, false),
                optionalUuid(payload, "responsavelColaboradorId"),
                priority(payload),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                canonicalUuid(
                        mutation.clientMutationId(),
                        "clientMutationId"
                ),
                context.actorId()
        );
        requireAppliedState(inserted);
    }

    private void completeTask(
            SyncMutationContext context,
            ObjectNode payload,
            String taskId,
            String worksiteId,
            Instant occurredAt
    ) {
        if (!booleanValue(payload, "concluida")
                || !sameInstant(payload.get("concluidaEm"), occurredAt)
                || !nullOrMissing(payload.get("deletadaEm"))) {
            throw badRequest(
                    "O snapshot de conclusão da tarefa é incoerente."
            );
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE tarefa
                SET concluida = TRUE,
                    concluida_em = ?,
                    atualizada_em = ?,
                    atualizada_por_colaborador_id = ?
                WHERE id = ?
                  AND obra_id = ?
                  AND deletada_em IS NULL
                  AND concluida = FALSE
                  AND equipe = ?
                  AND titulo = ?
                  AND observacoes = ?
                  AND criada_por = ?
                  AND criada_por_colaborador_id = ?
                  AND responsavel_equipe = ?
                  AND responsavel_colaborador_id IS NOT DISTINCT FROM ?
                  AND prioridade = ?
                  AND criada_em = ?
                """,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                context.actorId(),
                taskId,
                worksiteId,
                boundedText(payload, "equipe", 160, true),
                boundedText(payload, "titulo", 255, true),
                boundedText(payload, "observacoes", 20_000, false),
                boundedText(payload, "criadaPor", 255, true),
                requiredPayloadUuid(payload, "criadaPorColaboradorId"),
                boundedText(payload, "responsavelEquipe", 255, false),
                optionalUuid(payload, "responsavelColaboradorId"),
                priority(payload),
                requiredTimestamp(payload, "createdAt")
        );
        requireAppliedState(updated);
    }

    private void updateTask(
            SyncMutationContext context,
            ObjectNode payload,
            String taskId,
            String worksiteId,
            Instant occurredAt
    ) {
        if (!nullOrMissing(payload.get("deletadaEm"))) {
            throw badRequest(
                    "Uma atualização de campos não pode excluir a tarefa."
            );
        }
        boolean completed = booleanValue(payload, "concluida");
        Timestamp completedAt = optionalInstant(payload, "concluidaEm");
        if (completed != (completedAt != null)) {
            throw badRequest(
                    "O estado de conclusão da tarefa é incoerente."
            );
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE tarefa
                SET equipe = ?,
                    titulo = ?,
                    observacoes = ?,
                    responsavel_equipe = ?,
                    responsavel_colaborador_id = ?,
                    prioridade = ?,
                    atualizada_em = ?,
                    atualizada_por_colaborador_id = ?
                WHERE id = ?
                  AND obra_id = ?
                  AND deletada_em IS NULL
                  AND criada_por = ?
                  AND criada_por_colaborador_id = ?
                  AND criada_em = ?
                  AND concluida = ?
                  AND concluida_em IS NOT DISTINCT FROM ?
                """,
                boundedText(payload, "equipe", 160, true),
                boundedText(payload, "titulo", 255, true),
                boundedText(payload, "observacoes", 20_000, false),
                boundedText(payload, "responsavelEquipe", 255, false),
                optionalUuid(payload, "responsavelColaboradorId"),
                priority(payload),
                Timestamp.from(occurredAt),
                context.actorId(),
                taskId,
                worksiteId,
                boundedText(payload, "criadaPor", 255, true),
                requiredPayloadUuid(payload, "criadaPorColaboradorId"),
                requiredTimestamp(payload, "createdAt"),
                completed,
                completedAt
        );
        requireAppliedState(updated);
    }

    private void reopenTask(
            SyncMutationContext context,
            ObjectNode payload,
            String taskId,
            String worksiteId,
            Instant occurredAt
    ) {
        if (booleanValue(payload, "concluida")
                || !nullOrMissing(payload.get("concluidaEm"))
                || !nullOrMissing(payload.get("deletadaEm"))) {
            throw badRequest(
                    "O snapshot de reabertura da tarefa é incoerente."
            );
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE tarefa
                SET concluida = FALSE,
                    concluida_em = NULL,
                    atualizada_em = ?,
                    atualizada_por_colaborador_id = ?
                WHERE id = ?
                  AND obra_id = ?
                  AND deletada_em IS NULL
                  AND concluida = TRUE
                  AND equipe = ?
                  AND titulo = ?
                  AND observacoes = ?
                  AND criada_por = ?
                  AND criada_por_colaborador_id = ?
                  AND responsavel_equipe = ?
                  AND responsavel_colaborador_id IS NOT DISTINCT FROM ?
                  AND prioridade = ?
                  AND criada_em = ?
                """,
                Timestamp.from(occurredAt),
                context.actorId(),
                taskId,
                worksiteId,
                boundedText(payload, "equipe", 160, true),
                boundedText(payload, "titulo", 255, true),
                boundedText(payload, "observacoes", 20_000, false),
                boundedText(payload, "criadaPor", 255, true),
                requiredPayloadUuid(payload, "criadaPorColaboradorId"),
                boundedText(payload, "responsavelEquipe", 255, false),
                optionalUuid(payload, "responsavelColaboradorId"),
                priority(payload),
                requiredTimestamp(payload, "createdAt")
        );
        requireAppliedState(updated);
    }

    private void deleteTask(
            SyncMutationContext context,
            ObjectNode payload,
            String taskId,
            String worksiteId,
            Instant occurredAt
    ) {
        boolean completed = booleanValue(payload, "concluida");
        Timestamp completedAt = optionalInstant(payload, "concluidaEm");
        if (!sameInstant(payload.get("deletadaEm"), occurredAt)
                || completed != (completedAt != null)) {
            throw badRequest("O tombstone da tarefa é incoerente.");
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE tarefa
                SET deletada_em = ?,
                    atualizada_em = ?,
                    atualizada_por_colaborador_id = ?
                WHERE id = ?
                  AND obra_id = ?
                  AND deletada_em IS NULL
                  AND equipe = ?
                  AND titulo = ?
                  AND observacoes = ?
                  AND criada_por = ?
                  AND criada_por_colaborador_id = ?
                  AND responsavel_equipe = ?
                  AND responsavel_colaborador_id IS NOT DISTINCT FROM ?
                  AND prioridade = ?
                  AND criada_em = ?
                  AND concluida = ?
                  AND concluida_em IS NOT DISTINCT FROM ?
                """,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                context.actorId(),
                taskId,
                worksiteId,
                boundedText(payload, "equipe", 160, true),
                boundedText(payload, "titulo", 255, true),
                boundedText(payload, "observacoes", 20_000, false),
                boundedText(payload, "criadaPor", 255, true),
                requiredPayloadUuid(payload, "criadaPorColaboradorId"),
                boundedText(payload, "responsavelEquipe", 255, false),
                optionalUuid(payload, "responsavelColaboradorId"),
                priority(payload),
                requiredTimestamp(payload, "createdAt"),
                completed,
                completedAt
        );
        requireAppliedState(updated);
    }

    private ObjectNode requireEnvelopeAndPayload(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        if (mutation == null
                || mutation.schemaVersion() == null
                || mutation.schemaVersion() != 13
                || !entityType().equals(mutation.entidadeTipo())
                || !entityType().equals(mutation.entityType())
                || !OPERATIONS.contains(mutation.operacao())
                || mutation.entityId() == null
                || !mutation.entityId().equals(mutation.entidadeId())
                || context == null
                || context.actorId() == null
                || mutation.userId() == null
                || !mutation.userId().equals(context.actorId())) {
            throw badRequest("Envelope canônico de tarefa é inválido.");
        }
        String expectedCanonical = switch (mutation.operacao()) {
            case "CRIAR_TAREFA" -> "CREATE";
            case "ATUALIZAR_TAREFA" -> "UPDATE";
            case "EXCLUIR_TAREFA" -> "DELETE";
            default -> "TRANSITION";
        };
        if (!expectedCanonical.equals(mutation.operation())) {
            throw badRequest("Operação canônica de tarefa é incoerente.");
        }
        if (!(mutation.payload() instanceof ObjectNode payload)) {
            throw badRequest("payload de tarefa deve ser um objeto.");
        }
        return payload;
    }

    private void requirePayloadUuid(
            ObjectNode payload,
            String field,
            String expected
    ) {
        String value = canonicalUuid(text(payload, field, true), "payload." + field);
        if (!expected.equals(value)) {
            throw badRequest("payload." + field + " diverge do envelope.");
        }
    }

    private String optionalUuid(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (nullOrMissing(value)) {
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest("payload." + field + " deve ser UUID ou nulo.");
        }
        return canonicalUuid(value.textValue(), "payload." + field);
    }

    private String requiredPayloadUuid(ObjectNode payload, String field) {
        return canonicalUuid(
                text(payload, field, true),
                "payload." + field
        );
    }

    private int priority(ObjectNode payload) {
        JsonNode value = payload.get("prioridade");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw badRequest("payload.prioridade deve ser 1, 2 ou 3.");
        }
        int priority = value.intValue();
        if (priority < 1 || priority > 3) {
            throw badRequest("payload.prioridade deve ser 1, 2 ou 3.");
        }
        return priority;
    }

    private boolean booleanValue(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isBoolean()) {
            throw badRequest("payload." + field + " deve ser booleano.");
        }
        return value.booleanValue();
    }

    private Timestamp optionalInstant(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (nullOrMissing(value)) {
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest("payload." + field + " deve ser instante ou nulo.");
        }
        return Timestamp.from(canonicalInstant(
                value.textValue(),
                "payload." + field
        ));
    }

    private Timestamp requiredTimestamp(ObjectNode payload, String field) {
        return Timestamp.from(canonicalInstant(
                text(payload, field, true),
                "payload." + field
        ));
    }

    private String boundedText(
            ObjectNode payload,
            String field,
            int maxLength,
            boolean nonblank
    ) {
        String value = text(payload, field, true);
        if ((nonblank && value.isBlank()) || value.length() > maxLength) {
            throw badRequest("payload." + field + " é inválido.");
        }
        return value.strip();
    }

    private String text(
            ObjectNode payload,
            String field,
            boolean required
    ) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw badRequest("payload." + field + " é obrigatório.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest("payload." + field + " deve ser textual.");
        }
        return value.textValue();
    }

    private void requireSameInstant(
            ObjectNode payload,
            String field,
            Instant expected
    ) {
        if (!sameInstant(payload.get(field), expected)) {
            throw badRequest(
                    "payload." + field + " diverge de occurredAt."
            );
        }
    }

    private boolean sameInstant(JsonNode value, Instant expected) {
        if (value == null || !value.isTextual()) {
            return false;
        }
        try {
            return Instant.parse(value.textValue()).equals(expected);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private Instant canonicalInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw badRequest(field + " deve ser um instante UTC válido.");
        }
    }

    private String canonicalUuid(String value, String field) {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equals(value)) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw badRequest(field + " deve ser UUID canônico.");
        }
    }

    private boolean nullOrMissing(JsonNode value) {
        return value == null || value.isNull();
    }

    private void requireAppliedState(int affectedRows) {
        if (affectedRows != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A tarefa não existe ou seu estado atual não permite a operação."
            );
        }
    }

    private Map<String, Object> eventPayload(ObjectNode payload) {
        return objectMapper.convertValue(
                payload,
                new TypeReference<LinkedHashMap<String, Object>>() {
                }
        );
    }

    private ObjectNode canonicalTaskSnapshot(ObjectNode payload) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        for (String field : Set.of(
                "id",
                "obraId",
                "equipe",
                "titulo",
                "observacoes",
                "criadaPor",
                "criadaPorColaboradorId",
                "responsavelEquipe",
                "responsavelColaboradorId",
                "prioridade",
                "concluida",
                "concluidaEm",
                "createdAt",
                "updatedAt",
                "deletadaEm"
        )) {
            snapshot.set(field, payload.get(field));
        }
        return snapshot;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
