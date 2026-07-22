package com.projeto.cortex.intelligence.stavia.knowledge.frequency;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FrequencyKnowledgeSource implements StaviaKnowledgeSource {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE_BR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})");

    private final JdbcTemplate jdbcTemplate;

    public FrequencyKnowledgeSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return "frequencia-banco-horas";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-FREQUENCY-SOURCE-0.1.0";
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null) {
            return false;
        }
        if (!request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)) {
            return false;
        }
        return request.intent() == StaviaIntent.CONSULTAR_FREQUENCIA
                || request.intent() == StaviaIntent.CONSULTAR_BANCO_HORAS;
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        LocalDate date =
                extractDate(request.question().text());

        if (request.intent() == StaviaIntent.CONSULTAR_BANCO_HORAS) {
            LocalDate reference =
                    date == null ? LocalDate.now(BUSINESS_ZONE) : date;
            return bancoHoras(request.worksiteId(), reference);
        }

        LocalDate start =
                date == null
                        ? LocalDate.now(BUSINESS_ZONE).minusDays(30)
                        : date;
        LocalDate end =
                date == null
                        ? LocalDate.now(BUSINESS_ZONE)
                        : date;

        List<StaviaEvidence> occurrences =
                ocorrencias(request.worksiteId(), start, end);

        if (occurrences.isEmpty()) {
            return List.of(noFrequencyEvidence(request.worksiteId(), start, end));
        }

        return occurrences;
    }

    private List<StaviaEvidence> ocorrencias(
            String worksiteId,
            LocalDate start,
            LocalDate end
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    oc.id,
                    oc.colaborador_id,
                    col.nome AS colaborador_nome,
                    oc.data_ocorrencia,
                    oc.tipo,
                    oc.minutos,
                    oc.status,
                    oc.origem,
                    oc.justificativa,
                    oc.criado_em
                FROM ocorrencia_frequencia oc
                JOIN colaborador col
                  ON col.id = oc.colaborador_id
                WHERE oc.data_ocorrencia BETWEEN ? AND ?
                  AND oc.status <> 'CANCELADA'
                  AND EXISTS (
                        SELECT 1
                        FROM alocacao_colaborador aloc
                        JOIN obra
                          ON obra.id = aloc.obra_id
                        WHERE aloc.colaborador_id = oc.colaborador_id
                          AND aloc.data_alocacao = oc.data_ocorrencia
                          AND aloc.status <> 'CANCELADA'
                          AND (
                                obra.id = ?
                             OR obra.codigo_contrato = ?
                             OR obra.codigo_cw = ?
                             OR obra.codigo_interno = ?
                          )
                  )
                ORDER BY oc.data_ocorrencia DESC, col.nome, oc.tipo
                LIMIT 50
                """,
                (rs, rowNumber) -> {
                    Map<String, Object> attributes =
                            new LinkedHashMap<>();
                    attributes.put("classe", "OCORRENCIA");
                    attributes.put("ocorrenciaId", rs.getString("id"));
                    attributes.put("colaboradorId", rs.getString("colaborador_id"));
                    attributes.put("colaboradorNome", rs.getString("colaborador_nome"));
                    attributes.put("data", rs.getDate("data_ocorrencia").toLocalDate().toString());
                    attributes.put("tipo", rs.getString("tipo"));
                    attributes.put("minutos", rs.getInt("minutos"));
                    attributes.put("status", rs.getString("status"));
                    attributes.put("origem", rs.getString("origem"));
                    putText(attributes, "justificativa", rs.getString("justificativa"));

                    return new StaviaEvidence(
                            StaviaEvidenceTypes.FREQUENCIA,
                            "FREQUENCIA:" + rs.getString("id"),
                            occurrenceSummary(
                                    rs.getString("colaborador_nome"),
                                    rs.getDate("data_ocorrencia").toLocalDate(),
                                    rs.getString("tipo"),
                                    rs.getInt("minutos"),
                                    rs.getString("status")
                            ),
                            rs.getTimestamp("criado_em")
                                    .toLocalDateTime()
                                    .toInstant(ZoneOffset.UTC),
                            "VALIDADA".equals(rs.getString("status"))
                                    || "JUSTIFICADA".equals(rs.getString("status")),
                            attributes
                    );
                },
                start,
                end,
                worksiteId,
                worksiteId,
                worksiteId,
                worksiteId
        );
    }

    private List<StaviaEvidence> bancoHoras(
            String worksiteId,
            LocalDate reference
    ) {
        LocalDate start =
                reference.withDayOfMonth(1);
        LocalDate end =
                reference.withDayOfMonth(reference.lengthOfMonth());

        List<StaviaEvidence> saldos = jdbcTemplate.query(
                """
                SELECT
                    saldo.id,
                    saldo.colaborador_id,
                    col.nome AS colaborador_nome,
                    saldo.periodo_inicio,
                    saldo.periodo_fim,
                    saldo.minutos_saldo_anterior,
                    saldo.minutos_credito,
                    saldo.minutos_debito,
                    saldo.minutos_ajuste,
                    saldo.minutos_saldo_atual,
                    saldo.calculado_em
                FROM saldo_banco_horas saldo
                JOIN colaborador col
                  ON col.id = saldo.colaborador_id
                WHERE saldo.periodo_inicio <= ?
                  AND saldo.periodo_fim >= ?
                  AND EXISTS (
                        SELECT 1
                        FROM alocacao_colaborador aloc
                        JOIN obra
                          ON obra.id = aloc.obra_id
                        WHERE aloc.colaborador_id = saldo.colaborador_id
                          AND aloc.data_alocacao BETWEEN saldo.periodo_inicio AND saldo.periodo_fim
                          AND aloc.status <> 'CANCELADA'
                          AND (
                                obra.id = ?
                             OR obra.codigo_contrato = ?
                             OR obra.codigo_cw = ?
                             OR obra.codigo_interno = ?
                          )
                  )
                ORDER BY saldo.periodo_fim DESC, col.nome
                LIMIT 50
                """,
                (rs, rowNumber) -> {
                    Map<String, Object> attributes =
                            new LinkedHashMap<>();
                    attributes.put("classe", "BANCO_HORAS");
                    attributes.put("saldoId", rs.getString("id"));
                    attributes.put("colaboradorId", rs.getString("colaborador_id"));
                    attributes.put("colaboradorNome", rs.getString("colaborador_nome"));
                    attributes.put("periodoInicio", rs.getDate("periodo_inicio").toLocalDate().toString());
                    attributes.put("periodoFim", rs.getDate("periodo_fim").toLocalDate().toString());
                    attributes.put("minutosSaldoAnterior", rs.getInt("minutos_saldo_anterior"));
                    attributes.put("minutosCredito", rs.getInt("minutos_credito"));
                    attributes.put("minutosDebito", rs.getInt("minutos_debito"));
                    attributes.put("minutosAjuste", rs.getInt("minutos_ajuste"));
                    attributes.put("minutosSaldoAtual", rs.getInt("minutos_saldo_atual"));

                    return new StaviaEvidence(
                            StaviaEvidenceTypes.FREQUENCIA,
                            "BANCO_HORAS:" + rs.getString("id"),
                            bankSummary(
                                    rs.getString("colaborador_nome"),
                                    rs.getDate("periodo_inicio").toLocalDate(),
                                    rs.getDate("periodo_fim").toLocalDate(),
                                    rs.getInt("minutos_saldo_atual")
                            ),
                            rs.getTimestamp("calculado_em")
                                    .toLocalDateTime()
                                    .toInstant(ZoneOffset.UTC),
                            true,
                            attributes
                    );
                },
                end,
                start,
                worksiteId,
                worksiteId,
                worksiteId,
                worksiteId
        );

        if (saldos.isEmpty()) {
            return List.of(noBankEvidence(worksiteId, start, end));
        }

        return saldos;
    }

    private StaviaEvidence noFrequencyEvidence(
            String worksiteId,
            LocalDate start,
            LocalDate end
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();
        attributes.put("classe", "SEM_DADOS_FREQUENCIA");
        attributes.put("obraId", worksiteId);
        attributes.put("inicio", start.toString());
        attributes.put("fim", end.toString());

        return new StaviaEvidence(
                StaviaEvidenceTypes.FREQUENCIA,
                "FREQUENCIA:SEM_DADOS:" + worksiteId + ":" + start + ":" + end,
                "Nenhuma ocorrência de frequência encontrada para a obra no período consultado.",
                null,
                false,
                attributes
        );
    }

    private StaviaEvidence noBankEvidence(
            String worksiteId,
            LocalDate start,
            LocalDate end
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();
        attributes.put("classe", "SEM_DADOS_BANCO_HORAS");
        attributes.put("obraId", worksiteId);
        attributes.put("periodoInicio", start.toString());
        attributes.put("periodoFim", end.toString());

        return new StaviaEvidence(
                StaviaEvidenceTypes.FREQUENCIA,
                "BANCO_HORAS:SEM_DADOS:" + worksiteId + ":" + start,
                "Nenhum saldo de banco de horas encontrado para colaboradores alocados na obra no período consultado.",
                null,
                false,
                attributes
        );
    }

    private String occurrenceSummary(
            String colaborador,
            LocalDate data,
            String tipo,
            int minutos,
            String status
    ) {
        return colaborador
                + " possui ocorrência "
                + readable(tipo)
                + " em "
                + DATE_BR.format(data)
                + " por "
                + hours(minutos)
                + " hora(s), status "
                + readable(status)
                + ".";
    }

    private String bankSummary(
            String colaborador,
            LocalDate start,
            LocalDate end,
            int minutes
    ) {
        return "Saldo de banco de horas de "
                + colaborador
                + " no período "
                + DATE_BR.format(start)
                + " a "
                + DATE_BR.format(end)
                + ": "
                + hours(minutes)
                + " hora(s).";
    }

    private LocalDate extractDate(String question) {
        String text =
                question == null ? "" : question.toLowerCase();

        if (text.contains("hoje")) {
            return LocalDate.now(BUSINESS_ZONE);
        }

        Matcher matcher =
                DATE_PATTERN.matcher(question == null ? "" : question);

        if (!matcher.find()) {
            return null;
        }

        String value =
                matcher.group(1);
        try {
            if (value.contains("/")) {
                return LocalDate.parse(value, DATE_BR);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String readable(String value) {
        if (value == null || value.isBlank()) {
            return "não informado";
        }
        return value.trim().replace('_', ' ').toLowerCase();
    }

    private String hours(int minutes) {
        return java.math.BigDecimal.valueOf(minutes)
                .divide(java.math.BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value.trim());
        }
    }
}
