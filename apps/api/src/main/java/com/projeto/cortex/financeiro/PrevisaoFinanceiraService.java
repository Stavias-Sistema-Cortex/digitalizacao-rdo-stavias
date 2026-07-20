package com.projeto.cortex.financeiro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class PrevisaoFinanceiraService {

    public static final String MODEL_VERSION = "FINANCEIRO-0.1.0";
    public static final String ASSUMPTIONS_VERSION = "FINANCEIRO-ASSUMPTIONS-0.1.0";

    private static final String FONTE = "PREVISAO_FINANCEIRA";

    private final JdbcTemplate jdbcTemplate;
    private final ObraRepository obraRepository;
    private final CortexOperationalMemoryService memoryService;
    private final ObjectMapper objectMapper;

    public PrevisaoFinanceiraService(
            JdbcTemplate jdbcTemplate,
            ObraRepository obraRepository,
            CortexOperationalMemoryService memoryService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.obraRepository = obraRepository;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    public PrevisaoFinanceiraResponse calcular(
            String obraIdentifier,
            LocalDate referenceDate,
            String triggerType,
            String originEventId
    ) {
        Obra obra = localizarObra(obraIdentifier);
        LocalDate dataReferencia =
                referenceDate == null
                        ? resolverDataReferencia(obra.getId())
                        : referenceDate;

        SnapshotDraft draft =
                construirSnapshot(
                        obra,
                        dataReferencia,
                        normalizarTipoDisparo(triggerType),
                        originEventId
                );

        return buscarPorIdempotencyKey(draft.chaveIdempotencia())
                .map(snapshot -> toResponse(snapshot, true))
                .orElseGet(() -> inserirSnapshot(draft, obra));
    }

    public PrevisaoFinanceiraResponse buscarAtual(String obraIdentifier) {
        Obra obra = localizarObra(obraIdentifier);

        return jdbcTemplate.query(
                baseSelect() + """
                WHERE obra_id = ?
                ORDER BY executado_em DESC, criado_em DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNumber) -> mapSnapshot(rs),
                obra.getId()
        ).stream()
                .findFirst()
                .map(snapshot -> toResponse(snapshot, true))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Nenhum snapshot financeiro encontrado para a obra."
                ));
    }

    public List<PrevisaoFinanceiraResponse> buscarHistorico(
            String obraIdentifier,
            int page,
            int size
    ) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page não pode ser negativo."
            );
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "size deve estar entre 1 e 100."
            );
        }

        Obra obra = localizarObra(obraIdentifier);

        return jdbcTemplate.query(
                baseSelect() + """
                WHERE obra_id = ?
                ORDER BY executado_em DESC, criado_em DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNumber) -> toResponse(mapSnapshot(rs), true),
                obra.getId(),
                size,
                page * size
        );
    }

    @Transactional
    public void recalcularAposMudancaRdo(
            String obraId,
            LocalDate dataReferencia,
            String originEventId
    ) {
        try {
            calcular(
                    obraId,
                    dataReferencia,
                    "EVENT",
                    originEventId
            );
        } catch (RuntimeException ignored) {
            // O evento do RDO não deve ser perdido por uma falha de previsão.
            // O snapshot manual/API continuará expondo o erro quando reexecutado.
        }
    }

    private SnapshotDraft construirSnapshot(
            Obra obra,
            LocalDate dataReferencia,
            String tipoDisparo,
            String originEventId
    ) {
        ContractAggregate contrato =
                buscarContrato(obra.getId());
        ExecutionAggregate execucao =
                buscarExecucao(obra.getId(), dataReferencia);
        BigDecimal custoAlocacao =
                valueOrZero(buscarCustoAlocacao(obra.getId(), dataReferencia));

        List<String> warnings = new ArrayList<>();

        if (contrato.itemCount() == 0) {
            warnings.add(
                    "Não há itens contratuais ativos para valorar a produção."
            );
        }
        if (execucao.totalExecucoes() == 0) {
            warnings.add(
                    "Não há serviços executados registrados em RDO até a data de referência."
            );
        }
        if (execucao.execucoesSemItem() > 0) {
            warnings.add(
                    execucao.execucoesSemItem()
                            + " execução(ões) não possuem item contratual associado."
            );
        }
        if (!positive(execucao.custoExecutado()) && !positive(custoAlocacao)) {
            warnings.add(
                    "Custos realizados ainda não foram informados para execução, alocação ou equipe."
            );
        }
        if (execucao.producaoRejeitada() > 0 || execucao.retrabalhos() > 0) {
            warnings.add(
                    "Há produção rejeitada ou retrabalho que pode colocar receita em risco."
            );
        }

        BigDecimal custoRealizado =
                dinheiro(valueOrZero(execucao.custoExecutado()).add(custoAlocacao));
        BigDecimal custoComprometido =
                custoRealizado;
        BigDecimal receitaEstimada =
                dinheiro(valueOrZero(execucao.receitaEstimadaAcumulada()));
        BigDecimal receitaValidada =
                dinheiro(valueOrZero(execucao.receitaValidadaAcumulada()));
        BigDecimal receitaPrevistaFinal =
                positive(contrato.valorTotal())
                        ? dinheiro(contrato.valorTotal())
                        : receitaEstimada;
        BigDecimal receitaEmRisco =
                dinheiro(valueOrZero(execucao.receitaEmRisco()));
        BigDecimal backlog =
                receitaPrevistaFinal == null
                        ? null
                        : dinheiro(receitaPrevistaFinal.subtract(receitaEstimada));
        BigDecimal producaoPlanejada =
                contrato.quantidadeContratada();
        BigDecimal producaoRealizada =
                execucao.quantidadeExecutada();

        BigDecimal progresso =
                ratio(producaoRealizada, producaoPlanejada);
        BigDecimal custoPrevistoFinal =
                positive(progresso)
                        ? dinheiro(custoRealizado.divide(
                                progresso,
                                2,
                                RoundingMode.HALF_UP
                        ))
                        : custoRealizado;

        BigDecimal margemAtual =
                dinheiro(receitaEstimada.subtract(custoRealizado));
        BigDecimal margemPrevista =
                receitaPrevistaFinal == null || custoPrevistoFinal == null
                        ? null
                        : dinheiro(receitaPrevistaFinal.subtract(custoPrevistoFinal));
        BigDecimal margemPercentual =
                positive(receitaEstimada)
                        ? margemAtual.divide(
                                receitaEstimada,
                                6,
                                RoundingMode.HALF_UP
                        )
                        : null;

        BigDecimal qualidadeDados =
                qualidadeDados(contrato, execucao, custoRealizado);
        BigDecimal confianca =
                qualidadeDados;

        String statusExecucao =
                contrato.itemCount() == 0 || execucao.totalExecucoes() == 0
                        ? "DADOS_INSUFICIENTES"
                        : "CALCULADO";
        String statusCalculo =
                "CALCULADO".equals(statusExecucao)
                        ? "NAO_CALIBRADO"
                        : "DADOS_INSUFICIENTES";

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("obraId", obra.getId());
        inputs.put("codigoObra", codigoObra(obra));
        inputs.put("dataReferencia", dataReferencia);
        inputs.put("itensContratuaisAtivos", contrato.itemCount());
        inputs.put("execucoesAteReferencia", execucao.totalExecucoes());
        inputs.put("execucoesSemItemContratual", execucao.execucoesSemItem());
        inputs.put("receitaBaseMargem", "RECEITA_OPERACIONAL_ESTIMADA");
        inputs.put("receitaNaoEquivaleAFaturamento", true);

        Map<String, Object> fontes = new LinkedHashMap<>();
        fontes.put("receitaOperacionalEstimativa", "execucao_servico_rdo + item_contratual");
        fontes.put("custoRealizado", "execucao_servico_rdo.custo_realizado + alocacao_colaborador.custo_total");
        fontes.put("producaoPlanejada", "item_contratual.quantidade_contratada");
        fontes.put("producaoRealizada", "execucao_servico_rdo.quantidade_executada");
        fontes.put("receitaMedidaFaturadaRecebida", "Estados explícitos em execucao_servico_rdo.estado_receita");

        String chaveIdempotencia =
                calculateIdempotencyKey(
                        obra.getId(),
                        dataReferencia,
                        inputs,
                        List.copyOf(warnings),
                        contrato,
                        execucao,
                        custoAlocacao
                );

        return new SnapshotDraft(
                UUID.randomUUID().toString(),
                obra.getId(),
                codigoObra(obra),
                dataReferencia,
                LocalDateTime.now(),
                MODEL_VERSION,
                ASSUMPTIONS_VERSION,
                statusExecucao,
                statusCalculo,
                tipoDisparo,
                nuloSeVazio(originEventId),
                chaveIdempotencia,
                execucao.receitaEstimadaDia(),
                receitaEstimada,
                receitaValidada,
                execucao.receitaMedida(),
                execucao.receitaAprovada(),
                execucao.receitaFaturada(),
                execucao.receitaRecebida(),
                receitaPrevistaFinal,
                custoRealizado,
                custoComprometido,
                custoPrevistoFinal,
                margemAtual,
                margemPrevista,
                margemPercentual,
                producaoPlanejada,
                producaoRealizada,
                backlog,
                receitaEmRisco,
                confianca,
                qualidadeDados,
                objectMapper.valueToTree(warnings),
                objectMapper.valueToTree(inputs),
                objectMapper.valueToTree(fontes),
                null
        );
    }

    private PrevisaoFinanceiraResponse inserirSnapshot(
            SnapshotDraft draft,
            Obra obra
    ) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO previsao_financeira_snapshot (
                        id,
                        obra_id,
                        codigo_obra,
                        data_referencia,
                        executado_em,
                        versao_modelo,
                        versao_premissas,
                        status_execucao,
                        status_calculo,
                        tipo_disparo,
                        evento_origem_id,
                        chave_idempotencia,
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
                        inputs_json,
                        fontes_json,
                        erro_execucao
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """,
                    draft.id(),
                    draft.obraId(),
                    draft.codigoObra(),
                    draft.dataReferencia(),
                    draft.executadoEm(),
                    draft.versaoModelo(),
                    draft.versaoPremissas(),
                    draft.statusExecucao(),
                    draft.statusCalculo(),
                    draft.tipoDisparo(),
                    draft.eventoOrigemId(),
                    draft.chaveIdempotencia(),
                    draft.receitaEstimadaDia(),
                    draft.receitaEstimadaAcumulada(),
                    draft.receitaValidadaAcumulada(),
                    draft.receitaMedida(),
                    draft.receitaAprovada(),
                    draft.receitaFaturada(),
                    draft.receitaRecebida(),
                    draft.receitaPrevistaFinal(),
                    draft.custoRealizado(),
                    draft.custoComprometido(),
                    draft.custoPrevistoFinal(),
                    draft.margemAtual(),
                    draft.margemPrevista(),
                    draft.margemPercentual(),
                    draft.producaoPlanejada(),
                    draft.producaoRealizada(),
                    draft.backlogContratual(),
                    draft.receitaEmRisco(),
                    draft.confianca(),
                    draft.qualidadeDados(),
                    toJson(draft.warnings()),
                    toJson(draft.inputs()),
                    toJson(draft.fontes()),
                    draft.erroExecucao()
            );

            registrarEventoSnapshot(draft, obra);

            return toResponse(toSnapshot(draft), false);
        } catch (DuplicateKeyException exception) {
            return buscarPorIdempotencyKey(draft.chaveIdempotencia())
                    .map(snapshot -> toResponse(snapshot, true))
                    .orElseThrow(() -> exception);
        }
    }

    private void registrarEventoSnapshot(
            SnapshotDraft draft,
            Obra obra
    ) {
        memoryService.registrarObjeto(
                "OBRA",
                obra.getId(),
                codigoObra(obra),
                obra.getNome(),
                obra.getStatus(),
                FONTE
        );
        memoryService.registrarObjeto(
                "PREVISAO_FINANCEIRA",
                draft.id(),
                draft.codigoObra(),
                "Previsão financeira " + draft.codigoObra(),
                draft.statusExecucao(),
                FONTE
        );
        memoryService.registrarRelacaoAtiva(
                "PREVISAO_FINANCEIRA",
                draft.id(),
                "OBRA",
                obra.getId(),
                "ANALISA",
                FONTE,
                "Snapshot financeiro analisa a obra."
        );

        String tipoEvento =
                "CALCULADO".equals(draft.statusExecucao())
                        ? "PREVISAO_FINANCEIRA_CALCULADA"
                        : "PREVISAO_FINANCEIRA_INSUFICIENTE";

        Map<String, Object> payload = payloadEventoFinanceiro(
                obra.getId(),
                draft.id(),
                draft.dataReferencia(),
                draft.statusExecucao(),
                draft.receitaEstimadaAcumulada(),
                draft.custoRealizado(),
                draft.margemAtual(),
                draft.margemPercentual(),
                draft.producaoPlanejada(),
                draft.producaoRealizada(),
                draft.custoPrevistoFinal(),
                draft.receitaPrevistaFinal()
        );

        memoryService.registrarEvento(
                "PREVISAO_FINANCEIRA",
                draft.id(),
                tipoEvento,
                FONTE,
                obra.getId(),
                payload
        );
    }

    private ContractAggregate buscarContrato(String obraId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) AS item_count,
                    SUM(quantidade_contratada) AS quantidade_total,
                    SUM(valor_total) AS valor_total
                FROM item_contratual
                WHERE obra_id = ?
                  AND status = 'ATIVO'
                """,
                (rs, rowNumber) -> new ContractAggregate(
                        rs.getInt("item_count"),
                        rs.getBigDecimal("quantidade_total"),
                        rs.getBigDecimal("valor_total")
                ),
                obraId
        );
    }

    private ExecutionAggregate buscarExecucao(
            String obraId,
            LocalDate dataReferencia
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) AS total_execucoes,
                    SUM(CASE WHEN item_contratual_id IS NULL THEN 1 ELSE 0 END) AS execucoes_sem_item,
                    SUM(CASE WHEN retrabalho = 1 THEN 1 ELSE 0 END) AS retrabalhos,
                    SUM(CASE WHEN producao_rejeitada = 1 THEN 1 ELSE 0 END) AS producao_rejeitada,
                    SUM(CASE
                            WHEN data_execucao = ?
                             AND status_validacao IN ('REGISTRADA', 'VALIDADA')
                             AND producao_rejeitada = 0
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_estimada_dia,
                    SUM(CASE
                            WHEN status_validacao IN ('REGISTRADA', 'VALIDADA')
                             AND producao_rejeitada = 0
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_estimada_acumulada,
                    SUM(CASE
                            WHEN status_validacao = 'VALIDADA'
                             AND producao_rejeitada = 0
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_validada_acumulada,
                    SUM(CASE
                            WHEN estado_receita IN (
                                'RECEITA_MEDIDA',
                                'RECEITA_APROVADA',
                                'RECEITA_FATURADA',
                                'RECEITA_RECEBIDA'
                            )
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_medida,
                    SUM(CASE
                            WHEN estado_receita IN (
                                'RECEITA_APROVADA',
                                'RECEITA_FATURADA',
                                'RECEITA_RECEBIDA'
                            )
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_aprovada,
                    SUM(CASE
                            WHEN estado_receita IN (
                                'RECEITA_FATURADA',
                                'RECEITA_RECEBIDA'
                            )
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_faturada,
                    SUM(CASE
                            WHEN estado_receita = 'RECEITA_RECEBIDA'
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_recebida,
                    SUM(CASE
                            WHEN retrabalho = 1
                              OR producao_rejeitada = 1
                              OR status_validacao = 'REJEITADA'
                                THEN receita_operacional_estimativa
                            ELSE 0
                        END) AS receita_em_risco,
                    SUM(custo_realizado) AS custo_executado,
                    SUM(CASE
                            WHEN producao_rejeitada = 0
                             AND status_validacao IN ('REGISTRADA', 'VALIDADA')
                                THEN quantidade_executada
                            ELSE 0
                        END) AS quantidade_executada
                FROM execucao_servico_rdo
                WHERE obra_id = ?
                  AND data_execucao <= ?
                  AND cancelada = 0
                """,
                (rs, rowNumber) -> new ExecutionAggregate(
                        rs.getInt("total_execucoes"),
                        rs.getInt("execucoes_sem_item"),
                        rs.getInt("retrabalhos"),
                        rs.getInt("producao_rejeitada"),
                        moneyOrZero(rs.getBigDecimal("receita_estimada_dia")),
                        moneyOrZero(rs.getBigDecimal("receita_estimada_acumulada")),
                        moneyOrZero(rs.getBigDecimal("receita_validada_acumulada")),
                        moneyOrZero(rs.getBigDecimal("receita_medida")),
                        moneyOrZero(rs.getBigDecimal("receita_aprovada")),
                        moneyOrZero(rs.getBigDecimal("receita_faturada")),
                        moneyOrZero(rs.getBigDecimal("receita_recebida")),
                        moneyOrZero(rs.getBigDecimal("receita_em_risco")),
                        moneyOrZero(rs.getBigDecimal("custo_executado")),
                        scale3OrZero(rs.getBigDecimal("quantidade_executada"))
                ),
                dataReferencia,
                obraId,
                dataReferencia
        );
    }

    private BigDecimal buscarCustoAlocacao(
            String obraId,
            LocalDate dataReferencia
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT SUM(custo_total)
                FROM alocacao_colaborador
                WHERE obra_id = ?
                  AND data_alocacao <= ?
                  AND status <> 'CANCELADA'
                """,
                BigDecimal.class,
                obraId,
                dataReferencia
        );
    }

    private LocalDate resolverDataReferencia(String obraId) {
        LocalDate latestExecution = jdbcTemplate.queryForObject(
                """
                SELECT MAX(data_execucao)
                FROM execucao_servico_rdo
                WHERE obra_id = ?
                  AND cancelada = 0
                """,
                (rs, rowNumber) ->
                        rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(),
                obraId
        );

        if (latestExecution != null) {
            return latestExecution;
        }

        LocalDate latestProgramacao = jdbcTemplate.queryForObject(
                """
                SELECT MAX(data_programacao)
                FROM programacao_operacional
                WHERE obra_id = ?
                  AND cancelado_em IS NULL
                """,
                (rs, rowNumber) ->
                        rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(),
                obraId
        );

        return latestProgramacao == null ? LocalDate.now() : latestProgramacao;
    }

    private java.util.Optional<Snapshot> buscarPorIdempotencyKey(
            String key
    ) {
        return jdbcTemplate.query(
                baseSelect() + """
                WHERE chave_idempotencia = ?
                """,
                (rs, rowNumber) -> mapSnapshot(rs),
                key
        ).stream().findFirst();
    }

    private String baseSelect() {
        return """
                SELECT
                    id,
                    obra_id,
                    codigo_obra,
                    data_referencia,
                    executado_em,
                    versao_modelo,
                    versao_premissas,
                    status_execucao,
                    status_calculo,
                    tipo_disparo,
                    evento_origem_id,
                    chave_idempotencia,
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
                    inputs_json,
                    fontes_json,
                    erro_execucao,
                    criado_em
                FROM previsao_financeira_snapshot
                """;
    }

    private Snapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new Snapshot(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("codigo_obra"),
                rs.getDate("data_referencia").toLocalDate(),
                toLocalDateTime(rs.getTimestamp("executado_em")),
                rs.getString("versao_modelo"),
                rs.getString("versao_premissas"),
                rs.getString("status_execucao"),
                rs.getString("status_calculo"),
                rs.getString("tipo_disparo"),
                rs.getString("evento_origem_id"),
                rs.getString("chave_idempotencia"),
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
                parseJson(rs.getString("inputs_json")),
                parseJson(rs.getString("fontes_json")),
                rs.getString("erro_execucao"),
                toLocalDateTime(rs.getTimestamp("criado_em"))
        );
    }

    private Snapshot toSnapshot(SnapshotDraft draft) {
        return new Snapshot(
                draft.id(),
                draft.obraId(),
                draft.codigoObra(),
                draft.dataReferencia(),
                draft.executadoEm(),
                draft.versaoModelo(),
                draft.versaoPremissas(),
                draft.statusExecucao(),
                draft.statusCalculo(),
                draft.tipoDisparo(),
                draft.eventoOrigemId(),
                draft.chaveIdempotencia(),
                draft.receitaEstimadaDia(),
                draft.receitaEstimadaAcumulada(),
                draft.receitaValidadaAcumulada(),
                draft.receitaMedida(),
                draft.receitaAprovada(),
                draft.receitaFaturada(),
                draft.receitaRecebida(),
                draft.receitaPrevistaFinal(),
                draft.custoRealizado(),
                draft.custoComprometido(),
                draft.custoPrevistoFinal(),
                draft.margemAtual(),
                draft.margemPrevista(),
                draft.margemPercentual(),
                draft.producaoPlanejada(),
                draft.producaoRealizada(),
                draft.backlogContratual(),
                draft.receitaEmRisco(),
                draft.confianca(),
                draft.qualidadeDados(),
                draft.warnings(),
                draft.inputs(),
                draft.fontes(),
                draft.erroExecucao(),
                draft.executadoEm()
        );
    }

    private PrevisaoFinanceiraResponse toResponse(
            Snapshot snapshot,
            boolean snapshotExistente
    ) {
        return new PrevisaoFinanceiraResponse(
                snapshot.id(),
                snapshot.obraId(),
                snapshot.codigoObra(),
                snapshot.dataReferencia(),
                snapshot.executadoEm(),
                snapshot.versaoModelo(),
                snapshot.versaoPremissas(),
                snapshot.statusExecucao(),
                PrevisaoFinanceiraResponse.statusExecucaoLabel(snapshot.statusExecucao()),
                snapshot.statusCalculo(),
                PrevisaoFinanceiraResponse.statusCalculoLabel(snapshot.statusCalculo()),
                snapshot.receitaEstimadaDia(),
                snapshot.receitaEstimadaAcumulada(),
                snapshot.receitaValidadaAcumulada(),
                snapshot.receitaMedida(),
                snapshot.receitaAprovada(),
                snapshot.receitaFaturada(),
                snapshot.receitaRecebida(),
                snapshot.receitaPrevistaFinal(),
                snapshot.custoRealizado(),
                snapshot.custoComprometido(),
                snapshot.custoPrevistoFinal(),
                snapshot.margemAtual(),
                snapshot.margemPrevista(),
                snapshot.margemPercentual(),
                snapshot.producaoPlanejada(),
                snapshot.producaoRealizada(),
                snapshot.backlogContratual(),
                snapshot.receitaEmRisco(),
                snapshot.confianca(),
                snapshot.qualidadeDados(),
                snapshot.warnings(),
                snapshot.inputs(),
                snapshot.fontes(),
                snapshot.erroExecucao(),
                snapshotExistente
        );
    }

    private Obra localizarObra(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador da obra é obrigatório."
            );
        }

        List<Obra> obras =
                obraRepository.findAtivasByIdentificador(identifier.trim());

        if (obras.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada: " + identifier
            );
        }

        if (obras.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Identificador de obra ambíguo: " + identifier
            );
        }

        return obras.getFirst();
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

    private BigDecimal qualidadeDados(
            ContractAggregate contrato,
            ExecutionAggregate execucao,
            BigDecimal custoRealizado
    ) {
        BigDecimal score = BigDecimal.ZERO;

        if (contrato.itemCount() > 0) {
            score = score.add(BigDecimal.valueOf(0.30));
        }
        if (execucao.totalExecucoes() > 0) {
            score = score.add(BigDecimal.valueOf(0.25));
        }
        if (execucao.execucoesSemItem() == 0 && execucao.totalExecucoes() > 0) {
            score = score.add(BigDecimal.valueOf(0.20));
        }
        if (positive(custoRealizado)) {
            score = score.add(BigDecimal.valueOf(0.15));
        }
        if (execucao.retrabalhos() == 0 && execucao.producaoRejeitada() == 0) {
            score = score.add(BigDecimal.valueOf(0.10));
        }

        return score.min(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (!positive(numerator) || !positive(denominator)) {
            return null;
        }

        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private String calculateIdempotencyKey(
            String obraId,
            LocalDate dataReferencia,
            Map<String, Object> inputs,
            List<String> warnings,
            ContractAggregate contrato,
            ExecutionAggregate execucao,
            BigDecimal custoAlocacao
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("obraId", obraId);
            payload.put("dataReferencia", dataReferencia);
            payload.put("modelVersion", MODEL_VERSION);
            payload.put("assumptionsVersion", ASSUMPTIONS_VERSION);
            payload.put("inputs", inputs);
            payload.put("warnings", warnings);
            payload.put("contrato", contrato);
            payload.put("execucao", execucao);
            payload.put("custoAlocacao", custoAlocacao);

            String json = objectMapper.writeValueAsString(canonicalize(payload));
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível calcular a chave de idempotência da previsão financeira.",
                    exception
            );
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> canonical = new TreeMap<>();
            map.forEach((key, item) ->
                    canonical.put(String.valueOf(key), canonicalize(item)));
            return canonical;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::canonicalize)
                    .sorted(Comparator.comparing(this::canonicalSortKey))
                    .toList();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private String canonicalSortKey(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private String normalizarTipoDisparo(String value) {
        if (!hasText(value)) {
            return "MANUAL";
        }
        String normalized = value.trim().toUpperCase();

        if (!List.of("MANUAL", "EVENT", "SCHEDULED", "API")
                .contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "tipoDisparo inválido."
            );
        }

        return normalized;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Não foi possível serializar o snapshot financeiro.",
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nuloSeVazio(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal moneyOrZero(BigDecimal value) {
        return dinheiro(valueOrZero(value));
    }

    private BigDecimal scale3OrZero(BigDecimal value) {
        return valueOrZero(value).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal dinheiro(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    static Map<String, Object> payloadEventoFinanceiro(
            String obraId,
            String snapshotId,
            LocalDate dataReferencia,
            String statusExecucao,
            BigDecimal receitaEstimadaAcumulada,
            BigDecimal custoRealizado,
            BigDecimal margemAtual,
            BigDecimal margemPercentual,
            BigDecimal producaoPlanejada,
            BigDecimal producaoRealizada,
            BigDecimal custoPrevistoFinal,
            BigDecimal receitaPrevistaFinal
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("obraId", obraId);
        payload.put("snapshotId", snapshotId);
        payload.put("dataReferencia", dataReferencia);
        payload.put("statusExecucao", statusExecucao);
        payload.put("receitaOperacionalEstimativa", receitaEstimadaAcumulada);
        payload.put("custoRealizado", custoRealizado);
        payload.put("margemAtual", margemAtual);
        payload.put("margemPercentual", margemPercentual);
        payload.put("producaoPlanejada", producaoPlanejada);
        payload.put("producaoRealizada", producaoRealizada);
        payload.put("custoPrevistoFinal", custoPrevistoFinal);
        payload.put("receitaPrevistaFinal", receitaPrevistaFinal);
        return payload;
    }

    private record ContractAggregate(
            int itemCount,
            BigDecimal quantidadeContratada,
            BigDecimal valorTotal
    ) {
    }

    private record ExecutionAggregate(
            int totalExecucoes,
            int execucoesSemItem,
            int retrabalhos,
            int producaoRejeitada,
            BigDecimal receitaEstimadaDia,
            BigDecimal receitaEstimadaAcumulada,
            BigDecimal receitaValidadaAcumulada,
            BigDecimal receitaMedida,
            BigDecimal receitaAprovada,
            BigDecimal receitaFaturada,
            BigDecimal receitaRecebida,
            BigDecimal receitaEmRisco,
            BigDecimal custoExecutado,
            BigDecimal quantidadeExecutada
    ) {
    }

    private record SnapshotDraft(
            String id,
            String obraId,
            String codigoObra,
            LocalDate dataReferencia,
            LocalDateTime executadoEm,
            String versaoModelo,
            String versaoPremissas,
            String statusExecucao,
            String statusCalculo,
            String tipoDisparo,
            String eventoOrigemId,
            String chaveIdempotencia,
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
            JsonNode inputs,
            JsonNode fontes,
            String erroExecucao
    ) {
    }

    private record Snapshot(
            String id,
            String obraId,
            String codigoObra,
            LocalDate dataReferencia,
            LocalDateTime executadoEm,
            String versaoModelo,
            String versaoPremissas,
            String statusExecucao,
            String statusCalculo,
            String tipoDisparo,
            String eventoOrigemId,
            String chaveIdempotencia,
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
            JsonNode inputs,
            JsonNode fontes,
            String erroExecucao,
            LocalDateTime criadoEm
    ) {
    }
}
