package com.projeto.cortex.rdos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoAttachmentService {

    private static final int MAX_ATTACHMENTS = 5;
    private static final int MAX_METADATA_BYTES = 8_192;
    private static final int MAX_METADATA_DEPTH = 5;
    private static final int MAX_METADATA_ENTRIES = 64;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RdoAttachmentService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void substituirAttachments(
            String rdoId,
            String obraId,
            List<RdoCreateRequest.AttachmentItem> attachments
    ) {
        if (attachments == null) {
            return;
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw badRequest("attachments excede o limite permitido.");
        }

        String normalizedRdoId = requireReference(rdoId, "rdoId");
        String normalizedObraId = requireReference(obraId, "obraId");
        for (RdoCreateRequest.AttachmentItem attachment : attachments) {
            if (attachment == null) {
                throw badRequest("Anexo inválido.");
            }
            requireMatchingReference(
                    attachment.rdoId(), normalizedRdoId, "rdoId"
            );
            requireMatchingReference(
                    attachment.obraId(), normalizedObraId, "obraId"
            );
        }

        Set<String> incomingIds = new HashSet<>();

        for (RdoCreateRequest.AttachmentItem attachment : attachments) {
            if (isBlank(attachment.id())) {
                throw badRequest("attachment.id é obrigatório.");
            }

            String attachmentId = boundedText(
                    attachment.id(), "attachment.id", 128, null
            );
            if (!incomingIds.add(attachmentId)) {
                throw badRequest("attachment.id duplicado.");
            }
            String metadataJson = toJson(attachment.metadata());

            int changed = jdbcTemplate.update(
                    """
                    INSERT INTO rdo_attachment (
                        id,
                        rdo_id,
                        obra_id,
                        tipo,
                        nome,
                        nome_original,
                        mime_type,
                        tamanho_original_bytes,
                        tamanho_comprimido_bytes,
                        tamanho_bytes,
                        storage_ref,
                        sync_status,
                        metadata_json,
                        ultimo_erro,
                        criado_em,
                        atualizado_em,
                        removido_em
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, NULL, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        obra_id = EXCLUDED.obra_id,
                        tipo = EXCLUDED.tipo,
                        nome = EXCLUDED.nome,
                        nome_original = EXCLUDED.nome_original,
                        mime_type = EXCLUDED.mime_type,
                        tamanho_original_bytes = EXCLUDED.tamanho_original_bytes,
                        tamanho_comprimido_bytes = EXCLUDED.tamanho_comprimido_bytes,
                        tamanho_bytes = EXCLUDED.tamanho_bytes,
                        storage_ref = EXCLUDED.storage_ref,
                        sync_status = EXCLUDED.sync_status,
                        metadata_json = EXCLUDED.metadata_json,
                        atualizado_em = EXCLUDED.atualizado_em,
                        removido_em = EXCLUDED.removido_em
                    WHERE rdo_attachment.rdo_id = EXCLUDED.rdo_id
                      AND rdo_attachment.obra_id = EXCLUDED.obra_id
                    """,
                    attachmentId,
                    normalizedRdoId,
                    normalizedObraId,
                    boundedText(attachment.tipo(), "attachment.tipo", 40, "FOTO"),
                    boundedText(attachment.nome(), "attachment.nome", 255, attachmentId),
                    boundedOptionalText(attachment.nomeOriginal(), "attachment.nomeOriginal", 255),
                    boundedText(attachment.mimeType(), "attachment.mimeType", 120, "image/jpeg"),
                    boundedBytes(attachment.tamanhoOriginalBytes(), "attachment.tamanhoOriginalBytes"),
                    boundedBytes(attachment.tamanhoComprimidoBytes(), "attachment.tamanhoComprimidoBytes"),
                    boundedBytes(attachment.tamanhoBytes(), "attachment.tamanhoBytes"),
                    "indexeddb:" + attachmentId,
                    normalizeSyncStatus(attachment.syncStatus()),
                    metadataJson,
                    coalesce(attachment.createdAt(), LocalDateTime.now()),
                    coalesce(attachment.updatedAt(), LocalDateTime.now()),
                    attachment.removedAt()
            );
            if (changed != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O identificador do anexo pertence a outro RDO."
                );
            }
        }

        if (incomingIds.isEmpty()) {
            jdbcTemplate.update(
                    """
                    UPDATE rdo_attachment
                    SET
                        removido_em = COALESCE(removido_em, CURRENT_TIMESTAMP(6)),
                        sync_status = 'SYNCED'
                    WHERE rdo_id = ?
                      AND removido_em IS NULL
                    """,
                    normalizedRdoId
            );
        } else {
            String placeholders = String.join(
                    ",",
                    incomingIds.stream().map(ignored -> "?").toList()
            );
            Object[] params = new Object[incomingIds.size() + 1];
            params[0] = normalizedRdoId;
            int index = 1;
            for (String id : incomingIds) {
                params[index] = id;
                index++;
            }

            jdbcTemplate.update(
                    """
                    UPDATE rdo_attachment
                    SET
                        removido_em = COALESCE(removido_em, CURRENT_TIMESTAMP(6)),
                        sync_status = 'SYNCED'
                    WHERE rdo_id = ?
                      AND removido_em IS NULL
                      AND id NOT IN (%s)
                    """.formatted(placeholders),
                    params
            );
        }
    }

    public List<RdoResponse.AttachmentItem> listar(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    rdo_id,
                    obra_id,
                    tipo,
                    nome,
                    nome_original,
                    mime_type,
                    tamanho_original_bytes,
                    tamanho_comprimido_bytes,
                    tamanho_bytes,
                    sync_status,
                    criado_em,
                    atualizado_em,
                    removido_em,
                    metadata_json
                FROM rdo_attachment
                WHERE rdo_id = ?
                ORDER BY criado_em, id
                """,
                (rs, rowNum) -> new RdoResponse.AttachmentItem(
                        rs.getString("id"),
                        rs.getString("rdo_id"),
                        rs.getString("obra_id"),
                        rs.getString("tipo"),
                        rs.getString("nome"),
                        rs.getString("nome_original"),
                        rs.getString("mime_type"),
                        rs.getLong("tamanho_original_bytes"),
                        rs.getLong("tamanho_comprimido_bytes"),
                        rs.getLong("tamanho_bytes"),
                        rs.getString("sync_status"),
                        toLocalDateTime(rs.getTimestamp("criado_em")),
                        toLocalDateTime(rs.getTimestamp("atualizado_em")),
                        toLocalDateTime(rs.getTimestamp("removido_em")),
                        fromJson(rs.getString("metadata_json"))
                ),
                rdoId
        );
    }

    private String normalizeSyncStatus(String value) {
        if (isBlank(value)) {
            return "SYNCED";
        }

        return switch (value.trim()) {
            case "LOCAL_ONLY", "PENDING_SYNC", "SYNCING", "SYNCED", "SYNC_FAILED" -> value.trim();
            default -> "SYNCED";
        };
    }

    private String toJson(Map<String, Object> metadata) {
        Map<String, Object> value = metadata == null ? Map.of() : metadata;
        validateMetadata(value, 0);
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
                throw badRequest("Metadados de foto excedem o limite permitido.");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Metadados de foto inválidos.", exception);
        }
    }

    private void validateMetadata(Object value, int depth) {
        if (depth > MAX_METADATA_DEPTH) {
            throw badRequest("Metadados de foto excedem a profundidade permitida.");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() > 1_024) {
                throw badRequest("Metadados de foto contêm texto excessivo.");
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_METADATA_ENTRIES) {
                throw badRequest("Metadados de foto contêm itens demais.");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length() > 128) {
                    throw badRequest("Metadados de foto contêm chave inválida.");
                }
                validateMetadata(entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > MAX_METADATA_ENTRIES) {
                throw badRequest("Metadados de foto contêm itens demais.");
            }
            for (Object item : collection) {
                validateMetadata(item, depth + 1);
            }
            return;
        }
        throw badRequest("Metadados de foto contêm valor inválido.");
    }

    private Map<String, Object> fromJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(
                    metadataJson,
                    new TypeReference<>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Metadados de foto inválidos no banco.", exception);
        }
    }

    private Long boundedBytes(Long value, String field) {
        long normalized = value == null ? 0L : value;
        if (normalized < 0 || normalized > MAX_ATTACHMENT_BYTES) {
            throw badRequest(field + " excede o limite permitido.");
        }
        return normalized;
    }

    private LocalDateTime coalesce(
            LocalDateTime value,
            LocalDateTime fallback
    ) {
        return value == null ? fallback : value;
    }

    private LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String boundedText(
            String value,
            String field,
            int maxLength,
            String fallback
    ) {
        String normalized = firstNonBlank(value, fallback);
        if (normalized == null || normalized.isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(character -> character < 0x20)) {
            throw badRequest(field + " é inválido.");
        }
        return normalized;
    }

    private String boundedOptionalText(
            String value,
            String field,
            int maxLength
    ) {
        return isBlank(value) ? null : boundedText(value, field, maxLength, null);
    }

    private String requireReference(String value, String field) {
        if (isBlank(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " é obrigatório."
            );
        }
        return value.strip();
    }

    private void requireMatchingReference(
            String supplied,
            String expected,
            String field
    ) {
        if (!isBlank(supplied) && !expected.equals(supplied.strip())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Referência " + field + " do anexo é inválida."
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
