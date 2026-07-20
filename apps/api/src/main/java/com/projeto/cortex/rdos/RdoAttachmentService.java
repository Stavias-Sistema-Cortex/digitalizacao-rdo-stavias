package com.projeto.cortex.rdos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RdoAttachmentService {

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

        Set<String> incomingIds = new HashSet<>();

        for (RdoCreateRequest.AttachmentItem attachment : attachments) {
            if (attachment == null || isBlank(attachment.id())) {
                continue;
            }

            incomingIds.add(attachment.id().trim());

            jdbcTemplate.update(
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
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        obra_id = VALUES(obra_id),
                        tipo = VALUES(tipo),
                        nome = VALUES(nome),
                        nome_original = VALUES(nome_original),
                        mime_type = VALUES(mime_type),
                        tamanho_original_bytes = VALUES(tamanho_original_bytes),
                        tamanho_comprimido_bytes = VALUES(tamanho_comprimido_bytes),
                        tamanho_bytes = VALUES(tamanho_bytes),
                        storage_ref = VALUES(storage_ref),
                        sync_status = VALUES(sync_status),
                        metadata_json = VALUES(metadata_json),
                        atualizado_em = VALUES(atualizado_em),
                        removido_em = VALUES(removido_em)
                    """,
                    attachment.id().trim(),
                    rdoId,
                    firstNonBlank(attachment.obraId(), obraId),
                    firstNonBlank(attachment.tipo(), "FOTO"),
                    firstNonBlank(attachment.nome(), attachment.id()),
                    blankToNull(attachment.nomeOriginal()),
                    firstNonBlank(attachment.mimeType(), "image/jpeg"),
                    longValue(attachment.tamanhoOriginalBytes()),
                    longValue(attachment.tamanhoComprimidoBytes()),
                    longValue(attachment.tamanhoBytes()),
                    "indexeddb:" + attachment.id().trim(),
                    normalizeSyncStatus(attachment.syncStatus()),
                    toJson(attachment.metadata()),
                    coalesce(attachment.createdAt(), LocalDateTime.now()),
                    coalesce(attachment.updatedAt(), LocalDateTime.now()),
                    attachment.removedAt()
            );
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
                    rdoId
            );
        } else {
            String placeholders = String.join(
                    ",",
                    incomingIds.stream().map(ignored -> "?").toList()
            );
            Object[] params = new Object[incomingIds.size() + 1];
            params[0] = rdoId;
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
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Metadados de foto inválidos.", exception);
        }
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

    private Long longValue(Long value) {
        return value == null ? 0L : value;
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

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
