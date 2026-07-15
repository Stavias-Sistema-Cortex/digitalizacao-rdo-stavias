package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class FiscalExtractionRepository {

    private static final String JOB_SELECT = """
            SELECT id, stored_object_id, obra_id, client_mutation_id,
                   formato, status, extrator, extrator_versao,
                   sha256_cliente, sha256_servidor,
                   autorizacao_fiscal_status, criado_por, dispositivo_id,
                   correlacao_id, criado_em, concluido_em
            FROM finance_documento_extracao_job
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FiscalExtractionRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<JobRecord> findByMutation(
            String actorId,
            String clientMutationId
    ) {
        return jdbc.query(
                JOB_SELECT + " WHERE criado_por = ? AND client_mutation_id = ?",
                this::optionalJob,
                actorId,
                clientMutationId
        );
    }

    public Optional<JobRecord> findById(String id) {
        return jdbc.query(
                JOB_SELECT + " WHERE id = ?",
                this::optionalJob,
                id
        );
    }

    public void insert(JobWrite write, FiscalExtractionResult result) {
        jdbc.update(
                """
                INSERT INTO finance_documento_extracao_job (
                    id, stored_object_id, obra_id, client_mutation_id,
                    formato, status, extrator, extrator_versao,
                    sha256_cliente, sha256_servidor, resultado_json,
                    avisos_json, autorizacao_fiscal_status, erro_sanitizado,
                    criado_por, dispositivo_id, correlacao_id,
                    iniciado_em, concluido_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                write.id(), write.storedObjectId(), write.obraId(),
                write.clientMutationId(), write.format(), result.status().name(),
                result.extractor(), result.extractorVersion(),
                write.clientSha256(), write.serverSha256(), json(result),
                json(result.warnings()), result.autorizacaoFiscal(),
                result.status() == ExtractionStatus.FALHA
                        && !result.warnings().isEmpty()
                        ? result.warnings().getFirst() : null,
                write.createdBy(), write.deviceId(), write.correlationId()
        );
        Map<String, Integer> fieldOrder = new HashMap<>();
        for (FiscalCandidate candidate : result.candidates()) {
            int order = fieldOrder.merge(candidate.field(), 1, Integer::sum) - 1;
            jdbc.update(
                    """
                    INSERT INTO finance_documento_extracao_candidato (
                        id, job_id, campo, ordem, valor_normalizado_json,
                        texto_original, extrator, extrator_versao,
                        localizacao, confianca, validacao_status,
                        validacao_detalhe
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), write.id(), candidate.field(),
                    order, candidate.normalizedValue().toString(),
                    candidate.originalText(), candidate.extractor(),
                    candidate.extractorVersion(), candidate.location(),
                    candidate.confidence(), candidate.validation().name(),
                    candidate.validationDetail()
            );
        }
    }

    public FiscalExtractionJobResponse response(String id, boolean replayed) {
        List<FiscalExtractionJobResponse> values = jdbc.query(
                """
                SELECT j.*, o.nome_original, o.media_type_detectado,
                       o.tamanho_bytes
                FROM finance_documento_extracao_job j
                JOIN stored_object o ON o.id = j.stored_object_id
                WHERE j.id = ?
                """,
                (rs, row) -> new FiscalExtractionJobResponse(
                        rs.getString("id"),
                        rs.getString("stored_object_id"),
                        rs.getString("obra_id"),
                        rs.getString("nome_original"),
                        rs.getString("media_type_detectado"),
                        rs.getLong("tamanho_bytes"),
                        rs.getString("client_mutation_id"),
                        rs.getString("formato"),
                        ExtractionStatus.valueOf(rs.getString("status")),
                        rs.getString("extrator"),
                        rs.getString("extrator_versao"),
                        rs.getString("sha256_cliente"),
                        rs.getString("sha256_servidor"),
                        candidates(rs.getString("id")),
                        warnings(rs.getString("avisos_json")),
                        rs.getString("autorizacao_fiscal_status"),
                        rs.getString("criado_por"),
                        rs.getString("dispositivo_id"),
                        rs.getString("correlacao_id"),
                        dateTime(rs, "criado_em"),
                        dateTime(rs, "concluido_em"),
                        replayed
                ),
                id
        );
        if (values.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Extração fiscal não encontrada."
            );
        }
        return values.getFirst();
    }

    private List<FiscalCandidate> candidates(String jobId) {
        return jdbc.query(
                """
                SELECT campo, valor_normalizado_json, texto_original,
                       extrator, extrator_versao, localizacao, confianca,
                       validacao_status, validacao_detalhe
                FROM finance_documento_extracao_candidato
                WHERE job_id = ?
                ORDER BY campo, ordem
                """,
                (rs, row) -> new FiscalCandidate(
                        rs.getString("campo"),
                        tree(rs.getString("valor_normalizado_json")),
                        rs.getString("texto_original"),
                        rs.getString("extrator"),
                        rs.getString("extrator_versao"),
                        rs.getString("localizacao"),
                        rs.getBigDecimal("confianca"),
                        CandidateValidation.valueOf(
                                rs.getString("validacao_status")
                        ),
                        rs.getString("validacao_detalhe")
                ),
                jobId
        );
    }

    private Optional<JobRecord> optionalJob(ResultSet rs) throws SQLException {
        return rs.next() ? Optional.of(mapJob(rs)) : Optional.empty();
    }

    private JobRecord mapJob(ResultSet rs) throws SQLException {
        return new JobRecord(
                rs.getString("id"), rs.getString("stored_object_id"),
                rs.getString("obra_id"), rs.getString("client_mutation_id"),
                rs.getString("formato"), rs.getString("status"),
                rs.getString("extrator"), rs.getString("extrator_versao"),
                rs.getString("sha256_cliente"),
                rs.getString("sha256_servidor"),
                rs.getString("autorizacao_fiscal_status"),
                rs.getString("criado_por"), rs.getString("dispositivo_id"),
                rs.getString("correlacao_id"), dateTime(rs, "criado_em"),
                dateTime(rs, "concluido_em")
        );
    }

    private LocalDateTime dateTime(ResultSet rs, String column)
            throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Não foi possível serializar a extração fiscal.",
                    exception
            );
        }
    }

    private JsonNode tree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Candidato fiscal persistido é inválido.",
                    exception
            );
        }
    }

    private List<String> warnings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(
                    value,
                    new TypeReference<List<String>>() { }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Avisos da extração fiscal são inválidos.",
                    exception
            );
        }
    }

    public record JobWrite(
            String id,
            String storedObjectId,
            String obraId,
            String clientMutationId,
            String format,
            String clientSha256,
            String serverSha256,
            String createdBy,
            String deviceId,
            String correlationId
    ) {
    }

    public record JobRecord(
            String id,
            String storedObjectId,
            String obraId,
            String clientMutationId,
            String format,
            String status,
            String extractor,
            String extractorVersion,
            String clientSha256,
            String serverSha256,
            String autorizacaoFiscal,
            String createdBy,
            String deviceId,
            String correlationId,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }
}
