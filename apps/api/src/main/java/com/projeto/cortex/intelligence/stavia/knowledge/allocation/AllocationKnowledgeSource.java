package com.projeto.cortex.intelligence.stavia.knowledge.allocation;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
        return "STAVIA-ALLOCATION-SOURCE-0.1.0";
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
                (rs, rowNumber) -> {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("alocacaoId", rs.getString("id"));
                    attributes.put("colaboradorId", rs.getString("colaborador_id"));
                    attributes.put("colaboradorNome", rs.getString("colaborador_nome"));
                    attributes.put("data", rs.getDate("data_alocacao").toLocalDate().toString());
                    putTime(attributes, "horaInicio", rs.getTime("hora_inicio"));
                    putTime(attributes, "horaFim", rs.getTime("hora_fim"));
                    attributes.put("minutos", rs.getInt("minutos"));
                    put(attributes, "percentualDia", rs.getBigDecimal("percentual_dia"));
                    attributes.put("obraId", rs.getString("obra_id"));
                    attributes.put("codigoContrato", rs.getString("codigo_contrato"));
                    attributes.put("codigoCw", rs.getString("codigo_cw"));
                    attributes.put("obraNome", rs.getString("obra_nome"));
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
                            "ALOCACAO_COLABORADOR:" + rs.getString("id"),
                            summary(
                                    rs.getString("colaborador_nome"),
                                    rs.getDate("data_alocacao").toLocalDate(),
                                    rs.getString("codigo_cw"),
                                    rs.getString("codigo_contrato"),
                                    rs.getString("equipe"),
                                    rs.getString("servico_nome"),
                                    rs.getInt("minutos")
                            ),
                            rs.getTimestamp("atualizado_em") == null
                                    ? null
                                    : rs.getTimestamp("atualizado_em")
                                            .toLocalDateTime()
                                            .toInstant(ZoneOffset.UTC),
                            "VALIDADA".equals(rs.getString("status")),
                            attributes
                    );
                },
                request.worksiteId(),
                request.worksiteId(),
                request.worksiteId(),
                request.worksiteId(),
                date,
                date
        );
        return filterByEntities(rows, StaviaEntityFilters.from(request.plan().entities()));
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
            int minutos
    ) {
        String codigo = hasText(codigoCw) ? codigoCw : codigoContrato;
        BigDecimal horas = BigDecimal.valueOf(minutos)
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);

        StringBuilder summary = new StringBuilder();
        summary.append(colaborador)
                .append(" esteve na obra ")
                .append(codigo)
                .append(" em ")
                .append(DATE_BR.format(data))
                .append(" por ")
                .append(horas)
                .append(" hora(s)");

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
}
