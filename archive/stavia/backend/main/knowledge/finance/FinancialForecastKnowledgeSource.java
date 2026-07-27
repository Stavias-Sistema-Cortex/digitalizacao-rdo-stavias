package com.projeto.cortex.intelligence.stavia.knowledge.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class FinancialForecastKnowledgeSource implements StaviaKnowledgeSource {

    private static final String REQUIRED_FINANCIAL_PERMISSION =
            FinancialPermission.FINANCEIRO_VISUALIZAR.name();

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Set<StaviaIntent> SUPPORTED = Set.of(
            StaviaIntent.CONSULTAR_RECEITA,
            StaviaIntent.CONSULTAR_MARGEM,
            StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA,
            StaviaIntent.CONSULTAR_PRODUCAO,
            StaviaIntent.CONSULTAR_RECEITA_EM_RISCO
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObraRepository obraRepository;
    private final ObjectMapper objectMapper;

    public FinancialForecastKnowledgeSource(
            JdbcTemplate jdbcTemplate,
            ObraRepository obraRepository,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.obraRepository = obraRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceName() {
        return "previsao-financeira";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-FINANCE-SOURCE-0.1.0";
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        if (request == null) {
            return false;
        }
        if (!request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)) {
            return false;
        }
        if (!request.permissions().contains(REQUIRED_FINANCIAL_PERMISSION)) {
            return false;
        }
        return SUPPORTED.contains(request.intent());
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        if (!supports(request)) {
            return List.of();
        }
        List<Obra> obras =
                obraRepository.findAtivasByIdentificador(request.worksiteId());

        if (obras.size() != 1) {
            return List.of(unavailableEvidence(request.worksiteId()));
        }

        Obra obra = obras.getFirst();

        List<FinanceSnapshot> snapshots = jdbcTemplate.query(
                """
                SELECT
                    id,
                    codigo_obra,
                    data_referencia,
                    executado_em,
                    versao_modelo,
                    versao_premissas,
                    status_execucao,
                    status_calculo,
                    receita_estimada_dia,
                    receita_estimada_acumulada,
                    receita_validada_acumulada,
                    receita_medida,
                    receita_aprovada,
                    receita_faturada,
                    receita_recebida,
                    receita_prevista_final,
                    custo_realizado,
                    custo_comprometido,
                    custo_previsto_final,
                    margem_atual,
                    margem_prevista,
                    margem_percentual,
                    producao_planejada,
                    producao_realizada,
                    backlog_contratual,
                    receita_em_risco,
                    confianca,
                    qualidade_dados,
                    warnings_json,
                    fontes_json
                FROM previsao_financeira_snapshot
                WHERE obra_id = ?
                ORDER BY executado_em DESC, criado_em DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNumber) -> new FinanceSnapshot(
                        rs.getString("id"),
                        rs.getString("codigo_obra"),
                        rs.getDate("data_referencia").toLocalDate(),
                        rs.getTimestamp("executado_em").toLocalDateTime(),
                        rs.getString("versao_modelo"),
                        rs.getString("versao_premissas"),
                        rs.getString("status_execucao"),
                        rs.getString("status_calculo"),
                        rs.getBigDecimal("receita_estimada_dia"),
                        rs.getBigDecimal("receita_estimada_acumulada"),
                        rs.getBigDecimal("receita_validada_acumulada"),
                        rs.getBigDecimal("receita_medida"),
                        rs.getBigDecimal("receita_aprovada"),
                        rs.getBigDecimal("receita_faturada"),
                        rs.getBigDecimal("receita_recebida"),
                        rs.getBigDecimal("receita_prevista_final"),
                        rs.getBigDecimal("custo_realizado"),
                        rs.getBigDecimal("custo_comprometido"),
                        rs.getBigDecimal("custo_previsto_final"),
                        rs.getBigDecimal("margem_atual"),
                        rs.getBigDecimal("margem_prevista"),
                        rs.getBigDecimal("margem_percentual"),
                        rs.getBigDecimal("producao_planejada"),
                        rs.getBigDecimal("producao_realizada"),
                        rs.getBigDecimal("backlog_contratual"),
                        rs.getBigDecimal("receita_em_risco"),
                        rs.getBigDecimal("confianca"),
                        rs.getBigDecimal("qualidade_dados"),
                        parseJson(rs.getString("warnings_json")),
                        parseJson(rs.getString("fontes_json"))
                ),
                obra.getId()
        );

        if (snapshots.isEmpty()) {
            return List.of(noSnapshotEvidence(obra));
        }

        return List.of(toEvidence(obra, snapshots.getFirst()));
    }

    private StaviaEvidence toEvidence(Obra obra, FinanceSnapshot snapshot) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", obra.getId());
        attributes.put("codigoObra", snapshot.codigoObra());
        attributes.put("nomeObra", obra.getNome());
        attributes.put("snapshotId", snapshot.id());
        attributes.put("dataReferencia", snapshot.dataReferencia().toString());
        attributes.put("dataExecucao", snapshot.executadoEm().toString());
        attributes.put("versaoModelo", snapshot.versaoModelo());
        attributes.put("versaoPremissas", snapshot.versaoPremissas());
        attributes.put("statusExecucao", snapshot.statusExecucao());
        attributes.put("statusCalculo", snapshot.statusCalculo());
        put(attributes, "receitaEstimadaDia", snapshot.receitaEstimadaDia());
        put(attributes, "receitaEstimadaAcumulada", snapshot.receitaEstimadaAcumulada());
        put(attributes, "receitaValidadaAcumulada", snapshot.receitaValidadaAcumulada());
        put(attributes, "receitaMedida", snapshot.receitaMedida());
        put(attributes, "receitaAprovada", snapshot.receitaAprovada());
        put(attributes, "receitaFaturada", snapshot.receitaFaturada());
        put(attributes, "receitaRecebida", snapshot.receitaRecebida());
        put(attributes, "receitaPrevistaFinal", snapshot.receitaPrevistaFinal());
        put(attributes, "custoRealizado", snapshot.custoRealizado());
        put(attributes, "custoComprometido", snapshot.custoComprometido());
        put(attributes, "custoPrevistoFinal", snapshot.custoPrevistoFinal());
        put(attributes, "margemAtual", snapshot.margemAtual());
        put(attributes, "margemPrevista", snapshot.margemPrevista());
        put(attributes, "margemPercentual", snapshot.margemPercentual());
        put(attributes, "producaoPlanejada", snapshot.producaoPlanejada());
        put(attributes, "producaoRealizada", snapshot.producaoRealizada());
        put(attributes, "backlogContratual", snapshot.backlogContratual());
        put(attributes, "receitaEmRisco", snapshot.receitaEmRisco());
        put(attributes, "confianca", snapshot.confianca());
        put(attributes, "qualidadeDados", snapshot.qualidadeDados());
        attributes.put("warnings", snapshot.warnings());
        attributes.put("fontes", snapshot.fontes());
        attributes.put("receitaOperacionalNaoFaturada", true);

        return new StaviaEvidence(
                StaviaEvidenceTypes.PREVISAO_FINANCEIRA,
                "PREVISAO_FINANCEIRA:" + snapshot.id(),
                summary(snapshot),
                snapshot.executadoEm().toInstant(ZoneOffset.UTC),
                "CALCULADO".equals(snapshot.statusExecucao()),
                attributes
        );
    }

    private StaviaEvidence unavailableEvidence(String worksiteId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", worksiteId);
        attributes.put("statusExecucao", "OBRA_NAO_LOCALIZADA");

        return new StaviaEvidence(
                StaviaEvidenceTypes.PREVISAO_FINANCEIRA,
                "PREVISAO_FINANCEIRA:OBRA_NAO_LOCALIZADA:" + worksiteId,
                "A previsão financeira não localizou a obra informada.",
                null,
                false,
                attributes
        );
    }

    private StaviaEvidence noSnapshotEvidence(Obra obra) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", obra.getId());
        attributes.put("codigoObra", codigoObra(obra));
        attributes.put("statusExecucao", "SEM_SNAPSHOT");

        return new StaviaEvidence(
                StaviaEvidenceTypes.PREVISAO_FINANCEIRA,
                "PREVISAO_FINANCEIRA:SEM_SNAPSHOT:" + obra.getId(),
                "Nenhum snapshot financeiro encontrado para a obra "
                        + codigoObra(obra)
                        + ".",
                null,
                false,
                attributes
        );
    }

    private String summary(FinanceSnapshot snapshot) {
        return "Snapshot financeiro da obra "
                + snapshot.codigoObra()
                + " com referência "
                + DATE_FORMAT.format(snapshot.dataReferencia())
                + ", receita operacional estimada "
                + value(snapshot.receitaEstimadaAcumulada())
                + " e margem atual "
                + value(snapshot.margemAtual())
                + ".";
    }

    private String codigoObra(Obra obra) {
        if (hasText(obra.getCodigoCw())) {
            return obra.getCodigoCw().trim();
        }
        if (hasText(obra.getCodigoContrato())) {
            return obra.getCodigoContrato().trim();
        }
        if (hasText(obra.getCodigoInterno())) {
            return obra.getCodigoInterno().trim();
        }
        return obra.getId();
    }

    private void put(Map<String, Object> attributes, String key, BigDecimal value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private String value(BigDecimal value) {
        return value == null ? "não informada" : value.toPlainString();
    }

    private JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("jsonInvalido", true);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record FinanceSnapshot(
            String id,
            String codigoObra,
            LocalDate dataReferencia,
            LocalDateTime executadoEm,
            String versaoModelo,
            String versaoPremissas,
            String statusExecucao,
            String statusCalculo,
            BigDecimal receitaEstimadaDia,
            BigDecimal receitaEstimadaAcumulada,
            BigDecimal receitaValidadaAcumulada,
            BigDecimal receitaMedida,
            BigDecimal receitaAprovada,
            BigDecimal receitaFaturada,
            BigDecimal receitaRecebida,
            BigDecimal receitaPrevistaFinal,
            BigDecimal custoRealizado,
            BigDecimal custoComprometido,
            BigDecimal custoPrevistoFinal,
            BigDecimal margemAtual,
            BigDecimal margemPrevista,
            BigDecimal margemPercentual,
            BigDecimal producaoPlanejada,
            BigDecimal producaoRealizada,
            BigDecimal backlogContratual,
            BigDecimal receitaEmRisco,
            BigDecimal confianca,
            BigDecimal qualidadeDados,
            JsonNode warnings,
            JsonNode fontes
    ) {
    }
}
