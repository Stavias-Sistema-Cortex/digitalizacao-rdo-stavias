package com.projeto.cortex.pdoc;

import com.projeto.cortex.obras.Obra;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RealPdocInputLoader implements PdocInputLoader {

    private static final int DEFAULT_SIMULATION_ITERATIONS = 10_000;

    private final JdbcTemplate jdbcTemplate;

    public RealPdocInputLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PdocInputBundle load(Obra obra, LocalDate requestedReferenceDate) {
        ProgramacaoStats programacao = buscarProgramacaoStats(obra.getId());
        RdoStats rdo = buscarRdoStats(obra.getId(), requestedReferenceDate);
        LocalDate referenceDate = resolveReferenceDate(
                requestedReferenceDate,
                programacao.latestDate(),
                rdo.latestDate()
        );

        programacao = buscarProgramacaoStats(obra.getId(), referenceDate);
        rdo = buscarRdoStats(obra.getId(), referenceDate);
        MaterialStats material = buscarMaterialStats(obra.getId(), referenceDate);
        BigDecimal equipamentoHoras30d = buscarHorasEquipamento30d(obra.getId(), referenceDate);
        int delayedRdos = programacao.recordCount() > 0
                ? buscarRdosAtrasados(obra.getId(), referenceDate)
                : 0;
        SyncStats sync = buscarSyncStats(obra.getId());
        FinanceStats finance = buscarFinanceStats(obra.getId(), referenceDate);
        ServiceQuantityStats serviceQuantity =
                buscarServiceQuantityStats(obra.getId(), referenceDate);

        QuantityChoice quantity =
                escolherQuantidade(programacao, rdo, serviceQuantity);

        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, PdocInputOrigin> origins = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        PdocDataAvailability budgetAvailability =
                finance.hasBudgetData()
                        ? PdocDataAvailability.DIRECT
                        : PdocDataAvailability.ABSENT;
        put(
                inputs,
                origins,
                missing,
                "approvedBudget",
                "Orçamento total aprovado",
                budgetAvailability,
                finance.hasBudgetData() ? finance.approvedBudget() : null,
                "item_contratual.valor_total",
                finance.hasBudgetData()
                        ? "Soma dos valores totais dos itens contratuais ativos da obra."
                        : "Não há itens contratuais ativos com valor total para formar orçamento aprovado.",
                true
        );
        if (!finance.hasBudgetData()) {
            warnings.add(
                    "Orçamento total aprovado ausente; o PDOC não será calculado sem itens contratuais ativos."
            );
        }

        PdocDataAvailability actualCostAvailability =
                finance.hasActualCostData()
                        ? PdocDataAvailability.DIRECT
                        : PdocDataAvailability.ABSENT;
        put(
                inputs,
                origins,
                missing,
                "actualCost",
                "Custo realizado",
                actualCostAvailability,
                finance.hasActualCostData() ? finance.actualCost() : null,
                "execucao_servico_rdo.custo_realizado + alocacao_colaborador.custo_total",
                finance.hasActualCostData()
                        ? "Soma dos custos realizados lançados nas execuções de serviço e alocações de colaboradores."
                        : "Não há custo realizado informado em execução de serviço ou alocação de colaborador.",
                true
        );
        if (!finance.hasActualCostData()) {
            warnings.add(
                    "Custo realizado ausente; o PDOC não será calculado sem esse dado financeiro."
            );
        }

        PdocDataAvailability committedCostAvailability =
                finance.hasActualCostData()
                        ? PdocDataAvailability.AMBIGUOUS
                        : PdocDataAvailability.ABSENT;
        put(
                inputs,
                origins,
                missing,
                "committedCost",
                "Custo comprometido",
                committedCostAvailability,
                finance.hasActualCostData() ? finance.committedCost() : null,
                "execucao_servico_rdo.custo_realizado + alocacao_colaborador.custo_total",
                finance.hasActualCostData()
                        ? "Proxy operacional: enquanto não há ledger de comprometido, usa o custo realizado estruturado."
                        : "Não há custo comprometido nem proxy operacional calculável.",
                true
        );
        if (finance.hasActualCostData()) {
            warnings.add(
                    "Custo comprometido ainda não tem ledger próprio; o PDOC usa custo realizado estruturado como proxy auditável."
            );
        } else {
            warnings.add(
                    "Custo comprometido ausente; o PDOC não usará estimativas financeiras substitutas."
            );
        }

        BigDecimal totalPlanned = quantity == null ? null : quantity.totalPlanned();
        BigDecimal plannedUntilReference = quantity == null ? null : quantity.plannedUntilReference();
        BigDecimal actualExecuted = quantity == null ? null : quantity.actualExecuted();

        if (quantity != null && "ITEM_CONTRATUAL".equals(quantity.metric())) {
            warnings.add(
                    "Quantidade física derivada de itens contratuais e execuções de serviço; sem curva temporal, o total contratado foi usado como planejado até a referência."
            );
            if (
                    serviceQuantity.contractUnitCount() > 1
                            || serviceQuantity.executionUnitCount() > 1
            ) {
                warnings.add(
                        "Há múltiplas unidades de medida nos itens/execuções; o PDOC agregou quantidades apenas para manter rastreabilidade, não como unidade física homogênea."
                );
            }
        }

        put(
                inputs,
                origins,
                missing,
                "totalPlannedQuantity",
                "Quantidade total planejada",
                totalPlanned == null ? PdocDataAvailability.ABSENT : PdocDataAvailability.DERIVED,
                totalPlanned,
                quantity == null ? "programacao_operacional" : quantity.plannedSource(),
                quantity == null
                        ? "Não há quantidade planejada positiva em extensão, área ou volume."
                        : "Quantidade planejada agregada por obra.",
                true
        );

        put(
                inputs,
                origins,
                missing,
                "plannedExecutedQuantity",
                "Quantidade planejada até a data de referência",
                plannedUntilReference == null ? PdocDataAvailability.ABSENT : PdocDataAvailability.DERIVED,
                plannedUntilReference,
                quantity == null ? "programacao_operacional" : quantity.plannedSource(),
                "Soma planejada até " + referenceDate + ".",
                true
        );

        put(
                inputs,
                origins,
                missing,
                "actualExecutedQuantity",
                "Quantidade executada real",
                actualExecuted == null ? PdocDataAvailability.ABSENT : PdocDataAvailability.DERIVED,
                actualExecuted,
                quantity == null ? "rdo_controle_geometrico" : quantity.actualSource(),
                actualExecuted == null
                        ? "Não há produção real compatível registrada em controles geométricos de RDO."
                        : "Produção real agregada a partir dos controles geométricos de RDO.",
                true
        );

        if (programacao.incompleteQuantityRows() > 0) {
            warnings.add(
                    "Há " + programacao.incompleteQuantityRows()
                            + " linhas de programação sem quantidade completa."
            );
        }
        if (rdo.rdoCount() == 0) {
            warnings.add("Nenhum RDO associado encontrado para a obra até a data de referência.");
        }

        BigDecimal expectedMaterial = positive(material.expected())
                ? material.expected()
                : BigDecimal.ZERO;
        BigDecimal actualMaterial = positive(material.actual())
                ? material.actual()
                : BigDecimal.ZERO;
        boolean hasMaterial = positive(material.expected()) && positive(material.actual());
        if (!hasMaterial) {
            warnings.add(
                    "Dados de material ausentes ou incompletos; consumo de material será marcado como indisponível."
            );
        }
        put(
                inputs,
                origins,
                missing,
                "expectedMaterialConsumption",
                "Consumo previsto de material",
                hasMaterial ? PdocDataAvailability.DERIVED : PdocDataAvailability.ABSENT,
                hasMaterial ? expectedMaterial : null,
                "rdo_material.quantidade_prevista",
                "Soma das quantidades previstas informadas nos RDOs.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "actualMaterialConsumption",
                "Consumo real de material",
                hasMaterial ? PdocDataAvailability.DERIVED : PdocDataAvailability.ABSENT,
                hasMaterial ? actualMaterial : null,
                "rdo_material.quantidade_aplicada",
                "Soma das quantidades aplicadas informadas nos RDOs.",
                false
        );

        Productivity productivity = calcularProdutividade(
                plannedUntilReference,
                actualExecuted,
                programacao.firstDate(),
                referenceDate
        );
        if (!productivity.available()) {
            warnings.add(
                    "Produtividade esperada/real não pôde ser derivada com segurança a partir dos dados atuais."
            );
        }
        put(
                inputs,
                origins,
                missing,
                "expectedProductivity",
                "Produtividade esperada",
                productivity.available() ? PdocDataAvailability.DERIVED : PdocDataAvailability.ABSENT,
                productivity.available() ? productivity.expected() : null,
                "programacao_operacional",
                "Quantidade planejada até a referência dividida pelos dias decorridos.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "actualProductivity",
                "Produtividade real",
                productivity.available() ? PdocDataAvailability.DERIVED : PdocDataAvailability.ABSENT,
                productivity.available() ? productivity.actual() : null,
                "rdo_controle_geometrico",
                "Quantidade executada real dividida pelos dias decorridos.",
                false
        );

        if (!positive(equipamentoHoras30d)) {
            warnings.add(
                    "Horas planejadas e paralisações de equipamento não estão estruturadas; o risco de equipamento ficará sem evidência direta."
            );
        } else {
            warnings.add(
                    "Horas de equipamento dos RDOs foram usadas apenas como referência operacional; não há campo de paralisação estruturado."
            );
        }
        put(
                inputs,
                origins,
                missing,
                "equipmentDowntimeHours30d",
                "Horas de paralisação de equipamento em 30 dias",
                PdocDataAvailability.ABSENT,
                null,
                "rdo_equipamento",
                "Não há status/campo de paralisação de equipamento nos RDOs.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "plannedEquipmentHours30d",
                "Horas planejadas de equipamento em 30 dias",
                positive(equipamentoHoras30d) ? PdocDataAvailability.AMBIGUOUS : PdocDataAvailability.ABSENT,
                positive(equipamentoHoras30d) ? equipamentoHoras30d : null,
                "rdo_equipamento.hora_inicio/hora_fim",
                "Horas operacionais registradas; não equivalem a baseline planejado validado.",
                false
        );

        put(
                inputs,
                origins,
                missing,
                "delayedRdos",
                "RDOs atrasados",
                programacao.recordCount() > 0 ? PdocDataAvailability.DERIVED : PdocDataAvailability.ABSENT,
                delayedRdos,
                "programacao_operacional + rdo",
                "Datas de programação sem RDO correspondente até a referência.",
                false
        );

        PdocDataAvailability occurrenceAvailability = rdo.rdoCount() > 0
                ? PdocDataAvailability.AMBIGUOUS
                : PdocDataAvailability.ABSENT;
        if (rdo.observationCount() > 0) {
            warnings.add(
                    "Observações de RDO foram contadas como ocorrências relevantes, mas não há severidade estruturada."
            );
        }
        put(
                inputs,
                origins,
                missing,
                "criticalOccurrences",
                "Ocorrências relevantes",
                occurrenceAvailability,
                rdo.rdoCount() > 0 ? rdo.observationCount() : null,
                "rdo.observacoes",
                "Observações de RDO sem classificação formal de criticidade.",
                false
        );

        put(
                inputs,
                origins,
                missing,
                "pendingSyncEvents",
                "Eventos de sync pendentes",
                PdocDataAvailability.DIRECT,
                sync.pendingEvents(),
                "sync_mutacao_cliente.status",
                "Mutações pendentes ligadas à obra ou aos RDOs da obra.",
                false
        );

        if (sync.hoursSinceLastSync() == null) {
            warnings.add(
                    "Não há metadata de último sync para a obra; a confiança será penalizada se o cálculo for possível."
            );
        }
        put(
                inputs,
                origins,
                missing,
                "hoursSinceLastSync",
                "Horas desde o último sync",
                sync.hoursSinceLastSync() == null ? PdocDataAvailability.ABSENT : PdocDataAvailability.DERIVED,
                sync.hoursSinceLastSync(),
                "sync_mutacao_cliente",
                "Diferença entre agora e a última mutação recebida/aplicada relacionada.",
                false
        );

        inputs.put("referenceDate", referenceDate);
        inputs.put("quantityMetric", quantity == null ? null : quantity.metric());
        inputs.put("programacaoRows", programacao.recordCount());
        inputs.put("rdoRows", rdo.rdoCount());
        inputs.put("scheduleStartDate", programacao.firstDate());
        inputs.put("scheduleEndDate", programacao.latestDate());

        PdocInputBundle.SourceValues sourceValues =
                new PdocInputBundle.SourceValues(
                        finance.hasBudgetData() ? finance.approvedBudget() : null,
                        finance.hasActualCostData() ? finance.actualCost() : null,
                        finance.hasActualCostData() ? finance.committedCost() : null,
                        toDouble(totalPlanned),
                        toDouble(plannedUntilReference),
                        toDouble(actualExecuted),
                        toDouble(expectedMaterial),
                        toDouble(actualMaterial),
                        productivity.available() ? productivity.expected().doubleValue() : 0.0,
                        productivity.available() ? productivity.actual().doubleValue() : 0.0,
                        0.0,
                        toDouble(equipamentoHoras30d),
                        delayedRdos,
                        rdo.rdoCount() > 0 ? rdo.observationCount() : 0,
                        sync.pendingEvents(),
                        sync.hoursSinceLastSync() == null ? 168 : sync.hoursSinceLastSync(),
                        finance.hasBudgetData(),
                        programacao.recordCount() > 0,
                        actualExecuted != null,
                        hasMaterial,
                        positive(equipamentoHoras30d),
                        rdo.rdoCount() > 0,
                        rdo.rdoCount() > 0,
                        sync.hoursSinceLastSync() != null,
                        false,
                        programacao.recordCount() > 0,
                        totalPlanned != null && actualExecuted != null,
                        DEFAULT_SIMULATION_ITERATIONS
                );

        return new PdocInputBundle(
                obra.getId(),
                codigoObra(obra),
                referenceDate,
                inputs,
                origins,
                warnings,
                missing,
                sourceValues
        );
    }

    private ProgramacaoStats buscarProgramacaoStats(String obraId) {
        return buscarProgramacaoStats(obraId, null);
    }

    private ProgramacaoStats buscarProgramacaoStats(String obraId, LocalDate referenceDate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) AS total_registros,
                    MIN(data_programacao) AS primeira_data,
                    MAX(data_programacao) AS ultima_data,
                    SUM(extensao_m) AS total_extensao_m,
                    SUM(area_m2) AS total_area_m2,
                    SUM(volume_m3) AS total_volume_m3,
                    SUM(CASE WHEN ? IS NULL OR data_programacao <= ? THEN extensao_m ELSE 0 END)
                        AS extensao_ate_referencia,
                    SUM(CASE WHEN ? IS NULL OR data_programacao <= ? THEN area_m2 ELSE 0 END)
                        AS area_ate_referencia,
                    SUM(CASE WHEN ? IS NULL OR data_programacao <= ? THEN volume_m3 ELSE 0 END)
                        AS volume_ate_referencia,
                    SUM(CASE
                            WHEN extensao_m IS NULL
                              OR area_m2 IS NULL
                              OR volume_m3 IS NULL
                                THEN 1
                            ELSE 0
                        END) AS linhas_quantidade_incompleta
                FROM programacao_operacional
                WHERE obra_id = ?
                  AND cancelado_em IS NULL
                """,
                (rs, rowNum) -> new ProgramacaoStats(
                        rs.getInt("total_registros"),
                        toLocalDate(rs.getDate("primeira_data")),
                        toLocalDate(rs.getDate("ultima_data")),
                        rs.getBigDecimal("total_extensao_m"),
                        rs.getBigDecimal("total_area_m2"),
                        rs.getBigDecimal("total_volume_m3"),
                        rs.getBigDecimal("extensao_ate_referencia"),
                        rs.getBigDecimal("area_ate_referencia"),
                        rs.getBigDecimal("volume_ate_referencia"),
                        rs.getInt("linhas_quantidade_incompleta")
                ),
                referenceDate,
                referenceDate,
                referenceDate,
                referenceDate,
                referenceDate,
                referenceDate,
                obraId
        );
    }

    private RdoStats buscarRdoStats(String obraId, LocalDate referenceDate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(DISTINCT r.id) AS total_rdos,
                    MIN(r.data_rdo) AS primeira_data,
                    MAX(r.data_rdo) AS ultima_data,
                    COUNT(cg.id) AS total_controles,
                    SUM(cg.area_m2) AS area_m2,
                    SUM(cg.volume_m3) AS volume_m3,
                    SUM(cg.massa_tonelada) AS massa_tonelada,
                    SUM(CASE
                            WHEN r.observacoes IS NOT NULL
                              AND TRIM(r.observacoes) <> ''
                                THEN 1
                            ELSE 0
                        END) AS observacoes
                FROM rdo r
                LEFT JOIN rdo_controle_geometrico cg
                  ON cg.rdo_id = r.id
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                  AND (? IS NULL OR r.data_rdo <= ?)
                """,
                (rs, rowNum) -> new RdoStats(
                        rs.getInt("total_rdos"),
                        toLocalDate(rs.getDate("primeira_data")),
                        toLocalDate(rs.getDate("ultima_data")),
                        rs.getInt("total_controles"),
                        rs.getBigDecimal("area_m2"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getBigDecimal("massa_tonelada"),
                        rs.getInt("observacoes")
                ),
                obraId,
                referenceDate,
                referenceDate
        );
    }

    private MaterialStats buscarMaterialStats(String obraId, LocalDate referenceDate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    SUM(mat.quantidade_prevista) AS prevista,
                    SUM(mat.quantidade_aplicada) AS aplicada
                FROM rdo r
                JOIN rdo_material mat
                  ON mat.rdo_id = r.id
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                  AND r.data_rdo <= ?
                """,
                (rs, rowNum) -> new MaterialStats(
                        rs.getBigDecimal("prevista"),
                        rs.getBigDecimal("aplicada")
                ),
                obraId,
                referenceDate
        );
    }

    private BigDecimal buscarHorasEquipamento30d(String obraId, LocalDate referenceDate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    SUM(
                        CASE
                            WHEN eq.hora_inicio IS NOT NULL
                             AND eq.hora_fim IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, eq.hora_inicio, eq.hora_fim)
                                     / 60.0 * eq.quantidade
                            ELSE NULL
                        END
                    ) AS horas
                FROM rdo r
                JOIN rdo_equipamento eq
                  ON eq.rdo_id = r.id
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                  AND r.data_rdo BETWEEN DATE_SUB(?, INTERVAL 30 DAY) AND ?
                """,
                (rs, rowNum) -> rs.getBigDecimal("horas"),
                obraId,
                referenceDate,
                referenceDate
        );
    }

    private int buscarRdosAtrasados(String obraId, LocalDate referenceDate) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM (
                    SELECT DISTINCT data_programacao
                    FROM programacao_operacional
                    WHERE obra_id = ?
                      AND cancelado_em IS NULL
                      AND data_programacao <= ?
                ) programadas
                LEFT JOIN (
                    SELECT DISTINCT data_rdo
                    FROM rdo
                    WHERE obra_id = ?
                      AND cancelado_em IS NULL
                      AND data_rdo <= ?
                ) realizadas
                  ON realizadas.data_rdo = programadas.data_programacao
                WHERE realizadas.data_rdo IS NULL
                """,
                Integer.class,
                obraId,
                referenceDate,
                obraId,
                referenceDate
        );
        return value == null ? 0 : value;
    }

    private SyncStats buscarSyncStats(String obraId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    SUM(CASE WHEN status = 'PENDENTE' THEN 1 ELSE 0 END) AS pendentes,
                    TIMESTAMPDIFF(
                        HOUR,
                        MAX(COALESCE(aplicada_em, recebida_em)),
                        CURRENT_TIMESTAMP(6)
                    ) AS horas_ultimo_sync
                FROM sync_mutacao_cliente
                WHERE entidade_id = ?
                   OR entidade_id IN (
                        SELECT id
                        FROM rdo
                        WHERE obra_id = ?
                   )
                """,
                (rs, rowNum) -> new SyncStats(
                        rs.getInt("pendentes"),
                        nullableInteger(rs.getObject("horas_ultimo_sync"))
                ),
                obraId,
                obraId
        );
    }

    private FinanceStats buscarFinanceStats(
            String obraId,
            LocalDate referenceDate
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    (
                        SELECT COUNT(*)
                        FROM item_contratual
                        WHERE obra_id = ?
                          AND status = 'ATIVO'
                    ) AS item_count,
                    (
                        SELECT SUM(valor_total)
                        FROM item_contratual
                        WHERE obra_id = ?
                          AND status = 'ATIVO'
                    ) AS approved_budget,
                    (
                        SELECT COUNT(*)
                        FROM execucao_servico_rdo
                        WHERE obra_id = ?
                          AND data_execucao <= ?
                          AND cancelada = 0
                          AND custo_realizado IS NOT NULL
                    ) AS execution_cost_rows,
                    (
                        SELECT SUM(custo_realizado)
                        FROM execucao_servico_rdo
                        WHERE obra_id = ?
                          AND data_execucao <= ?
                          AND cancelada = 0
                    ) AS execution_cost,
                    (
                        SELECT COUNT(*)
                        FROM alocacao_colaborador
                        WHERE obra_id = ?
                          AND data_alocacao <= ?
                          AND status <> 'CANCELADA'
                          AND custo_total IS NOT NULL
                    ) AS allocation_cost_rows,
                    (
                        SELECT SUM(custo_total)
                        FROM alocacao_colaborador
                        WHERE obra_id = ?
                          AND data_alocacao <= ?
                          AND status <> 'CANCELADA'
                    ) AS allocation_cost
                """,
                (rs, rowNumber) -> {
                    BigDecimal executionCost =
                            valueOrZero(rs.getBigDecimal("execution_cost"));
                    BigDecimal allocationCost =
                            valueOrZero(rs.getBigDecimal("allocation_cost"));

                    return new FinanceStats(
                            rs.getInt("item_count"),
                            rs.getBigDecimal("approved_budget"),
                            rs.getInt("execution_cost_rows"),
                            rs.getInt("allocation_cost_rows"),
                            executionCost.add(allocationCost)
                    );
                },
                obraId,
                obraId,
                obraId,
                referenceDate,
                obraId,
                referenceDate,
                obraId,
                referenceDate,
                obraId,
                referenceDate
        );
    }

    private ServiceQuantityStats buscarServiceQuantityStats(
            String obraId,
            LocalDate referenceDate
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    (
                        SELECT COUNT(*)
                        FROM item_contratual
                        WHERE obra_id = ?
                          AND status = 'ATIVO'
                    ) AS item_count,
                    (
                        SELECT COUNT(DISTINCT unidade_medida)
                        FROM item_contratual
                        WHERE obra_id = ?
                          AND status = 'ATIVO'
                    ) AS contract_unit_count,
                    (
                        SELECT SUM(quantidade_contratada)
                        FROM item_contratual
                        WHERE obra_id = ?
                          AND status = 'ATIVO'
                    ) AS total_planned,
                    (
                        SELECT COUNT(*)
                        FROM execucao_servico_rdo
                        WHERE obra_id = ?
                          AND data_execucao <= ?
                          AND cancelada = 0
                    ) AS execution_count,
                    (
                        SELECT COUNT(DISTINCT unidade_medida)
                        FROM execucao_servico_rdo
                        WHERE obra_id = ?
                          AND data_execucao <= ?
                          AND cancelada = 0
                    ) AS execution_unit_count,
                    (
                        SELECT SUM(CASE
                                WHEN producao_rejeitada = 0
                                 AND status_validacao IN ('REGISTRADA', 'VALIDADA')
                                    THEN quantidade_executada
                                ELSE 0
                            END)
                        FROM execucao_servico_rdo
                        WHERE obra_id = ?
                          AND data_execucao <= ?
                          AND cancelada = 0
                    ) AS actual_executed
                """,
                (rs, rowNumber) -> new ServiceQuantityStats(
                        rs.getInt("item_count"),
                        rs.getInt("contract_unit_count"),
                        rs.getBigDecimal("total_planned"),
                        rs.getInt("execution_count"),
                        rs.getInt("execution_unit_count"),
                        valueOrZero(rs.getBigDecimal("actual_executed"))
                ),
                obraId,
                obraId,
                obraId,
                obraId,
                referenceDate,
                obraId,
                referenceDate,
                obraId,
                referenceDate
        );
    }

    private QuantityChoice escolherQuantidade(
            ProgramacaoStats programacao,
            RdoStats rdo,
            ServiceQuantityStats serviceQuantity
    ) {
        if (positive(programacao.totalAreaM2())) {
            return new QuantityChoice(
                    "AREA_M2",
                    "programacao_operacional.area_m2",
                    "rdo_controle_geometrico.area_m2",
                    programacao.totalAreaM2(),
                    valueOrZero(programacao.areaUntilReference()),
                    positive(rdo.areaM2()) ? rdo.areaM2() : null
            );
        }

        if (positive(programacao.totalVolumeM3())) {
            return new QuantityChoice(
                    "VOLUME_M3",
                    "programacao_operacional.volume_m3",
                    "rdo_controle_geometrico.volume_m3",
                    programacao.totalVolumeM3(),
                    valueOrZero(programacao.volumeUntilReference()),
                    positive(rdo.volumeM3()) ? rdo.volumeM3() : null
            );
        }

        if (positive(programacao.totalExtensionM())) {
            return new QuantityChoice(
                    "EXTENSAO_M",
                    "programacao_operacional.extensao_m",
                    "rdo_controle_geometrico",
                    programacao.totalExtensionM(),
                    valueOrZero(programacao.extensionUntilReference()),
                    null
            );
        }

        if (
                serviceQuantity != null
                        && positive(serviceQuantity.totalPlanned())
                        && serviceQuantity.executionCount() > 0
        ) {
            return new QuantityChoice(
                    "ITEM_CONTRATUAL",
                    "item_contratual.quantidade_contratada",
                    "execucao_servico_rdo.quantidade_executada",
                    serviceQuantity.totalPlanned(),
                    serviceQuantity.totalPlanned(),
                    valueOrZero(serviceQuantity.actualExecuted())
            );
        }

        return null;
    }

    private Productivity calcularProdutividade(
            BigDecimal plannedUntilReference,
            BigDecimal actualExecuted,
            LocalDate firstDate,
            LocalDate referenceDate
    ) {
        if (
                !positive(plannedUntilReference)
                        || !positive(actualExecuted)
                        || firstDate == null
                        || referenceDate == null
        ) {
            return new Productivity(false, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        long elapsedDays = Math.max(
                1,
                ChronoUnit.DAYS.between(firstDate, referenceDate) + 1
        );

        return new Productivity(
                true,
                plannedUntilReference.divide(
                        BigDecimal.valueOf(elapsedDays),
                        6,
                        RoundingMode.HALF_UP
                ),
                actualExecuted.divide(
                        BigDecimal.valueOf(elapsedDays),
                        6,
                        RoundingMode.HALF_UP
                )
        );
    }

    private void put(
            Map<String, Object> inputs,
            Map<String, PdocInputOrigin> origins,
            List<String> missing,
            String field,
            String label,
            PdocDataAvailability availability,
            Object value,
            String source,
            String detail,
            boolean required
    ) {
        inputs.put(field, value);
        origins.put(
                field,
                new PdocInputOrigin(
                        field,
                        label,
                        availability,
                        value,
                        source,
                        detail,
                        required
                )
        );

        if (required && (availability == PdocDataAvailability.ABSENT || value == null)) {
            missing.add(field);
        }
    }

    private LocalDate resolveReferenceDate(
            LocalDate requested,
            LocalDate latestProgramacao,
            LocalDate latestRdo
    ) {
        if (requested != null) {
            return requested;
        }

        if (latestProgramacao != null && latestRdo != null) {
            return latestProgramacao.isAfter(latestRdo)
                    ? latestProgramacao
                    : latestRdo;
        }

        if (latestProgramacao != null) {
            return latestProgramacao;
        }

        if (latestRdo != null) {
            return latestRdo;
        }

        return LocalDate.now();
    }

    private String codigoObra(Obra obra) {
        if (hasText(obra.getCodigoCw())) {
            return obra.getCodigoCw();
        }
        if (hasText(obra.getCodigoContrato())) {
            return obra.getCodigoContrato();
        }
        if (hasText(obra.getCodigoInterno())) {
            return obra.getCodigoInterno();
        }
        return obra.getId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private Integer nullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    private record ProgramacaoStats(
            int recordCount,
            LocalDate firstDate,
            LocalDate latestDate,
            BigDecimal totalExtensionM,
            BigDecimal totalAreaM2,
            BigDecimal totalVolumeM3,
            BigDecimal extensionUntilReference,
            BigDecimal areaUntilReference,
            BigDecimal volumeUntilReference,
            int incompleteQuantityRows
    ) {
    }

    private record RdoStats(
            int rdoCount,
            LocalDate firstDate,
            LocalDate latestDate,
            int controlCount,
            BigDecimal areaM2,
            BigDecimal volumeM3,
            BigDecimal massTon,
            int observationCount
    ) {
    }

    private record MaterialStats(
            BigDecimal expected,
            BigDecimal actual
    ) {
    }

    private record SyncStats(
            int pendingEvents,
            Integer hoursSinceLastSync
    ) {
    }

    private record FinanceStats(
            int itemCount,
            BigDecimal approvedBudget,
            int executionCostRows,
            int allocationCostRows,
            BigDecimal actualCost
    ) {
        private boolean hasBudgetData() {
            return itemCount > 0
                    && approvedBudget != null
                    && approvedBudget.compareTo(BigDecimal.ZERO) > 0;
        }

        private boolean hasActualCostData() {
            return executionCostRows > 0 || allocationCostRows > 0;
        }

        private BigDecimal committedCost() {
            return actualCost == null ? BigDecimal.ZERO : actualCost;
        }
    }

    private record ServiceQuantityStats(
            int itemCount,
            int contractUnitCount,
            BigDecimal totalPlanned,
            int executionCount,
            int executionUnitCount,
            BigDecimal actualExecuted
    ) {
    }

    private record QuantityChoice(
            String metric,
            String plannedSource,
            String actualSource,
            BigDecimal totalPlanned,
            BigDecimal plannedUntilReference,
            BigDecimal actualExecuted
    ) {
    }

    private record Productivity(
            boolean available,
            BigDecimal expected,
            BigDecimal actual
    ) {
    }
}
