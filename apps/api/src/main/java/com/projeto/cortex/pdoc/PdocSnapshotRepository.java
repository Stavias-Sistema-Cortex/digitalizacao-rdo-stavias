package com.projeto.cortex.pdoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PdocSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PdocSnapshotRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(PdocSnapshot snapshot) throws DuplicateKeyException {
        jdbcTemplate.update(
                """
                INSERT INTO pdoc_snapshot (
                    id,
                    obra_id,
                    codigo_obra,
                    executado_em,
                    data_referencia,
                    versao_modelo,
                    versao_premissas,
                    status_execucao,
                    tipo_disparo,
                    evento_origem_id,
                    chave_idempotencia,
                    inputs_json,
                    origem_inputs_json,
                    warnings_json,
                    modo_calculo,
                    calibracao,
                    fase_obra,
                    nivel_risco,
                    p10_custo,
                    p50_custo,
                    p80_custo,
                    p95_custo,
                    eac_cpi,
                    eac_cpi_spi,
                    eac_bottom_up,
                    eac_ponderado,
                    cpi,
                    spi,
                    prob_qualquer_excedente,
                    prob_exceder_5_pct,
                    prob_exceder_10_pct,
                    score_heuristico,
                    confianca,
                    simulacao_convergiu,
                    iteracoes_simulacao,
                    drivers_json,
                    erro_execucao
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                snapshot.id(),
                snapshot.obraId(),
                snapshot.codigoObra(),
                snapshot.executedAt(),
                snapshot.referenceDate(),
                snapshot.modelVersion(),
                snapshot.assumptionsVersion(),
                snapshot.executionStatus().name(),
                snapshot.triggerType().name(),
                snapshot.originEventId(),
                snapshot.idempotencyKey(),
                toJson(snapshot.inputs()),
                toJson(snapshot.inputOrigins()),
                toJson(snapshot.warnings()),
                snapshot.calculationMode(),
                snapshot.calibrationStatus(),
                snapshot.projectPhase(),
                snapshot.riskLevel(),
                snapshot.costP10(),
                snapshot.costP50(),
                snapshot.costP80(),
                snapshot.costP95(),
                snapshot.eacCpi(),
                snapshot.eacCpiSpi(),
                snapshot.eacBottomUp(),
                snapshot.eacWeighted(),
                snapshot.cpi(),
                snapshot.spi(),
                snapshot.probabilityAnyOverrun(),
                snapshot.probabilityOverFivePercent(),
                snapshot.probabilityOverTenPercent(),
                snapshot.heuristicRiskScore(),
                snapshot.confidence(),
                snapshot.simulationConverged(),
                snapshot.simulationIterations(),
                toJson(snapshot.drivers()),
                snapshot.executionError()
        );
    }

    public Optional<PdocSnapshot> findByIdempotencyKey(String idempotencyKey) {
        List<PdocSnapshot> snapshots = jdbcTemplate.query(
                baseSelect() + """
                WHERE chave_idempotencia = ?
                """,
                (rs, rowNum) -> map(rs),
                idempotencyKey
        );

        return snapshots.stream().findFirst();
    }

    public Optional<PdocSnapshot> findLatestByObraId(String obraId) {
        List<PdocSnapshot> snapshots = jdbcTemplate.query(
                baseSelect() + """
                WHERE obra_id = ?
                ORDER BY executado_em DESC, criado_em DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> map(rs),
                obraId
        );

        return snapshots.stream().findFirst();
    }

    public List<PdocSnapshot> findHistoryByObraId(
            String obraId,
            int page,
            int size
    ) {
        return jdbcTemplate.query(
                baseSelect() + """
                WHERE obra_id = ?
                ORDER BY executado_em DESC, criado_em DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> map(rs),
                obraId,
                size,
                page * size
        );
    }

    public long countByObraId(String obraId) {
        Long total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pdoc_snapshot
                WHERE obra_id = ?
                """,
                Long.class,
                obraId
        );

        return total == null ? 0 : total;
    }

    private String baseSelect() {
        return """
                SELECT
                    id,
                    obra_id,
                    codigo_obra,
                    executado_em,
                    data_referencia,
                    versao_modelo,
                    versao_premissas,
                    status_execucao,
                    tipo_disparo,
                    evento_origem_id,
                    chave_idempotencia,
                    inputs_json,
                    origem_inputs_json,
                    warnings_json,
                    modo_calculo,
                    calibracao,
                    fase_obra,
                    nivel_risco,
                    p10_custo,
                    p50_custo,
                    p80_custo,
                    p95_custo,
                    eac_cpi,
                    eac_cpi_spi,
                    eac_bottom_up,
                    eac_ponderado,
                    cpi,
                    spi,
                    prob_qualquer_excedente,
                    prob_exceder_5_pct,
                    prob_exceder_10_pct,
                    score_heuristico,
                    confianca,
                    simulacao_convergiu,
                    iteracoes_simulacao,
                    drivers_json,
                    erro_execucao,
                    criado_em
                FROM pdoc_snapshot
                """;
    }

    private PdocSnapshot map(ResultSet rs) throws SQLException {
        return new PdocSnapshot(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("codigo_obra"),
                toLocalDateTime(rs, "executado_em"),
                rs.getDate("data_referencia").toLocalDate(),
                rs.getString("versao_modelo"),
                rs.getString("versao_premissas"),
                PdocExecutionStatus.valueOf(rs.getString("status_execucao")),
                PdocTriggerType.valueOf(rs.getString("tipo_disparo")),
                rs.getString("evento_origem_id"),
                rs.getString("chave_idempotencia"),
                parseJson(rs.getString("inputs_json")),
                parseJson(rs.getString("origem_inputs_json")),
                parseJson(rs.getString("warnings_json")),
                rs.getString("modo_calculo"),
                rs.getString("calibracao"),
                rs.getString("fase_obra"),
                rs.getString("nivel_risco"),
                rs.getBigDecimal("p10_custo"),
                rs.getBigDecimal("p50_custo"),
                rs.getBigDecimal("p80_custo"),
                rs.getBigDecimal("p95_custo"),
                rs.getBigDecimal("eac_cpi"),
                rs.getBigDecimal("eac_cpi_spi"),
                rs.getBigDecimal("eac_bottom_up"),
                rs.getBigDecimal("eac_ponderado"),
                rs.getBigDecimal("cpi"),
                rs.getBigDecimal("spi"),
                rs.getBigDecimal("prob_qualquer_excedente"),
                rs.getBigDecimal("prob_exceder_5_pct"),
                rs.getBigDecimal("prob_exceder_10_pct"),
                rs.getBigDecimal("score_heuristico"),
                rs.getBigDecimal("confianca"),
                nullableBoolean(rs, "simulacao_convergiu"),
                nullableInteger(rs, "iteracoes_simulacao"),
                parseJson(rs.getString("drivers_json")),
                rs.getString("erro_execucao"),
                toLocalDateTime(rs, "criado_em")
        );
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(
                    node == null ? objectMapper.nullNode() : node
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Não foi possível serializar os dados do snapshot PDOC.",
                    exception
            );
        }
    }

    private JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode()
                    .put("jsonInvalido", true);
        }
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column)
            throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private Boolean nullableBoolean(ResultSet rs, String column)
            throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getBoolean(column);
    }

    private Integer nullableInteger(ResultSet rs, String column)
            throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }
}
