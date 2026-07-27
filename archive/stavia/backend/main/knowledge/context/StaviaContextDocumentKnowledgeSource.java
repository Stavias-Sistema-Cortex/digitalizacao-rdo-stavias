package com.projeto.cortex.intelligence.stavia.knowledge.context;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class StaviaContextDocumentKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final int MAX_DOCUMENTS = 20;
    private static final int MAX_TEXT_SNIPPET = 1_500;

    private final JdbcTemplate jdbcTemplate;

    public StaviaContextDocumentKnowledgeSource(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return "contexto-da-obra";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-WORKSITE-CONTEXT-SOURCE-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(
                        QueryDomain.OBRA,
                        QueryDomain.RDO,
                        QueryDomain.ONTOLOGIA
                ),
                Set.of("CONTEXTO_OBRA"),
                Set.of(
                        "descricao",
                        "nomeArquivo",
                        "textoExtraido",
                        "contentType"
                ),
                Set.of("CONTEXTUALIZA"),
                Set.of(
                        QueryOperation.READ_ATTRIBUTE,
                        QueryOperation.LIST_OBJECTS,
                        QueryOperation.SUMMARIZE
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Contextos anexados localmente à obra",
                20,
                MAX_DOCUMENTS
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null) {
            return false;
        }

        if (!request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)) {
            return false;
        }

        if (request.plan() != null
                && request.plan().requiredSources().contains(sourceName())) {
            return true;
        }

        String normalized =
                StaviaText.normalize(request.question().text());

        if (containsAny(
                normalized,
                "contexto",
                "anexo",
                "arquivo",
                "documento",
                "pdf",
                "imagem",
                "foto"
        )) {
            return true;
        }

        return request.intent() == StaviaIntent.RESUMIR_OBRA;
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    obra_id,
                    nome_arquivo,
                    content_type,
                    tamanho_bytes,
                    hash_sha256,
                    descricao,
                    texto_extraido,
                    status_processamento,
                    criado_em
                FROM stavia_contexto_obra
                WHERE obra_id = ?
                  AND ativo = 1
                ORDER BY criado_em DESC, id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Timestamp createdAt =
                            rs.getTimestamp("criado_em");
                    Map<String, Object> attributes =
                            new LinkedHashMap<>();

                    put(attributes, "obraId", rs.getString("obra_id"));
                    put(attributes, "nomeArquivo", rs.getString("nome_arquivo"));
                    put(attributes, "contentType", rs.getString("content_type"));
                    attributes.put("tamanhoBytes", rs.getLong("tamanho_bytes"));
                    put(attributes, "hashSha256", rs.getString("hash_sha256"));
                    put(attributes, "descricao", rs.getString("descricao"));
                    put(attributes, "textoExtraido", trimText(rs.getString("texto_extraido")));
                    put(attributes, "statusProcessamento", rs.getString("status_processamento"));
                    put(attributes, "fonte", sourceName());

                    return new StaviaEvidence(
                            StaviaEvidenceTypes.CONTEXTO_OBRA,
                            rs.getString("id"),
                            summary(attributes),
                            createdAt == null
                                    ? null
                                    : Instant.ofEpochMilli(
                                            createdAt.getTime()
                                    ),
                            true,
                            attributes
                    );
                },
                request.worksiteId(),
                MAX_DOCUMENTS
        );
    }

    private String summary(Map<String, Object> attributes) {
        String filename = text(attributes, "nomeArquivo");
        String description = text(attributes, "descricao");
        String extractedText = text(attributes, "textoExtraido");

        StringBuilder summary =
                new StringBuilder("Contexto anexado");

        if (hasText(filename)) {
            summary.append(" ")
                    .append(filename);
        }

        if (hasText(description)) {
            summary.append(" (")
                    .append(description)
                    .append(")");
        }

        if (hasText(extractedText)) {
            summary.append(": ")
                    .append(extractedText);
        } else {
            summary.append(
                    ": arquivo armazenado localmente; texto ainda não extraído."
            );
        }

        return summary.toString();
    }

    private String trimText(String value) {
        if (!hasText(value)) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.length() > MAX_TEXT_SNIPPET
                ? trimmed.substring(0, MAX_TEXT_SNIPPET)
                : trimmed;
    }

    private void put(
            Map<String, Object> attributes,
            String key,
            Object value
    ) {
        if (value == null) {
            return;
        }

        if (value instanceof String text && text.isBlank()) {
            return;
        }

        attributes.put(key, value);
    }

    private String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
