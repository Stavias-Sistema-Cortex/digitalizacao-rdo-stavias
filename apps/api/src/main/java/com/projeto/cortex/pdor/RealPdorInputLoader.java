package com.projeto.cortex.pdor;

import com.projeto.cortex.intelligence.PdorEngine;
import com.projeto.cortex.obras.Obra;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RealPdorInputLoader implements PdorInputLoader {

    private static final int DEFAULT_SIMULATION_ITERATIONS = 10_000;

    /**
     * Espelho do mínimo exigido pelo PdorEngine para parametrizar uma
     * distribuição com histórico real; usado apenas para avisos.
     */
    private static final int MINIMUM_CALIBRATION_WEEKS =
            PdorEngine.MINIMUM_HISTORY_OBSERVATIONS;

    private final JdbcTemplate jdbcTemplate;

    public RealPdorInputLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PdorInputBundle load(Obra obra, LocalDate requestedReferenceDate) {
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
        ServiceQuantityStats serviceQuantity = buscarServiceQuantityStats(
                obra.getId(),
                referenceDate,
                finance.acceptedQuantity()
        );
        TeamStats team = buscarTeamStats(obra.getId(), referenceDate);
        int activeGeospatialFeatureCount =
                buscarActiveGeospatialFeatureCount(obra.getId(), referenceDate);
        WeatherStats weather = buscarWeatherStats(obra.getId(), referenceDate);

        QuantityChoice quantity =
                escolherQuantidade(programacao, rdo, serviceQuantity);

        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, PdorInputOrigin> origins = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        PdorDataAvailability contractAvailability =
                finance.hasContractData()
                        ? PdorDataAvailability.DIRECT
                        : PdorDataAvailability.ABSENT;
        put(
                inputs,
                origins,
                missing,
                "contractValue",
                "Valor contratual da obra",
                contractAvailability,
                finance.hasContractData() ? finance.contractValue() : null,
                "item_contratual.valor_total",
                finance.hasContractData()
                        ? "Soma dos valores totais dos itens contratuais ativos da obra."
                        : "Não há itens contratuais ativos com valor total para formar o valor contratual.",
                true
        );
        if (!finance.hasContractData()) {
            warnings.add(
                    "Valor contratual ausente; o PDOR não será calculado sem itens contratuais ativos."
            );
        }

        PdorDataAvailability measuredRevenueAvailability =
                finance.hasRevenueData()
                        ? PdorDataAvailability.DIRECT
                        : PdorDataAvailability.ABSENT;
        put(
                inputs,
                origins,
                missing,
                "measuredRevenue",
                "Receita medida acumulada",
                measuredRevenueAvailability,
                finance.hasRevenueData() ? finance.measuredRevenue() : null,
                "execucao_servico_rdo.revenue_amount + cortex_evento_operacional.commit_seq",
                finance.hasRevenueData()
                        ? "Soma exata de evidências ACCEPTED_EXACT validadas contra o evento ontológico canônico."
                        : "Não há evidência de receita aceita com identidade e evento canônicos válidos.",
                true
        );
        if (!finance.hasRevenueData()) {
            warnings.add(
                    "Receita medida ausente; o PDOR não será calculado sem esse dado financeiro."
            );
        }

        PdorDataAvailability validatedRevenueAvailability =
                finance.hasRevenueData()
                        ? PdorDataAvailability.DIRECT
                        : PdorDataAvailability.ABSENT;
        put(
                inputs,
                origins,
                missing,
                "validatedRevenue",
                "Receita validada acumulada",
                validatedRevenueAvailability,
                finance.hasRevenueData() ? finance.validatedRevenue() : null,
                "execucao_servico_rdo.revenue_amount + cortex_evento_operacional.commit_seq",
                finance.hasRevenueData()
                        ? "Receita aceita em execuções VALIDADA, sem rejeição, retrabalho ou cancelamento."
                        : "Não há receita aceita validada para a obra.",
                true
        );
        if (!finance.hasRevenueData()) {
            warnings.add(
                    "Receita validada ausente; o PDOR não usará estimativas financeiras substitutas."
            );
        }

        put(
                inputs,
                origins,
                missing,
                "acceptedRevenueEvidenceCount",
                "Evidências de receita aceitas",
                PdorDataAvailability.DIRECT,
                finance.acceptedRows(),
                "execucao_servico_rdo.revenue_evidence_id",
                "Quantidade de evidências aceitas que passaram pela validação canônica completa.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "eligibleRevenueExecutionCount",
                "Execuções elegíveis para receita",
                PdorDataAvailability.DIRECT,
                finance.eligibleRows(),
                "execucao_servico_rdo.revenue_coverage_code",
                "Execuções validadas e não rejeitadas usadas para medir cobertura de preço.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "evidenceHighWaterMark",
                "Marca d'água da ontologia",
                PdorDataAvailability.DIRECT,
                finance.evidenceHighWaterMark(),
                "cortex_evento_commit_sequence.ultima_commit_seq",
                "Sequência canônica observada atomicamente durante a leitura das evidências.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "revenueCoverageCode",
                "Cobertura da receita",
                PdorDataAvailability.DERIVED,
                finance.coverageCode(),
                "execucao_servico_rdo.revenue_coverage_code",
                "Cobertura calculada entre execuções elegíveis e evidências ACCEPTED_EXACT válidas.",
                false
        );

        BigDecimal totalPlanned = quantity == null ? null : quantity.totalPlanned();
        BigDecimal plannedUntilReference = quantity == null ? null : quantity.plannedUntilReference();
        BigDecimal actualExecuted = quantity == null ? null : quantity.actualExecuted();
        BigDecimal remainingContracted = totalPlanned == null
                ? null
                : totalPlanned.subtract(valueOrZero(actualExecuted)).max(BigDecimal.ZERO);

        if (quantity != null && "ITEM_CONTRATUAL".equals(quantity.metric())) {
            warnings.add(
                    "Quantidade física derivada de itens contratuais e execuções de serviço; sem curva temporal, o total contratado foi usado como planejado até a referência."
            );
            if (
                    serviceQuantity.contractUnitCount() > 1
                            || serviceQuantity.executionUnitCount() > 1
            ) {
                warnings.add(
                        "Há múltiplas unidades de medida nos itens/execuções; o PDOR agregou quantidades apenas para manter rastreabilidade, não como unidade física homogênea."
                );
            }
        }

        put(
                inputs,
                origins,
                missing,
                "totalPlannedQuantity",
                "Quantidade total planejada",
                totalPlanned == null ? PdorDataAvailability.ABSENT : PdorDataAvailability.DERIVED,
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
                "remainingContractedQuantity",
                "Quantidade contratada remanescente",
                remainingContracted == null
                        ? PdorDataAvailability.ABSENT
                        : PdorDataAvailability.DERIVED,
                remainingContracted,
                "item_contratual.quantidade_contratada - execucao_servico_rdo.quantidade_executada",
                "Saldo factual após subtrair somente execuções com receita aceita.",
                false
        );

        put(
                inputs,
                origins,
                missing,
                "plannedExecutedQuantity",
                "Quantidade planejada até a data de referência",
                plannedUntilReference == null ? PdorDataAvailability.ABSENT : PdorDataAvailability.DERIVED,
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
                actualExecuted == null ? PdorDataAvailability.ABSENT : PdorDataAvailability.DERIVED,
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
                hasMaterial ? PdorDataAvailability.DERIVED : PdorDataAvailability.ABSENT,
                hasMaterial ? expectedMaterial : null,
                "rdo_material.quantidade_prevista",
                "Soma das quantidades previstas informadas nos RDOs.",
                false
        );

        put(
                inputs,
                origins,
                missing,
                "activeTeamCount",
                "Equipes vigentes na obra",
                PdorDataAvailability.DIRECT,
                team.teamCount(),
                "equipe + equipe_obra",
                "Equipes com vigência na obra na data de referência.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "activeTeamMemberCount",
                "Membros vigentes nas equipes",
                PdorDataAvailability.DIRECT,
                team.memberCount(),
                "equipe_membro + equipe_obra",
                "Pessoas com participação vigente nas equipes da obra.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "activeTeamFunctionCount",
                "Funções operacionais vigentes",
                PdorDataAvailability.DIRECT,
                team.functionCount(),
                "equipe_membro.funcao_operacional_id",
                "Funções operacionais distintas nas equipes vigentes.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "responsibleTeamMemberCount",
                "Responsáveis vigentes nas equipes",
                PdorDataAvailability.DIRECT,
                team.responsibleMemberCount(),
                "equipe_membro.responsavel",
                "Membros vigentes marcados como responsáveis.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "laborCapacityHours",
                "Capacidade de mão de obra em horas",
                PdorDataAvailability.ABSENT,
                null,
                "equipe_membro",
                "O cadastro registra participação e função, mas não disponibilidade, jornada ou horas de capacidade.",
                false
        );
        if (team.teamCount() == 0) {
            warnings.add("Nenhuma equipe vigente foi encontrada para a obra na data de referência.");
        } else if (team.memberCount() == 0) {
            warnings.add("As equipes vigentes não possuem membros vigentes na data de referência.");
        } else {
            warnings.add(
                    "A quantidade de membros não foi convertida em capacidade de mão de obra porque não há jornada ou disponibilidade estruturada."
            );
            if (team.responsibleMemberCount() == 0) {
                warnings.add("As equipes vigentes não possuem membro responsável definido.");
            }
        }

        put(
                inputs,
                origins,
                missing,
                "activeGeospatialFeatureCount",
                "Geometrias operacionais vigentes",
                PdorDataAvailability.DIRECT,
                activeGeospatialFeatureCount,
                "obra_geometria",
                "Geometrias com vigência na obra na data de referência.",
                false
        );

        put(
                inputs,
                origins,
                missing,
                "weatherObservationCount",
                "RDOs com observação climática",
                PdorDataAvailability.DIRECT,
                weather.observationCount(),
                "rdo.condicao_manha/condicao_tarde/condicao_noite/pluviometria_mm",
                "Contagem de RDOs com condição climática ou pluviometria observada até a referência.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "rainfallMmObserved",
                "Pluviometria observada acumulada",
                weather.rainfallMeasurementCount() > 0
                        ? PdorDataAvailability.DIRECT
                        : PdorDataAvailability.ABSENT,
                weather.rainfallMeasurementCount() > 0 ? weather.rainfallMm() : null,
                "rdo.pluviometria_mm",
                weather.rainfallMeasurementCount() > 0
                        ? "Soma da pluviometria registrada em RDOs até a data de referência."
                        : "Nenhum RDO possui pluviometria estruturada até a data de referência.",
                false
        );
        if (weather.observationCount() == 0) {
            warnings.add(
                    "Clima indisponível: não há condição climática ou pluviometria registrada em RDO; nenhuma condição foi inferida."
            );
        } else if (weather.rainfallMeasurementCount() == 0) {
            warnings.add(
                    "Há condição climática qualitativa em RDO, mas não há pluviometria estruturada para quantificação."
            );
        }
        put(
                inputs,
                origins,
                missing,
                "actualMaterialConsumption",
                "Consumo real de material",
                hasMaterial ? PdorDataAvailability.DERIVED : PdorDataAvailability.ABSENT,
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
                productivity.available() ? PdorDataAvailability.DERIVED : PdorDataAvailability.ABSENT,
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
                productivity.available() ? PdorDataAvailability.DERIVED : PdorDataAvailability.ABSENT,
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
                PdorDataAvailability.ABSENT,
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
                positive(equipamentoHoras30d) ? PdorDataAvailability.AMBIGUOUS : PdorDataAvailability.ABSENT,
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
                programacao.recordCount() > 0 ? PdorDataAvailability.DERIVED : PdorDataAvailability.ABSENT,
                delayedRdos,
                "programacao_operacional + rdo",
                "Datas de programação sem RDO correspondente até a referência.",
                false
        );

        PdorDataAvailability occurrenceAvailability = rdo.rdoCount() > 0
                ? PdorDataAvailability.AMBIGUOUS
                : PdorDataAvailability.ABSENT;
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
                PdorDataAvailability.DIRECT,
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
                sync.hoursSinceLastSync() == null ? PdorDataAvailability.ABSENT : PdorDataAvailability.DERIVED,
                sync.hoursSinceLastSync(),
                "sync_mutacao_cliente",
                "Diferença entre agora e a última mutação recebida/aplicada relacionada.",
                false
        );

        List<Double> productivityLossWeekly = buscarSerieSemanalPerdaProdutividade(
                obra.getId(),
                referenceDate,
                quantity == null ? null : quantity.metric()
        );
        List<Double> materialOverconsumptionWeekly =
                buscarSerieSemanalExcessoMaterial(obra.getId(), referenceDate);

        if (productivityLossWeekly.size() < MINIMUM_CALIBRATION_WEEKS) {
            warnings.add(
                    "Histórico semanal de produtividade insuficiente para apoio histórico ("
                            + productivityLossWeekly.size()
                            + " de " + MINIMUM_CALIBRATION_WEEKS
                            + " semanas); premissas de protótipo serão usadas."
            );
        }
        if (materialOverconsumptionWeekly.size() < MINIMUM_CALIBRATION_WEEKS) {
            warnings.add(
                    "Histórico semanal de material insuficiente para apoio histórico ("
                            + materialOverconsumptionWeekly.size()
                            + " de " + MINIMUM_CALIBRATION_WEEKS
                            + " semanas); premissas de protótipo serão usadas."
            );
        }

        put(
                inputs,
                origins,
                missing,
                "productivityLossWeeklySeries",
                "Série semanal de perda de produtividade",
                productivityLossWeekly.isEmpty()
                        ? PdorDataAvailability.ABSENT
                        : PdorDataAvailability.DERIVED,
                productivityLossWeekly.isEmpty() ? null : productivityLossWeekly,
                "programacao_operacional + rdo_controle_geometrico",
                "Perda semanal = 1 - executado/planejado nas semanas com programação e RDO.",
                false
        );
        put(
                inputs,
                origins,
                missing,
                "materialOverconsumptionWeeklySeries",
                "Série semanal de excesso de consumo de material",
                materialOverconsumptionWeekly.isEmpty()
                        ? PdorDataAvailability.ABSENT
                        : PdorDataAvailability.DERIVED,
                materialOverconsumptionWeekly.isEmpty()
                        ? null
                        : materialOverconsumptionWeekly,
                "rdo_material",
                "Excesso semanal = aplicado/previsto - 1 nas semanas com material informado nos RDOs.",
                false
        );

        inputs.put("referenceDate", referenceDate);
        inputs.put("quantityMetric", quantity == null ? null : quantity.metric());
        inputs.put("programacaoRows", programacao.recordCount());
        inputs.put("rdoRows", rdo.rdoCount());
        inputs.put("scheduleStartDate", programacao.firstDate());
        inputs.put("scheduleEndDate", programacao.latestDate());

        PdorInputBundle.SourceValues sourceValues =
                new PdorInputBundle.SourceValues(
                        finance.hasContractData() ? finance.contractValue() : null,
                        finance.hasRevenueData() ? finance.measuredRevenue() : null,
                        finance.hasRevenueData() ? finance.validatedRevenue() : null,
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
                        finance.hasContractData(),
                        programacao.recordCount() > 0,
                        actualExecuted != null,
                        hasMaterial,
                        positive(equipamentoHoras30d),
                        rdo.rdoCount() > 0,
                        rdo.rdoCount() > 0,
                        sync.hoursSinceLastSync() != null,
                        // Itens contratuais ativos são o baseline formal do
                        // contrato: quando existem, o orçamento é validado.
                        finance.hasContractData(),
                        programacao.recordCount() > 0,
                        totalPlanned != null && actualExecuted != null,
                        DEFAULT_SIMULATION_ITERATIONS
                );

        return new PdorInputBundle(
                obra.getId(),
                codigoObra(obra),
                referenceDate,
                inputs,
                origins,
                warnings,
                missing,
                sourceValues,
                new PdorEngine.HistoricalSeries(
                        productivityLossWeekly,
                        materialOverconsumptionWeekly
                ),
                buscarEvidencias(obra.getId(), referenceDate),
                finance.evidenceIds(),
                finance.evidenceHighWaterMark(),
                finance.coverageCode()
        );
    }

    private TeamStats buscarTeamStats(String obraId, LocalDate referenceDate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(DISTINCT equipe.id) AS total_equipes,
                    COUNT(DISTINCT membro.id) AS total_membros,
                    COUNT(DISTINCT membro.funcao_operacional_id) AS total_funcoes,
                    COUNT(DISTINCT CASE WHEN membro.responsavel = TRUE THEN membro.id END)
                        AS total_responsaveis
                FROM equipe_obra participacao
                JOIN equipe
                  ON equipe.id = participacao.equipe_id
                LEFT JOIN equipe_membro membro
                  ON membro.equipe_id = equipe.id
                 AND DATE(membro.inicio_em) <= ?
                 AND (membro.fim_em IS NULL OR DATE(membro.fim_em) >= ?)
                WHERE participacao.obra_id = ?
                  AND DATE(participacao.inicio_em) <= ?
                  AND (participacao.fim_em IS NULL OR DATE(participacao.fim_em) >= ?)
                  AND DATE(equipe.inicio_validade_em) <= ?
                  AND (equipe.fim_validade_em IS NULL OR DATE(equipe.fim_validade_em) >= ?)
                """,
                (rs, rowNum) -> new TeamStats(
                        rs.getInt("total_equipes"),
                        rs.getInt("total_membros"),
                        rs.getInt("total_funcoes"),
                        rs.getInt("total_responsaveis")
                ),
                referenceDate,
                referenceDate,
                obraId,
                referenceDate,
                referenceDate,
                referenceDate,
                referenceDate
        );
    }

    private int buscarActiveGeospatialFeatureCount(
            String obraId,
            LocalDate referenceDate
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM obra_geometria
                WHERE obra_id = ?
                  AND DATE(valido_desde) <= ?
                  AND (valido_ate IS NULL OR DATE(valido_ate) >= ?)
                """,
                Integer.class,
                obraId,
                referenceDate,
                referenceDate
        );
        return count == null ? 0 : count;
    }

    private WeatherStats buscarWeatherStats(String obraId, LocalDate referenceDate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    SUM(CASE
                            WHEN NULLIF(TRIM(condicao_manha), '') IS NOT NULL
                              OR NULLIF(TRIM(condicao_tarde), '') IS NOT NULL
                              OR NULLIF(TRIM(condicao_noite), '') IS NOT NULL
                              OR pluviometria_mm IS NOT NULL
                                THEN 1
                            ELSE 0
                        END) AS total_observacoes,
                    SUM(CASE WHEN pluviometria_mm IS NOT NULL THEN 1 ELSE 0 END)
                        AS total_medicoes_chuva,
                    SUM(pluviometria_mm) AS pluviometria_mm
                FROM rdo
                WHERE obra_id = ?
                  AND cancelado_em IS NULL
                  AND data_rdo <= ?
                """,
                (rs, rowNum) -> new WeatherStats(
                        rs.getInt("total_observacoes"),
                        rs.getInt("total_medicoes_chuva"),
                        valueOrZero(rs.getBigDecimal("pluviometria_mm"))
                ),
                obraId,
                referenceDate
        );
    }

    private List<PdorEvidenceReference> buscarEvidencias(
            String obraId,
            LocalDate referenceDate
    ) {
        List<PdorEvidenceReference> evidence = new ArrayList<>();
        addEvidence(
                evidence,
                """
                SELECT id, criado_em AS observed_at
                FROM programacao_operacional
                WHERE obra_id = ? AND cancelado_em IS NULL AND data_programacao <= ?
                ORDER BY data_programacao DESC, id
                LIMIT 50
                """,
                "PROGRAMACAO", "programacao_operacional", "PLANEJAMENTO",
                obraId, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT id, criado_em AS observed_at
                FROM rdo
                WHERE obra_id = ? AND cancelado_em IS NULL AND data_rdo <= ?
                ORDER BY data_rdo DESC, id
                LIMIT 50
                """,
                "RDO", "rdo", "EXECUCAO_REAL",
                obraId, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT id, criado_em AS observed_at
                FROM item_contratual
                WHERE obra_id = ? AND status = 'ATIVO'
                  AND (vigencia_inicio IS NULL OR vigencia_inicio <= ?)
                  AND (vigencia_fim IS NULL OR vigencia_fim >= ?)
                ORDER BY codigo_item, id
                LIMIT 50
                """,
                "ITEM_CONTRATUAL", "item_contratual", "BASE_CONTRATUAL",
                obraId, referenceDate, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT id, criado_em AS observed_at
                FROM execucao_servico_rdo
                WHERE obra_id = ? AND cancelada = FALSE AND data_execucao <= ?
                ORDER BY data_execucao DESC, id
                LIMIT 50
                """,
                "EXECUCAO_SERVICO_RDO", "execucao_servico_rdo", "PRODUCAO_E_RECEITA",
                obraId, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT DISTINCT equipe.id, equipe.criado_em AS observed_at
                FROM equipe
                JOIN equipe_obra participacao ON participacao.equipe_id = equipe.id
                WHERE participacao.obra_id = ?
                  AND DATE(participacao.inicio_em) <= ?
                  AND (participacao.fim_em IS NULL OR DATE(participacao.fim_em) >= ?)
                  AND DATE(equipe.inicio_validade_em) <= ?
                  AND (equipe.fim_validade_em IS NULL OR DATE(equipe.fim_validade_em) >= ?)
                ORDER BY equipe.id
                LIMIT 50
                """,
                "EQUIPE", "equipe + equipe_obra", "CONTEXTO_OPERACIONAL",
                obraId, referenceDate, referenceDate, referenceDate, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT DISTINCT membro.id, membro.criado_em AS observed_at
                FROM equipe_membro membro
                JOIN equipe_obra participacao ON participacao.equipe_id = membro.equipe_id
                WHERE participacao.obra_id = ?
                  AND DATE(participacao.inicio_em) <= ?
                  AND (participacao.fim_em IS NULL OR DATE(participacao.fim_em) >= ?)
                  AND DATE(membro.inicio_em) <= ?
                  AND (membro.fim_em IS NULL OR DATE(membro.fim_em) >= ?)
                ORDER BY membro.id
                LIMIT 50
                """,
                "EQUIPE_MEMBRO", "equipe_membro", "COMPOSICAO_DA_EQUIPE",
                obraId, referenceDate, referenceDate, referenceDate, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT id, criado_em AS observed_at
                FROM obra_geometria
                WHERE obra_id = ?
                  AND DATE(valido_desde) <= ?
                  AND (valido_ate IS NULL OR DATE(valido_ate) >= ?)
                ORDER BY valido_desde DESC, id
                LIMIT 50
                """,
                "OBRA_GEOMETRIA", "obra_geometria", "CONTEXTO_GEOGRAFICO",
                obraId, referenceDate, referenceDate
        );
        addEvidence(
                evidence,
                """
                SELECT id, ocorrido_em AS observed_at
                FROM cortex_evento_operacional
                WHERE obra_id = ? AND ocorrido_em < (? + INTERVAL '1 day')
                ORDER BY ocorrido_em DESC, commit_seq DESC
                LIMIT 50
                """,
                "EVENTO_OPERACIONAL", "cortex_evento_operacional", "HISTORICO_ONTOLOGICO",
                obraId, referenceDate
        );
        return evidence;
    }

    private void addEvidence(
            List<PdorEvidenceReference> target,
            String sql,
            String entityType,
            String source,
            String role,
            Object... arguments
    ) {
        target.addAll(jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PdorEvidenceReference(
                        entityType,
                        rs.getString("id"),
                        source,
                        role,
                        toLocalDateTime(rs.getTimestamp("observed_at"))
                ),
                arguments
        ));
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
                    SUM(CASE WHEN CAST(? AS DATE) IS NULL OR data_programacao <= ? THEN extensao_m ELSE 0 END)
                        AS extensao_ate_referencia,
                    SUM(CASE WHEN CAST(? AS DATE) IS NULL OR data_programacao <= ? THEN area_m2 ELSE 0 END)
                        AS area_ate_referencia,
                    SUM(CASE WHEN CAST(? AS DATE) IS NULL OR data_programacao <= ? THEN volume_m3 ELSE 0 END)
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
                  AND (CAST(? AS DATE) IS NULL OR r.data_rdo <= ?)
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
                                THEN EXTRACT(EPOCH FROM (eq.hora_fim - eq.hora_inicio))
                                     / 3600.0 * eq.quantidade
                            ELSE NULL
                        END
                    ) AS horas
                FROM rdo r
                JOIN rdo_equipamento eq
                  ON eq.rdo_id = r.id
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                  AND r.data_rdo BETWEEN (? - INTERVAL '30 days') AND ?
                """,
                (rs, rowNum) -> rs.getBigDecimal("horas"),
                obraId,
                referenceDate,
                referenceDate
        );
    }

    /**
     * Perda de produtividade observada por semana ISO: 1 - executado/planejado,
     * considerando apenas semanas com programação positiva e pelo menos um RDO.
     * A série cresce a cada novo RDO e recalibra o Monte Carlo.
     */
    private List<Double> buscarSerieSemanalPerdaProdutividade(
            String obraId,
            LocalDate referenceDate,
            String quantityMetric
    ) {
        String quantityColumn = switch (quantityMetric == null ? "" : quantityMetric) {
            case "AREA_M2" -> "area_m2";
            case "VOLUME_M3" -> "volume_m3";
            default -> null;
        };

        if (quantityColumn == null) {
            return List.of();
        }

        String sql = """
                SELECT
                    planejado.semana AS semana,
                    planejado.quantidade AS quantidade_planejada,
                    COALESCE(executado.quantidade, 0) AS quantidade_executada
                FROM (
                    SELECT
                        TO_CHAR(data_programacao, 'IYYYIW') AS semana,
                        SUM(%1$s) AS quantidade
                    FROM programacao_operacional
                    WHERE obra_id = ?
                      AND cancelado_em IS NULL
                      AND data_programacao <= ?
                    GROUP BY TO_CHAR(data_programacao, 'IYYYIW')
                    HAVING SUM(%1$s) > 0
                ) planejado
                JOIN (
                    SELECT DISTINCT TO_CHAR(data_rdo, 'IYYYIW') AS semana
                    FROM rdo
                    WHERE obra_id = ?
                      AND cancelado_em IS NULL
                      AND data_rdo <= ?
                ) semanas_com_rdo
                  ON semanas_com_rdo.semana = planejado.semana
                LEFT JOIN (
                    SELECT
                        TO_CHAR(r.data_rdo, 'IYYYIW') AS semana,
                        SUM(cg.%1$s) AS quantidade
                    FROM rdo r
                    JOIN rdo_controle_geometrico cg
                      ON cg.rdo_id = r.id
                    WHERE r.obra_id = ?
                      AND r.cancelado_em IS NULL
                      AND r.data_rdo <= ?
                    GROUP BY TO_CHAR(r.data_rdo, 'IYYYIW')
                ) executado
                  ON executado.semana = planejado.semana
                ORDER BY planejado.semana
                """.formatted(quantityColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    BigDecimal planned = rs.getBigDecimal("quantidade_planejada");
                    BigDecimal executed = rs.getBigDecimal("quantidade_executada");
                    return weeklyLoss(planned, executed);
                },
                obraId,
                referenceDate,
                obraId,
                referenceDate,
                obraId,
                referenceDate
        );
    }

    /**
     * Excesso de consumo de material por semana ISO: aplicado/previsto - 1,
     * considerando apenas semanas com previsto e aplicado positivos nos RDOs.
     */
    private List<Double> buscarSerieSemanalExcessoMaterial(
            String obraId,
            LocalDate referenceDate
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    TO_CHAR(r.data_rdo, 'IYYYIW') AS semana,
                    SUM(mat.quantidade_prevista) AS prevista,
                    SUM(mat.quantidade_aplicada) AS aplicada
                FROM rdo r
                JOIN rdo_material mat
                  ON mat.rdo_id = r.id
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                  AND r.data_rdo <= ?
                GROUP BY TO_CHAR(r.data_rdo, 'IYYYIW')
                HAVING SUM(mat.quantidade_prevista) > 0
                   AND SUM(mat.quantidade_aplicada) > 0
                ORDER BY semana
                """,
                (rs, rowNum) -> {
                    BigDecimal expected = rs.getBigDecimal("prevista");
                    BigDecimal applied = rs.getBigDecimal("aplicada");
                    return weeklyOverconsumption(expected, applied);
                },
                obraId,
                referenceDate
        );
    }

    private Double weeklyLoss(BigDecimal planned, BigDecimal executed) {
        if (!positive(planned)) {
            return 0.0;
        }

        double ratio = valueOrZero(executed)
                .divide(planned, 6, RoundingMode.HALF_UP)
                .doubleValue();
        double loss = Math.max(0.0, Math.min(1.0, 1.0 - ratio));
        return roundSeriesValue(loss);
    }

    private Double weeklyOverconsumption(BigDecimal expected, BigDecimal applied) {
        if (!positive(expected)) {
            return 0.0;
        }

        double ratio = valueOrZero(applied)
                .divide(expected, 6, RoundingMode.HALF_UP)
                .doubleValue();
        double overconsumption = Math.max(0.0, ratio - 1.0);
        return roundSeriesValue(overconsumption);
    }

    private double roundSeriesValue(double value) {
        return BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
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
                    EXTRACT(EPOCH FROM (
                        CURRENT_TIMESTAMP(6)
                        - MAX(COALESCE(aplicada_em, recebida_em))
                    )) / 3600 AS horas_ultimo_sync
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
        ContractRevenueScope scope = jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) FILTER (WHERE item.status = 'ATIVO') AS item_count,
                    SUM(item.valor_total) FILTER (
                        WHERE item.status = 'ATIVO'
                    ) AS contract_value,
                    (
                        SELECT COUNT(*)
                        FROM execucao_servico_rdo execution
                        WHERE execution.obra_id = ?
                          AND execution.data_execucao <= ?
                          AND execution.cancelada = FALSE
                          AND execution.producao_rejeitada = FALSE
                          AND execution.retrabalho = FALSE
                          AND execution.status_validacao = 'VALIDADA'
                    ) AS eligible_rows
                FROM item_contratual item
                WHERE item.obra_id = ?
                """,
                (rs, rowNumber) -> new ContractRevenueScope(
                        rs.getInt("item_count"),
                        rs.getBigDecimal("contract_value"),
                        rs.getInt("eligible_rows")
                ),
                obraId,
                referenceDate,
                obraId
        );

        FinanceEvidenceSnapshot evidenceSnapshot = jdbcTemplate.query(
                """
                WITH evidence_snapshot AS (
                    SELECT COALESCE((
                        SELECT ultima_commit_seq
                        FROM cortex_evento_commit_sequence
                        WHERE id = 1
                    ), 0)::bigint AS high_water_mark
                ), accepted_evidence AS (
                SELECT execution.revenue_evidence_id,
                       execution.revenue_amount,
                       execution.quantidade_executada,
                       event.commit_seq
                FROM execucao_servico_rdo execution
                JOIN cortex_evento_operacional event
                  ON event.id = execution.revenue_event_id
                 AND event.tipo_entidade = 'RDO_EXECUTION'
                 AND event.tipo_evento = 'RDO_SERVICE_EXECUTED'
                 AND event.entidade_id = execution.id
                 AND event.obra_id = execution.obra_id
                 AND event.rdo_id = execution.rdo_id
                 AND event.payload_json ->> 'schemaVersion' = '1'
                 AND event.payload_json ->> 'status' = 'ACCEPTED'
                 AND event.payload_json ->> 'rdoId' = execution.rdo_id
                 AND event.payload_json ->> 'obraId' = execution.obra_id
                 AND event.payload_json ->> 'serviceId' = execution.service_id
                 AND event.payload_json ->> 'priceVersionId' = execution.price_version_id
                 AND event.payload_json ->> 'revenueEvidenceId' = execution.revenue_evidence_id
                 AND event.payload_json ->> 'unit' = execution.unidade_medida
                 AND event.payload_json ->> 'currency' = execution.currency
                 AND CASE
                     WHEN event.payload_json ->> 'acceptedQuantity'
                          ~ '^[0-9]+([.][0-9]+)?$'
                     THEN (event.payload_json ->> 'acceptedQuantity')::numeric
                          = execution.quantidade_executada
                     ELSE FALSE
                 END
                 AND CASE
                     WHEN event.payload_json ->> 'unitPrice'
                          ~ '^[0-9]+([.][0-9]+)?$'
                     THEN (event.payload_json ->> 'unitPrice')::numeric
                          = execution.unit_price_snapshot
                     ELSE FALSE
                 END
                 AND CASE
                     WHEN event.payload_json ->> 'revenue'
                          ~ '^[0-9]+([.][0-9]+)?$'
                     THEN (event.payload_json ->> 'revenue')::numeric
                          = execution.revenue_amount
                     ELSE FALSE
                 END
                 AND jsonb_typeof(event.entidades_relacionadas_json) = 'array'
                 AND jsonb_array_length(event.entidades_relacionadas_json) = 5
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object('tipo', 'RDO', 'id', execution.rdo_id)
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object('tipo', 'WORKSITE', 'id', execution.obra_id)
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object('tipo', 'SERVICE', 'id', execution.service_id)
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object(
                         'tipo', 'SERVICE_PRICE_VERSION',
                         'id', execution.price_version_id
                     )
                 )
                 AND event.entidades_relacionadas_json @> jsonb_build_array(
                     jsonb_build_object(
                         'tipo', 'REVENUE_EVIDENCE',
                         'id', execution.revenue_evidence_id
                     )
                 )
                CROSS JOIN evidence_snapshot snapshot
                WHERE execution.obra_id = ?
                  AND execution.data_execucao <= ?
                  AND execution.revenue_coverage_code = 'ACCEPTED_EXACT'
                  AND execution.revenue_evidence_id IS NOT NULL
                  AND execution.revenue_event_id IS NOT NULL
                  AND execution.status_validacao = 'VALIDADA'
                  AND execution.cancelada = FALSE
                  AND execution.producao_rejeitada = FALSE
                  AND execution.retrabalho = FALSE
                  AND event.commit_seq <= snapshot.high_water_mark
                )
                SELECT snapshot.high_water_mark,
                       accepted.revenue_evidence_id,
                       accepted.revenue_amount,
                       accepted.quantidade_executada,
                       accepted.commit_seq
                FROM evidence_snapshot snapshot
                LEFT JOIN accepted_evidence accepted ON TRUE
                ORDER BY accepted.revenue_evidence_id
                """,
                rs -> {
                    long highWaterMark = 0L;
                    List<AcceptedRevenueRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        highWaterMark = rs.getLong("high_water_mark");
                        String evidenceId = rs.getString("revenue_evidence_id");
                        if (evidenceId != null) {
                            rows.add(new AcceptedRevenueRow(
                                    evidenceId,
                                    rs.getBigDecimal("revenue_amount"),
                                    rs.getBigDecimal("quantidade_executada"),
                                    rs.getLong("commit_seq")
                            ));
                        }
                    }
                    return new FinanceEvidenceSnapshot(
                            highWaterMark,
                            List.copyOf(rows)
                    );
                },
                obraId,
                referenceDate
        );
        List<AcceptedRevenueRow> acceptedRows = evidenceSnapshot.acceptedRows();
        long resolvedHighWaterMark = evidenceSnapshot.highWaterMark();
        long acceptedEvidenceMaxCommit = acceptedRows.stream()
                .mapToLong(AcceptedRevenueRow::commitSequence)
                .max()
                .orElse(0L);
        if (acceptedEvidenceMaxCommit > resolvedHighWaterMark) {
            throw new IllegalStateException("PDOR_EVIDENCE_HIGH_WATER_INVALID");
        }
        BigDecimal acceptedRevenue = acceptedRows.stream()
                .map(AcceptedRevenueRow::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal acceptedQuantity = acceptedRows.stream()
                .map(AcceptedRevenueRow::acceptedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> evidenceIds = acceptedRows.stream()
                .map(AcceptedRevenueRow::evidenceId)
                .distinct()
                .sorted()
                .toList();
        int eligibleRows = scope == null ? 0 : scope.eligibleRows();
        String coverageCode = acceptedRows.isEmpty()
                ? "NO_ACCEPTED_EVIDENCE"
                : acceptedRows.size() == eligibleRows
                        ? "COMPLETE_ACCEPTED_EXACT"
                        : "PARTIAL_ACCEPTED_EXACT";

        return new FinanceStats(
                scope == null ? 0 : scope.itemCount(),
                scope == null ? null : scope.contractValue(),
                acceptedRows.size(),
                eligibleRows,
                acceptedRevenue,
                acceptedRevenue,
                acceptedQuantity,
                evidenceIds,
                resolvedHighWaterMark,
                coverageCode
        );
    }

    private ServiceQuantityStats buscarServiceQuantityStats(
            String obraId,
            LocalDate referenceDate,
            BigDecimal acceptedQuantity
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
                          AND cancelada = FALSE
                    ) AS execution_count,
                    (
                        SELECT COUNT(DISTINCT unidade_medida)
                        FROM execucao_servico_rdo
                        WHERE obra_id = ?
                          AND data_execucao <= ?
                          AND cancelada = FALSE
                    ) AS execution_unit_count
                """,
                (rs, rowNumber) -> new ServiceQuantityStats(
                        rs.getInt("item_count"),
                        rs.getInt("contract_unit_count"),
                        rs.getBigDecimal("total_planned"),
                        rs.getInt("execution_count"),
                        rs.getInt("execution_unit_count"),
                        valueOrZero(acceptedQuantity)
                ),
                obraId,
                obraId,
                obraId,
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
            Map<String, PdorInputOrigin> origins,
            List<String> missing,
            String field,
            String label,
            PdorDataAvailability availability,
            Object value,
            String source,
            String detail,
            boolean required
    ) {
        inputs.put(field, value);
        origins.put(
                field,
                new PdorInputOrigin(
                        field,
                        label,
                        availability,
                        value,
                        source,
                        detail,
                        required
                )
        );

        if (required && (availability == PdorDataAvailability.ABSENT || value == null)) {
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

    private LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
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

    private record TeamStats(
            int teamCount,
            int memberCount,
            int functionCount,
            int responsibleMemberCount
    ) {
    }

    private record WeatherStats(
            int observationCount,
            int rainfallMeasurementCount,
            BigDecimal rainfallMm
    ) {
    }

    private record FinanceStats(
            int itemCount,
            BigDecimal contractValue,
            int acceptedRows,
            int eligibleRows,
            BigDecimal measuredRevenue,
            BigDecimal validatedRevenue,
            BigDecimal acceptedQuantity,
            List<String> evidenceIds,
            long evidenceHighWaterMark,
            String coverageCode
    ) {
        private boolean hasContractData() {
            return itemCount > 0
                    && contractValue != null
                    && contractValue.compareTo(BigDecimal.ZERO) > 0;
        }

        private boolean hasRevenueData() {
            return acceptedRows > 0;
        }
    }

    private record ContractRevenueScope(
            int itemCount,
            BigDecimal contractValue,
            int eligibleRows
    ) {
    }

    private record AcceptedRevenueRow(
            String evidenceId,
            BigDecimal revenue,
            BigDecimal acceptedQuantity,
            long commitSequence
    ) {
    }

    private record FinanceEvidenceSnapshot(
            long highWaterMark,
            List<AcceptedRevenueRow> acceptedRows
    ) {
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
