package com.projeto.cortex.intelligence.stavia.knowledge.allocation;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AllocationKnowledgeSource implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_BR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})");

    private final JdbcTemplate jdbcTemplate;

    public AllocationKnowledgeSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return "alocacao-colaborador";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-ALLOCATION-SOURCE-0.3.0";
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null) {
            return false;
        }
        if (!request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)) {
            return false;
        }
        return request.intent() == StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR
                || request.intent() == StaviaIntent.CONSULTAR_FREQUENCIA
                || request.intent() == StaviaIntent.CONSULTAR_BANCO_HORAS;
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        LocalDate date = extractDate(request.question().text());
        StaviaEntityFilters filters =
                StaviaEntityFilters.from(request.plan().entities());

        if (isCrossWorksiteRelationship(request, filters)) {
            return retrieveAcrossWorks(filters, date);
        }

        String sql = """
                SELECT
                    aloc.id,
                    aloc.colaborador_id,
                    col.nome AS colaborador_nome,
                    aloc.data_alocacao,
                    aloc.hora_inicio,
                    aloc.hora_fim,
                    aloc.minutos,
                    aloc.percentual_dia,
                    aloc.obra_id,
                    obra.codigo_contrato,
                    obra.codigo_cw,
                    obra.nome AS obra_nome,
                    aloc.equipe,
                    aloc.servico_nome,
                    aloc.rdo_id,
                    aloc.turno,
                    aloc.funcao,
                    aloc.tipo_alocacao,
                    aloc.fonte,
                    aloc.status,
                    aloc.custo_total,
                    aloc.atualizado_em
                FROM alocacao_colaborador aloc
                JOIN colaborador col
                  ON col.id = aloc.colaborador_id
                JOIN obra
                  ON obra.id = aloc.obra_id
                WHERE (
                        obra.id = ?
                     OR obra.codigo_contrato = ?
                     OR obra.codigo_cw = ?
                     OR obra.codigo_interno = ?
                )
                  AND aloc.status <> 'CANCELADA'
                  AND (? IS NULL OR aloc.data_alocacao = ?)
                ORDER BY aloc.data_alocacao DESC, aloc.hora_inicio, col.nome
                LIMIT 50
                """;

        List<StaviaEvidence> rows = jdbcTemplate.query(
                sql,
                (rs, rowNumber) -> toEvidence(rs, "ALOCACAO_COLABORADOR:"),
                request.worksiteId(),
                request.worksiteId(),
                request.worksiteId(),
                request.worksiteId(),
                date,
                date
        );
        return filterByEntities(rows, filters);
    }

    /**
     * A question such as "Abner está em outra obra?" has to look beyond the
     * worksite supplied as context. Modern allocations are queried first and
     * the RDO workforce records fill the historical gap for entries created
     * before the allocation table existed.
     */
    private List<StaviaEvidence> retrieveAcrossWorks(
            StaviaEntityFilters filters,
            LocalDate date
    ) {
        String collaborator = filters.collaboratorName().orElseThrow();
        SqlNameFilter nameFilter = nameFilter(collaborator);
        List<StaviaEvidence> evidences = new ArrayList<>();

        String allocationsSql = """
                SELECT
                    aloc.id,
                    aloc.colaborador_id,
                    col.nome AS colaborador_nome,
                    aloc.data_alocacao,
                    aloc.hora_inicio,
                    aloc.hora_fim,
                    aloc.minutos,
                    aloc.percentual_dia,
                    aloc.obra_id,
                    obra.codigo_contrato,
                    obra.codigo_cw,
                    obra.nome AS obra_nome,
                    aloc.equipe,
                    aloc.servico_nome,
                    aloc.rdo_id,
                    aloc.turno,
                    aloc.funcao,
                    aloc.tipo_alocacao,
                    aloc.fonte,
                    aloc.status,
                    aloc.custo_total,
                    aloc.atualizado_em
                FROM alocacao_colaborador aloc
                JOIN colaborador col
                  ON col.id = aloc.colaborador_id
                JOIN obra
                  ON obra.id = aloc.obra_id
                WHERE aloc.status <> 'CANCELADA'
                  AND (? IS NULL OR aloc.data_alocacao = ?)
                  AND %s
                ORDER BY aloc.data_alocacao DESC, aloc.hora_inicio, col.nome
                LIMIT 100
                """.formatted(nameFilter.condition());

        List<Object> allocationParameters = new ArrayList<>();
        allocationParameters.add(date);
        allocationParameters.add(date);
        allocationParameters.addAll(nameFilter.parameters());
        evidences.addAll(jdbcTemplate.query(
                allocationsSql,
                (rs, rowNumber) -> toEvidence(rs, "ALOCACAO_COLABORADOR:"),
                allocationParameters.toArray()
        ));

        String legacySql = """
                SELECT
                    mo.id,
                    COALESCE(mo.colaborador_id, col.id) AS colaborador_id,
                    COALESCE(col.nome, mo.nome_colaborador) AS colaborador_nome,
                    r.data_rdo AS data_alocacao,
                    mo.hora_inicio,
                    mo.hora_fim,
                    NULL AS minutos,
                    NULL AS percentual_dia,
                    r.obra_id,
                    obra.codigo_contrato,
                    obra.codigo_cw,
                    obra.nome AS obra_nome,
                    NULL AS equipe,
                    NULL AS servico_nome,
                    r.id AS rdo_id,
                    r.turno,
                    mo.cargo AS funcao,
                    mo.tipo_vinculo AS tipo_alocacao,
                    'RDO_LEGADO' AS fonte,
                    r.status,
                    NULL AS custo_total,
                    mo.atualizado_em
                FROM rdo r
                JOIN rdo_mao_obra mo
                  ON mo.rdo_id = r.id
                LEFT JOIN colaborador col
                  ON col.id = mo.colaborador_id
                 AND col.deletado_em IS NULL
                JOIN obra
                  ON obra.id = r.obra_id
                WHERE (? IS NULL OR r.data_rdo = ?)
                  AND r.cancelado_em IS NULL
                  AND %s
                  AND NOT EXISTS (
                      SELECT 1
                      FROM alocacao_colaborador aloc
                      WHERE aloc.rdo_id = r.id
                        AND aloc.colaborador_id = mo.colaborador_id
                        AND aloc.status <> 'CANCELADA'
                  )
                ORDER BY r.data_rdo DESC, mo.hora_inicio, colaborador_nome
                LIMIT 100
                """.formatted(
                nameFilter.condition().replace("LOWER(col.nome)",
                        "LOWER(COALESCE(col.nome, mo.nome_colaborador))")
        );

        List<Object> legacyParameters = new ArrayList<>();
        legacyParameters.add(date);
        legacyParameters.add(date);
        legacyParameters.addAll(nameFilter.parameters());
        evidences.addAll(jdbcTemplate.query(
                legacySql,
                (rs, rowNumber) -> toEvidence(rs, "ALOCACAO_LEGADO:"),
                legacyParameters.toArray()
        ));

        String rdoHeaderSql = """
                SELECT *
                FROM (
                    SELECT
                        CONCAT(r.id, ':encarregado_obra') AS id,
                        NULL AS colaborador_id,
                        r.encarregado_obra AS colaborador_nome,
                        r.data_rdo AS data_alocacao,
                        r.hora_inicio,
                        r.hora_fim,
                        NULL AS minutos,
                        NULL AS percentual_dia,
                        r.obra_id,
                        obra.codigo_contrato,
                        obra.codigo_cw,
                        obra.nome AS obra_nome,
                        NULL AS equipe,
                        NULL AS servico_nome,
                        r.id AS rdo_id,
                        r.turno,
                        'Encarregado da obra' AS funcao,
                        'RDO_CABECALHO' AS tipo_alocacao,
                        'RDO_CABECALHO' AS fonte,
                        r.status,
                        NULL AS custo_total,
                        r.atualizado_em
                    FROM rdo r
                    JOIN obra
                      ON obra.id = r.obra_id
                    WHERE r.cancelado_em IS NULL
                      AND r.encarregado_obra IS NOT NULL
                      AND r.encarregado_obra <> ''

                    UNION ALL

                    SELECT
                        CONCAT(r.id, ':apontador_rdo') AS id,
                        NULL AS colaborador_id,
                        r.apontador_rdo AS colaborador_nome,
                        r.data_rdo AS data_alocacao,
                        r.hora_inicio,
                        r.hora_fim,
                        NULL AS minutos,
                        NULL AS percentual_dia,
                        r.obra_id,
                        obra.codigo_contrato,
                        obra.codigo_cw,
                        obra.nome AS obra_nome,
                        NULL AS equipe,
                        NULL AS servico_nome,
                        r.id AS rdo_id,
                        r.turno,
                        'Apontador do RDO' AS funcao,
                        'RDO_CABECALHO' AS tipo_alocacao,
                        'RDO_CABECALHO' AS fonte,
                        r.status,
                        NULL AS custo_total,
                        r.atualizado_em
                    FROM rdo r
                    JOIN obra
                      ON obra.id = r.obra_id
                    WHERE r.cancelado_em IS NULL
                      AND r.apontador_rdo IS NOT NULL
                      AND r.apontador_rdo <> ''

                    UNION ALL

                    SELECT
                        CONCAT(r.id, ':preenchido_por') AS id,
                        NULL AS colaborador_id,
                        r.preenchido_por AS colaborador_nome,
                        r.data_rdo AS data_alocacao,
                        r.hora_inicio,
                        r.hora_fim,
                        NULL AS minutos,
                        NULL AS percentual_dia,
                        r.obra_id,
                        obra.codigo_contrato,
                        obra.codigo_cw,
                        obra.nome AS obra_nome,
                        NULL AS equipe,
                        NULL AS servico_nome,
                        r.id AS rdo_id,
                        r.turno,
                        'Preenchido por' AS funcao,
                        'RDO_CABECALHO' AS tipo_alocacao,
                        'RDO_CABECALHO' AS fonte,
                        r.status,
                        NULL AS custo_total,
                        r.atualizado_em
                    FROM rdo r
                    JOIN obra
                      ON obra.id = r.obra_id
                    WHERE r.cancelado_em IS NULL
                      AND r.preenchido_por IS NOT NULL
                      AND r.preenchido_por <> ''
                ) rdo_cabecalho
                WHERE (? IS NULL OR data_alocacao = ?)
                  AND %s
                ORDER BY data_alocacao DESC, colaborador_nome
                LIMIT 100
                """.formatted(
                nameFilter.condition().replace("LOWER(col.nome)",
                        "LOWER(colaborador_nome)")
        );

        List<Object> rdoHeaderParameters = new ArrayList<>();
        rdoHeaderParameters.add(date);
        rdoHeaderParameters.add(date);
        rdoHeaderParameters.addAll(nameFilter.parameters());
        evidences.addAll(jdbcTemplate.query(
                rdoHeaderSql,
                (rs, rowNumber) -> toEvidence(rs, "ALOCACAO_RDO_CABECALHO:"),
                rdoHeaderParameters.toArray()
        ));

        String programmingSql = """
                SELECT
                    p.id,
                    p.encarregado_colaborador_id AS colaborador_id,
                    COALESCE(c.nome, p.encarregado) AS colaborador_nome,
                    p.data_programacao AS data_alocacao,
                    NULL AS hora_inicio,
                    NULL AS hora_fim,
                    NULL AS minutos,
                    NULL AS percentual_dia,
                    p.obra_id,
                    obra.codigo_contrato,
                    obra.codigo_cw,
                    obra.nome AS obra_nome,
                    p.equipe,
                    p.servico AS servico_nome,
                    (
                        SELECT r.id
                        FROM rdo r
                        WHERE r.programacao_id = p.id
                          AND r.cancelado_em IS NULL
                        ORDER BY r.data_rdo DESC, r.atualizado_em DESC, r.id
                        LIMIT 1
                    ) AS rdo_id,
                    p.periodo AS turno,
                    'Encarregado da programação' AS funcao,
                    'PROGRAMACAO_OPERACIONAL' AS tipo_alocacao,
                    'PROGRAMACAO_OPERACIONAL' AS fonte,
                    p.status,
                    NULL AS custo_total,
                    p.atualizado_em
                FROM programacao_operacional p
                LEFT JOIN colaborador c
                  ON c.id = p.encarregado_colaborador_id
                 AND c.deletado_em IS NULL
                JOIN obra
                  ON obra.id = p.obra_id
                WHERE p.cancelado_em IS NULL
                  AND p.encarregado IS NOT NULL
                  AND p.encarregado <> ''
                  AND (? IS NULL OR p.data_programacao = ?)
                  AND %s
                ORDER BY p.data_programacao DESC, colaborador_nome
                LIMIT 100
                """.formatted(
                nameFilter.condition().replace("LOWER(col.nome)",
                        "LOWER(COALESCE(c.nome, p.encarregado))")
        );

        List<Object> programmingParameters = new ArrayList<>();
        programmingParameters.add(date);
        programmingParameters.add(date);
        programmingParameters.addAll(nameFilter.parameters());
        evidences.addAll(jdbcTemplate.query(
                programmingSql,
                (rs, rowNumber) -> toEvidence(rs, "ALOCACAO_PROGRAMACAO:"),
                programmingParameters.toArray()
        ));

        return filterByEntities(evidences, filters);
    }

    private boolean isCrossWorksiteRelationship(
            StaviaKnowledgeRequest request,
            StaviaEntityFilters filters
    ) {
        return request.plan().operation()
                == QueryOperation.TRAVERSE_RELATIONSHIP
                && filters.collaboratorName().isPresent();
    }

    private SqlNameFilter nameFilter(String collaborator) {
        String[] tokens = StaviaText.normalize(collaborator).split("\\s+");
        List<String> meaningfulTokens = new ArrayList<>();

        for (int index = tokens.length > 1 ? 1 : 0;
                index < tokens.length;
                index++) {
            if (tokens[index].length() >= 3) {
                meaningfulTokens.add(tokens[index]);
            }
        }

        if (meaningfulTokens.isEmpty()) {
            return new SqlNameFilter("1 = 1", List.of());
        }

        String condition = meaningfulTokens.stream()
                .map(ignored -> "LOWER(col.nome) LIKE ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        List<Object> parameters = meaningfulTokens.stream()
                .map(token -> (Object) ("%" + token + "%"))
                .toList();

        return new SqlNameFilter(condition, parameters);
    }

    private StaviaEvidence toEvidence(
            ResultSet rs,
            String idPrefix
    ) throws SQLException {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("alocacaoId", rs.getString("id"));
        putText(attributes, "colaboradorId", rs.getString("colaborador_id"));
        putText(attributes, "colaboradorNome", rs.getString("colaborador_nome"));

        LocalDate data = rs.getDate("data_alocacao").toLocalDate();
        attributes.put("data", data.toString());
        putTime(attributes, "horaInicio", rs.getTime("hora_inicio"));
        putTime(attributes, "horaFim", rs.getTime("hora_fim"));
        putObject(attributes, "minutos", rs.getObject("minutos"));
        put(attributes, "percentualDia", rs.getBigDecimal("percentual_dia"));
        putText(attributes, "obraId", rs.getString("obra_id"));
        putText(attributes, "codigoContrato", rs.getString("codigo_contrato"));
        putText(attributes, "codigoCw", rs.getString("codigo_cw"));
        putText(attributes, "obraNome", rs.getString("obra_nome"));
        putText(attributes, "equipe", rs.getString("equipe"));
        putText(attributes, "servicoNome", rs.getString("servico_nome"));
        putText(attributes, "rdoId", rs.getString("rdo_id"));
        putText(attributes, "turno", rs.getString("turno"));
        putText(attributes, "funcao", rs.getString("funcao"));
        putText(attributes, "tipoAlocacao", rs.getString("tipo_alocacao"));
        putText(attributes, "fonte", rs.getString("fonte"));
        putText(attributes, "status", rs.getString("status"));
        put(attributes, "custoTotal", rs.getBigDecimal("custo_total"));

        return new StaviaEvidence(
                StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                idPrefix + rs.getString("id"),
                summary(
                        rs.getString("colaborador_nome"),
                        data,
                        rs.getString("codigo_cw"),
                        rs.getString("codigo_contrato"),
                        rs.getString("equipe"),
                        rs.getString("servico_nome"),
                        rs.getObject("minutos", Integer.class)
                ),
                rs.getTimestamp("atualizado_em") == null
                        ? null
                        : rs.getTimestamp("atualizado_em")
                                .toLocalDateTime()
                                .toInstant(ZoneOffset.UTC),
                isValidated(rs.getString("status")),
                attributes
        );
    }

    public static List<StaviaEvidence> filterByEntities(
            List<StaviaEvidence> evidences,
            StaviaEntityFilters filters
    ) {
        if (filters.isEmpty()) {
            return evidences;
        }
        return evidences.stream()
                .filter(e -> filters.matchesCollaborator(
                        String.valueOf(e.attributes().getOrDefault("colaboradorNome", ""))))
                .filter(e -> filters.matchesRole(
                        String.valueOf(e.attributes().getOrDefault("funcao", ""))))
                .toList();
    }

    private String summary(
            String colaborador,
            LocalDate data,
            String codigoCw,
            String codigoContrato,
            String equipe,
            String servico,
            Integer minutos
    ) {
        String codigo = hasText(codigoCw) ? codigoCw : codigoContrato;

        StringBuilder summary = new StringBuilder();
        summary.append(colaborador)
                .append(" esteve na obra ")
                .append(codigo)
                .append(" em ")
                .append(DATE_BR.format(data));

        if (minutos != null) {
            BigDecimal horas = BigDecimal.valueOf(minutos)
                    .divide(
                            BigDecimal.valueOf(60),
                            2,
                            java.math.RoundingMode.HALF_UP
                    );
            summary.append(" por ")
                    .append(horas)
                    .append(" hora(s)");
        } else {
            summary.append(" (registro legado de mão de obra)");
        }

        if (hasText(equipe)) {
            summary.append(", equipe ").append(equipe);
        }
        if (hasText(servico)) {
            summary.append(", serviço ").append(servico);
        }
        summary.append(".");
        return summary.toString();
    }

    private LocalDate extractDate(String question) {
        Matcher matcher = DATE_PATTERN.matcher(question == null ? "" : question);

        if (!matcher.find()) {
            return null;
        }

        String value = matcher.group(1);
        try {
            if (value.contains("/")) {
                return LocalDate.parse(value, DATE_BR);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private void putText(Map<String, Object> attributes, String key, String value) {
        if (hasText(value)) {
            attributes.put(key, value.trim());
        }
    }

    private void put(Map<String, Object> attributes, String key, BigDecimal value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private void putObject(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private void putTime(
            Map<String, Object> attributes,
            String key,
            java.sql.Time value
    ) {
        if (value != null) {
            LocalTime time = value.toLocalTime();
            attributes.put(key, time.toString());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isValidated(String status) {
        return "VALIDADA".equals(status)
                || "ENVIADO".equals(status)
                || "APROVADO".equals(status);
    }

    private record SqlNameFilter(
            String condition,
            List<Object> parameters
    ) {
    }
}
