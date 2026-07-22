package com.projeto.cortex.pdor;

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
public class PdorSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PdorSnapshotRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(PdorSnapshot snapshot) throws DuplicateKeyException {
        jdbcTemplate.update(
                """
                INSERT INTO pdor_snapshot (
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
                    versao_dados,
                    inputs_json,
                    origem_inputs_json,
                    escopo_analise_json,
                    janela_temporal_json,
                    features_utilizadas_json,
                    dados_ausentes_json,
                    limitacoes_json,
                    alertas_json,
                    recomendacoes_json,
                    comparacao_anterior_json,
                    evidencias_json,
                    warnings_json,
                    iniciado_por,
                    tipo_iniciador,
                    modo_calculo,
                    calibracao,
                    fase_obra,
                    nivel_risco,
                    p10_receita,
                    p50_receita,
                    p80_receita,
                    p95_receita,
                    rac_rci,
                    rac_rci_spi,
                    rac_bottom_up,
                    rac_ponderado,
                    rci,
                    spi,
                    prob_abaixo_contrato,
                    prob_abaixo_95_pct,
                    prob_abaixo_90_pct,
                    score_heuristico,
                    confianca,
                    simulacao_convergiu,
                    iteracoes_simulacao,
                    drivers_json,
                    erro_execucao
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?,
                    CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB),
                    CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB),
                    CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB),
                    CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, CAST(? AS JSONB), ?
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
                snapshot.dataVersion(),
                toJson(snapshot.inputs()),
                toJson(snapshot.inputOrigins()),
                toJson(snapshot.analysisScope()),
                toJson(snapshot.temporalWindow()),
                toJson(snapshot.featuresUsed()),
                toJson(snapshot.missingData()),
                toJson(snapshot.limitations()),
                toJson(snapshot.alerts()),
                toJson(snapshot.recommendations()),
                toJson(snapshot.previousComparison()),
                toJson(snapshot.evidence()),
                toJson(snapshot.warnings()),
                snapshot.initiatedBy(),
                snapshot.initiatorType(),
                snapshot.calculationMode(),
                snapshot.calibrationStatus(),
                snapshot.projectPhase(),
                snapshot.riskLevel(),
                snapshot.revenueP10(),
                snapshot.revenueP50(),
                snapshot.revenueP80(),
                snapshot.revenueP95(),
                snapshot.racRci(),
                snapshot.racRciSpi(),
                snapshot.racBottomUp(),
                snapshot.racWeighted(),
                snapshot.rci(),
                snapshot.spi(),
                snapshot.probabilityBelowContract(),
                snapshot.probabilityBelow95Pct(),
                snapshot.probabilityBelow90Pct(),
                snapshot.heuristicRiskScore(),
                snapshot.confidence(),
                snapshot.simulationConverged(),
                snapshot.simulationIterations(),
                toJson(snapshot.drivers()),
                snapshot.executionError()
        );
    }

    public Optional<PdorSnapshot> findByIdempotencyKey(String idempotencyKey) {
        List<PdorSnapshot> snapshots = jdbcTemplate.query(
                baseSelect() + """
                WHERE chave_idempotencia = ?
                """,
                (rs, rowNum) -> map(rs),
                idempotencyKey
        );

        return snapshots.stream().findFirst();
    }

    public Optional<PdorSnapshot> findLatestByObraId(String obraId) {
        List<PdorSnapshot> snapshots = jdbcTemplate.query(
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

    public List<PdorSnapshot> findHistoryByObraId(
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
                FROM pdor_snapshot
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
                    versao_dados,
                    inputs_json,
                    origem_inputs_json,
                    escopo_analise_json,
                    janela_temporal_json,
                    features_utilizadas_json,
                    dados_ausentes_json,
                    limitacoes_json,
                    alertas_json,
                    recomendacoes_json,
                    comparacao_anterior_json,
                    evidencias_json,
                    warnings_json,
                    iniciado_por,
                    tipo_iniciador,
                    modo_calculo,
                    calibracao,
                    fase_obra,
                    nivel_risco,
                    p10_receita,
                    p50_receita,
                    p80_receita,
                    p95_receita,
                    rac_rci,
                    rac_rci_spi,
                    rac_bottom_up,
                    rac_ponderado,
                    rci,
                    spi,
                    prob_abaixo_contrato,
                    prob_abaixo_95_pct,
                    prob_abaixo_90_pct,
                    score_heuristico,
                    confianca,
                    simulacao_convergiu,
                    iteracoes_simulacao,
                    drivers_json,
                    erro_execucao,
                    criado_em
                FROM pdor_snapshot
                """;
    }

    private PdorSnapshot map(ResultSet rs) throws SQLException {
        return new PdorSnapshot(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("codigo_obra"),
                toLocalDateTime(rs, "executado_em"),
                rs.getDate("data_referencia").toLocalDate(),
                rs.getString("versao_modelo"),
                rs.getString("versao_premissas"),
                PdorExecutionStatus.valueOf(rs.getString("status_execucao")),
                PdorTriggerType.valueOf(rs.getString("tipo_disparo")),
                rs.getString("evento_origem_id"),
                rs.getString("chave_idempotencia"),
                parseJson(rs.getString("inputs_json")),
                parseJson(rs.getString("origem_inputs_json")),
                parseJson(rs.getString("warnings_json")),
                rs.getString("modo_calculo"),
                rs.getString("calibracao"),
                rs.getString("fase_obra"),
                rs.getString("nivel_risco"),
                rs.getBigDecimal("p10_receita"),
                rs.getBigDecimal("p50_receita"),
                rs.getBigDecimal("p80_receita"),
                rs.getBigDecimal("p95_receita"),
                rs.getBigDecimal("rac_rci"),
                rs.getBigDecimal("rac_rci_spi"),
                rs.getBigDecimal("rac_bottom_up"),
                rs.getBigDecimal("rac_ponderado"),
                rs.getBigDecimal("rci"),
                rs.getBigDecimal("spi"),
                rs.getBigDecimal("prob_abaixo_contrato"),
                rs.getBigDecimal("prob_abaixo_95_pct"),
                rs.getBigDecimal("prob_abaixo_90_pct"),
                rs.getBigDecimal("score_heuristico"),
                rs.getBigDecimal("confianca"),
                nullableBoolean(rs, "simulacao_convergiu"),
                nullableInteger(rs, "iteracoes_simulacao"),
                parseJson(rs.getString("drivers_json")),
                rs.getString("erro_execucao"),
                toLocalDateTime(rs, "criado_em"),
                rs.getString("versao_dados"),
                parseJson(rs.getString("escopo_analise_json")),
                parseJson(rs.getString("janela_temporal_json")),
                parseJson(rs.getString("features_utilizadas_json")),
                parseJson(rs.getString("dados_ausentes_json")),
                parseJson(rs.getString("limitacoes_json")),
                parseJson(rs.getString("alertas_json")),
                parseJson(rs.getString("recomendacoes_json")),
                parseJson(rs.getString("comparacao_anterior_json")),
                parseJson(rs.getString("evidencias_json")),
                rs.getString("iniciado_por"),
                rs.getString("tipo_iniciador")
        );
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(
                    node == null ? objectMapper.nullNode() : node
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Não foi possível serializar os dados do snapshot PDOR.",
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
