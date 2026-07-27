package com.projeto.cortex.intelligence.stavia.knowledge.context;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OperationalContextKnowledgeSource implements StaviaKnowledgeSource {

    public static final String SOURCE_NAME = "contexto-operacional-flexivel";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OperationalContextKnowledgeSource.class);

    private static final Pattern RDO_CONTEXT_PATTERN =
            Pattern.compile("(?iu)\\brdoId\\s*=\\s*([^\\s]+)");
    private static final Pattern RDO_NUMBER_PATTERN =
            Pattern.compile("(?iu)\\bRDO[-–][A-Z0-9_-]+\\b");

    private final JdbcTemplate jdbcTemplate;

    public OperationalContextKnowledgeSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public String sourceVersion() {
        return "1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                SOURCE_NAME,
                sourceVersion(),
                Set.of(),
                Set.of("OBRA", "RDO", "PROGRAMACAO", "ATIVIDADE"),
                Set.of(
                        "data", "dataRdo", "numeroRdo", "contrato", "codigoCw",
                        "cidade", "uf", "rodovia", "status", "turno", "programacao"
                ),
                Set.of("CONTEXTUALIZA", "RELACIONA"),
                Set.of(),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Tempo real",
                1,
                80
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null && request.question() != null;
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        List<StaviaEvidence> evidences = new ArrayList<>();
        String worksiteId = request.worksiteId();
        String questionText = request.question().text();
        String selectedRdoId = extractRdoReference(questionText);

        addSelectedRdoContext(evidences, selectedRdoId, worksiteId);
        addWorksiteContext(evidences, worksiteId);
        addRecentRdos(evidences, worksiteId, selectedRdoId);
        addSchedules(evidences, worksiteId);
        addServiceExecutions(evidences, worksiteId);

        return evidences;
    }

    private void addSelectedRdoContext(
            List<StaviaEvidence> evidences,
            String selectedRdoId,
            String fallbackWorksiteId
    ) {
        if (isBlank(selectedRdoId)) {
            return;
        }

        safe("RDO selecionado", () -> jdbcTemplate.query("""
                SELECT id, obra_id, programacao_id, numero_rdo, data_rdo,
                       dia_semana, contrato, rodovia, cidade, uf, turno,
                       status, observacoes, atualizado_em
                FROM rdo
                WHERE id = ? OR numero_rdo = ?
                LIMIT 1
                """, rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> attrs = rdoAttributes(rs);
                    String worksiteId = firstNonBlank(rs.getString("obra_id"), fallbackWorksiteId);
                    String summary = "Contexto selecionado: RDO "
                            + firstNonBlank(rs.getString("numero_rdo"), rs.getString("id"))
                            + ", data do RDO " + formatDate(rs.getDate("data_rdo"))
                            + append("obraId", worksiteId)
                            + append("contrato", rs.getString("contrato"))
                            + append("cidade", joinCity(rs.getString("cidade"), rs.getString("uf")))
                            + append("rodovia", rs.getString("rodovia"))
                            + append("turno", rs.getString("turno"))
                            + append("status", rs.getString("status"))
                            + ".";
                    evidences.add(new StaviaEvidence(
                            StaviaEvidenceTypes.CONTEXTO_OBRA,
                            "contexto-rdo-" + rs.getString("id"),
                            summary,
                            toInstant(rs.getTimestamp("atualizado_em")),
                            true,
                            attrs
                    ));
                    return null;
                }, selectedRdoId, selectedRdoId));
    }

    private void addWorksiteContext(
            List<StaviaEvidence> evidences,
            String worksiteId
    ) {
        if (isBlank(worksiteId)) {
            return;
        }

        safe("obra", () -> jdbcTemplate.query("""
                SELECT id, codigo_contrato, codigo_cw, codigo_interno, nome,
                       cliente, descricao, cidade, uf, rodovia, status, atualizado_em
                FROM obra
                WHERE id = ?
                LIMIT 1
                """, rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("obraId", rs.getString("id"));
                    attrs.put("nome", rs.getString("nome"));
                    attrs.put("codigoContrato", rs.getString("codigo_contrato"));
                    attrs.put("codigoCw", rs.getString("codigo_cw"));
                    attrs.put("codigoInterno", rs.getString("codigo_interno"));
                    attrs.put("cliente", rs.getString("cliente"));
                    attrs.put("cidade", rs.getString("cidade"));
                    attrs.put("uf", rs.getString("uf"));
                    attrs.put("rodovia", rs.getString("rodovia"));
                    attrs.put("status", rs.getString("status"));
                    attrs.put("descricao", rs.getString("descricao"));

                    String summary = "Obra selecionada: "
                            + firstNonBlank(rs.getString("nome"), rs.getString("id"))
                            + append("contrato", rs.getString("codigo_contrato"))
                            + append("CW", rs.getString("codigo_cw"))
                            + append("cliente", rs.getString("cliente"))
                            + append("cidade", joinCity(rs.getString("cidade"), rs.getString("uf")))
                            + append("rodovia", rs.getString("rodovia"))
                            + append("status", rs.getString("status"))
                            + ".";
                    evidences.add(new StaviaEvidence(
                            StaviaEvidenceTypes.OBRA,
                            "obra-" + rs.getString("id"),
                            summary,
                            toInstant(rs.getTimestamp("atualizado_em")),
                            true,
                            attrs
                    ));
                    return null;
                }, worksiteId));
    }

    private void addRecentRdos(
            List<StaviaEvidence> evidences,
            String worksiteId,
            String selectedRdoId
    ) {
        if (isBlank(worksiteId)) {
            return;
        }

        safe("RDOs recentes", () -> jdbcTemplate.query("""
                SELECT id, obra_id, programacao_id, numero_rdo, data_rdo,
                       dia_semana, contrato, rodovia, cidade, uf, turno,
                       status, observacoes, atualizado_em
                FROM rdo
                WHERE obra_id = ?
                ORDER BY data_rdo DESC, atualizado_em DESC
                LIMIT 8
                """, rs -> {
                    Map<String, Object> attrs = rdoAttributes(rs);
                    boolean selected = !isBlank(selectedRdoId)
                            && (selectedRdoId.equals(rs.getString("id"))
                                || selectedRdoId.equals(rs.getString("numero_rdo")));
                    attrs.put("selecionado", selected);
                    String summary = (selected ? "RDO selecionado" : "RDO relacionado")
                            + ": " + firstNonBlank(rs.getString("numero_rdo"), rs.getString("id"))
                            + ", data " + formatDate(rs.getDate("data_rdo"))
                            + append("status", rs.getString("status"))
                            + append("turno", rs.getString("turno"))
                            + append("observações", rs.getString("observacoes"))
                            + ".";
                    evidences.add(new StaviaEvidence(
                            StaviaEvidenceTypes.RDO,
                            "rdo-" + rs.getString("id"),
                            summary,
                            toInstant(rs.getTimestamp("atualizado_em")),
                            true,
                            attrs
                    ));
                }, worksiteId));
    }

    private void addSchedules(
            List<StaviaEvidence> evidences,
            String worksiteId
    ) {
        if (isBlank(worksiteId)) {
            return;
        }

        safe("programações", () -> jdbcTemplate.query("""
                SELECT id, obra_id, data_programacao, equipe, encarregado,
                       engenheiro, servico, tipo_servico, cidade, uf, rodovia,
                       faixa, km_inicial, km_final, status, observacoes, atualizado_em
                FROM programacao_operacional
                WHERE obra_id = ?
                ORDER BY data_programacao DESC, atualizado_em DESC
                LIMIT 10
                """, rs -> {
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("obraId", rs.getString("obra_id"));
                    attrs.put("programacaoId", rs.getString("id"));
                    attrs.put("dataProgramacao", formatDate(rs.getDate("data_programacao")));
                    attrs.put("equipe", rs.getString("equipe"));
                    attrs.put("encarregado", rs.getString("encarregado"));
                    attrs.put("engenheiro", rs.getString("engenheiro"));
                    attrs.put("servico", rs.getString("servico"));
                    attrs.put("tipoServico", rs.getString("tipo_servico"));
                    attrs.put("status", rs.getString("status"));

                    String summary = "Programação operacional: data "
                            + formatDate(rs.getDate("data_programacao"))
                            + append("serviço", firstNonBlank(rs.getString("servico"), rs.getString("tipo_servico")))
                            + append("equipe", rs.getString("equipe"))
                            + append("encarregado", rs.getString("encarregado"))
                            + append("status", rs.getString("status"))
                            + append("trecho", joinSegment(rs))
                            + ".";
                    evidences.add(new StaviaEvidence(
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            "programacao-" + rs.getString("id"),
                            summary,
                            toInstant(rs.getTimestamp("atualizado_em")),
                            true,
                            attrs
                    ));
                }, worksiteId));
    }

    private void addServiceExecutions(
            List<StaviaEvidence> evidences,
            String worksiteId
    ) {
        if (isBlank(worksiteId)) {
            return;
        }

        safe("execuções de serviço", () -> jdbcTemplate.query("""
                SELECT id, obra_id, rdo_id, programacao_id, servico_nome,
                       quantidade_executada, unidade_medida, data_execucao,
                       turno, status_validacao, estado_receita, retrabalho,
                       producao_rejeitada, atualizado_em
                FROM execucao_servico_rdo
                WHERE obra_id = ? AND cancelada = 0
                ORDER BY data_execucao DESC, atualizado_em DESC
                LIMIT 10
                """, rs -> {
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("obraId", rs.getString("obra_id"));
                    attrs.put("rdoId", rs.getString("rdo_id"));
                    attrs.put("programacaoId", rs.getString("programacao_id"));
                    attrs.put("dataExecucao", formatDate(rs.getDate("data_execucao")));
                    attrs.put("servico", rs.getString("servico_nome"));
                    attrs.put("quantidadeExecutada", rs.getBigDecimal("quantidade_executada"));
                    attrs.put("unidadeMedida", rs.getString("unidade_medida"));
                    attrs.put("statusValidacao", rs.getString("status_validacao"));
                    attrs.put("estadoReceita", rs.getString("estado_receita"));
                    attrs.put("retrabalho", rs.getBoolean("retrabalho"));
                    attrs.put("producaoRejeitada", rs.getBoolean("producao_rejeitada"));

                    String summary = "Execução registrada: "
                            + firstNonBlank(rs.getString("servico_nome"), rs.getString("id"))
                            + append("data", formatDate(rs.getDate("data_execucao")))
                            + append("quantidade", quantity(rs))
                            + append("status", rs.getString("status_validacao"))
                            + append("turno", rs.getString("turno"))
                            + booleanNote(rs.getBoolean("retrabalho"), "retrabalho registrado")
                            + booleanNote(rs.getBoolean("producao_rejeitada"), "produção rejeitada")
                            + ".";
                    evidences.add(new StaviaEvidence(
                            StaviaEvidenceTypes.ESTADO,
                            "execucao-" + rs.getString("id"),
                            summary,
                            toInstant(rs.getTimestamp("atualizado_em")),
                            true,
                            attrs
                    ));
                }, worksiteId));
    }

    private Map<String, Object> rdoAttributes(ResultSet rs) throws SQLException {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("obraId", rs.getString("obra_id"));
        attrs.put("rdoId", rs.getString("id"));
        attrs.put("programacaoId", rs.getString("programacao_id"));
        attrs.put("numeroRdo", rs.getString("numero_rdo"));
        attrs.put("dataRdo", formatDate(rs.getDate("data_rdo")));
        attrs.put("diaSemana", rs.getString("dia_semana"));
        attrs.put("contrato", rs.getString("contrato"));
        attrs.put("rodovia", rs.getString("rodovia"));
        attrs.put("cidade", rs.getString("cidade"));
        attrs.put("uf", rs.getString("uf"));
        attrs.put("turno", rs.getString("turno"));
        attrs.put("status", rs.getString("status"));
        attrs.put("observacoes", rs.getString("observacoes"));
        return attrs;
    }

    private String extractRdoReference(String questionText) {
        if (isBlank(questionText)) {
            return null;
        }
        Matcher context = RDO_CONTEXT_PATTERN.matcher(questionText);
        if (context.find()) {
            return cleanToken(context.group(1));
        }
        Matcher number = RDO_NUMBER_PATTERN.matcher(questionText);
        if (number.find()) {
            return cleanToken(number.group());
        }
        return null;
    }

    private void safe(String label, Runnable query) {
        try {
            query.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Fonte de contexto flexível ignorou {}: {}", label, exception.getMessage());
        }
    }

    private String cleanToken(String token) {
        if (token == null) {
            return null;
        }
        String cleaned = token.trim().replaceAll("[.,;:!?]+$", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    private String append(String label, String value) {
        if (isBlank(value)) {
            return "";
        }
        return ", " + label + " " + value.trim();
    }

    private String booleanNote(boolean active, String text) {
        return active ? ", " + text : "";
    }

    private String joinCity(String city, String state) {
        if (isBlank(city)) {
            return state;
        }
        if (isBlank(state)) {
            return city;
        }
        return city.trim() + "/" + state.trim();
    }

    private String joinSegment(ResultSet rs) throws SQLException {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, rs.getString("rodovia"));
        addIfPresent(parts, rs.getString("faixa"));
        addIfPresent(parts, rs.getString("km_inicial"));
        addIfPresent(parts, rs.getString("km_final"));
        return String.join(" - ", parts);
    }

    private void addIfPresent(List<String> values, String value) {
        if (!isBlank(value)) {
            values.add(value.trim());
        }
    }

    private String quantity(ResultSet rs) throws SQLException {
        if (rs.getBigDecimal("quantidade_executada") == null) {
            return null;
        }
        String unit = rs.getString("unidade_medida");
        return rs.getBigDecimal("quantidade_executada").stripTrailingZeros().toPlainString()
                + (isBlank(unit) ? "" : " " + unit.trim());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String formatDate(Date date) {
        if (date == null) {
            return "não informada";
        }
        return date.toLocalDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("pt-BR")));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
