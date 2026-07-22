package com.projeto.cortex.rdos;

import com.projeto.cortex.financeiro.revenue.RevenueCalculator;
import com.projeto.cortex.financeiro.revenue.RevenueEvidence;
import com.projeto.cortex.financeiro.revenue.RevenueOntologyPublisher;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RdoOperationalDetailService {

    private static final String FONTE = "RDO_API";
    private static final BigDecimal MINUTOS_DIA_PADRAO =
            BigDecimal.valueOf(480);
    private static final Pattern CODIGO_SERVICO_PATTERN =
            Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)*)\\s+-\\s+(.+?)\\s*$");

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;
    private final RevenueOntologyPublisher revenuePublisher;
    private final RevenueCalculator revenueCalculator = new RevenueCalculator();

    public RdoOperationalDetailService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this(jdbcTemplate, memoryService, new RevenueOntologyPublisher(memoryService));
    }

    @Autowired
    public RdoOperationalDetailService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService,
            RevenueOntologyPublisher revenuePublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
        this.revenuePublisher = revenuePublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RdoOperationalDetails substituirDetalhes(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<RdoCreateRequest.ServicoExecutadoItem> servicosExecutados,
            List<RdoCreateRequest.AlocacaoColaboradorItem> alocacoesColaboradores
    ) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {
                    // PostgreSQL returns void; reaching the row means the lock is held.
                },
                "rdo-revenue:" + rdoId
        );
        List<RemovedChild> previousServices = listarFilhosAtuais(
                "execucao_servico_rdo", rdoId, "servico_nome"
        );
        List<RemovedChild> previousAllocations = listarFilhosAtuais(
                "alocacao_colaborador", rdoId, "colaborador_id"
        );

        Map<String, ExistingAcceptedExecution> acceptedById =
                listarExecucoesAceitas(rdoId);
        List<RdoCreateRequest.ServicoExecutadoItem> incomingServices =
                listaSegura(servicosExecutados);
        validarReplayAceitoAntesDeMutar(acceptedById, incomingServices);
        List<PreparedService> preparedServices = prepararServicos(
                rdoId, obraId, programacaoId, dataRdo, turnoRdo,
                incomingServices, acceptedById
        );

        apagarDetalhesMutaveis(rdoId);

        List<RdoResponse.ServicoExecutadoItem> servicos =
                inserirServicos(
                        rdoId,
                        obraId,
                        programacaoId,
                        dataRdo,
                        turnoRdo,
                        preparedServices
                );

        List<RdoResponse.AlocacaoColaboradorItem> alocacoes =
                inserirAlocacoes(
                        rdoId,
                        obraId,
                        programacaoId,
                        dataRdo,
                        turnoRdo,
                        alocacoesColaboradores
                );

        inativarAusentes(
                previousServices,
                servicos.stream().map(RdoResponse.ServicoExecutadoItem::id).toList(),
                "EXECUCAO_SERVICO_RDO",
                "execucao_servico_rdo",
                rdoId
        );
        inativarAusentes(
                previousAllocations,
                alocacoes.stream().map(RdoResponse.AlocacaoColaboradorItem::id).toList(),
                "ALOCACAO_COLABORADOR",
                "alocacao_colaborador",
                rdoId
        );

        return new RdoOperationalDetails(servicos, alocacoes);
    }

    /**
     * Replaces the mutable service imported from a historical spreadsheet.
     * Public RDO requests never call this path: provenance must match the
     * immutable import coordinates already persisted on the RDO.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RdoResponse.ServicoExecutadoItem substituirServicoImportadoHistorico(
            RdoHistoricalImportServiceCommand command
    ) {
        if (command == null) {
            badRequest("RDO_HISTORICAL_IMPORT_COMMAND_REQUIRED");
        }
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> {
                    // PostgreSQL returns void; reaching the row means the lock is held.
                },
                "rdo-revenue:" + command.rdoId()
        );
        validarProvenienciaImportacaoHistorica(command);
        if (!listarExecucoesAceitas(command.rdoId()).isEmpty()) {
            conflict("RDO_ACCEPTED_EXECUTION_OMITTED");
        }
        if (command.serviceName() == null || command.serviceName().isBlank()) {
            badRequest("RDO_HISTORICAL_SERVICE_NAME_REQUIRED");
        }
        if (command.quantity() == null
                || command.quantity().compareTo(BigDecimal.ZERO) < 0) {
            badRequest("RDO_EXECUTION_QUANTITY_INVALID");
        }
        String unit = resolverUnidadeImportacaoHistorica(command);
        String executionId = idOuNovo(
                command.executionId(), "historicalImport.executionId"
        );
        List<RemovedChild> previousServices = listarFilhosAtuais(
                "execucao_servico_rdo", command.rdoId(), "servico_nome"
        );
        apagarServicosMutaveis(command.rdoId());
        BigDecimal quantity = escala3(command.quantity());
        String serviceName = command.serviceName().strip();
        String turn = nuloSeVazio(command.turn());
        String executionKey = sha256(String.join(
                "|", command.importId(), command.rdoId(), executionId,
                serviceName, quantity.toPlainString(), unit,
                nullToEmpty(command.initialSegment()),
                nullToEmpty(command.finalSegment())
        ));
        jdbcTemplate.update(
                """
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, programacao_id, servico_nome,
                    item_contratual_id, service_id, price_version_id,
                    quantidade_executada, unidade_medida, trecho_inicial,
                    trecho_final, localizacao, data_execucao, turno,
                    status_validacao, estado_receita, retrabalho,
                    producao_rejeitada, fonte, chave_execucao, observacoes,
                    unit_price_snapshot, currency, revenue_amount,
                    revenue_coverage_code, revenue_evidence_id,
                    revenue_event_id, accepted_at
                )
                SELECT ?, rdo.id, rdo.obra_id, rdo.programacao_id, ?,
                       NULL, NULL, NULL, ?, ?, ?, ?, ?, rdo.data_rdo, ?,
                       'REGISTRADA', 'PRODUCAO_REGISTRADA', FALSE, FALSE,
                       'IMPORTACAO_HISTORICA', ?, ?, NULL, NULL, 0,
                       'HISTORICAL_UNPRICED', NULL, NULL, NULL
                FROM rdo
                WHERE rdo.id = ? AND rdo.obra_id = ?
                """,
                executionId, serviceName, quantity, unit,
                nuloSeVazio(command.initialSegment()),
                nuloSeVazio(command.finalSegment()),
                nuloSeVazio(command.location()), turn, executionKey,
                command.observations(), command.rdoId(), command.obraId()
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rdoId", command.rdoId());
        metadata.put("obraId", command.obraId());
        metadata.put("importId", command.importId());
        metadata.put("coverage", "HISTORICAL_UNPRICED");
        metadata.put("quantity", quantity.toPlainString());
        metadata.put("unit", unit);
        memoryService.registrarObjeto(
                "RDO_SERVICE_EXECUTED", executionId, executionId,
                serviceName, "RECORDED", "RDO_HISTORICAL_IMPORT",
                "execucao_servico_rdo", metadata
        );
        memoryService.registrarRelacaoAtiva(
                "RDO_SERVICE_EXECUTED", executionId,
                "RDO", command.rdoId(), "EXECUTED_IN",
                "RDO_HISTORICAL_IMPORT", "Historical service imported in RDO."
        );
        inativarAusentes(
                previousServices, List.of(executionId),
                "RDO_SERVICE_EXECUTED", "execucao_servico_rdo", command.rdoId()
        );
        return new RdoResponse.ServicoExecutadoItem(
                executionId, null, null, serviceName, null, quantity, unit,
                nuloSeVazio(command.initialSegment()),
                nuloSeVazio(command.finalSegment()),
                nuloSeVazio(command.location()), turn, "REGISTRADA",
                "PRODUCAO_REGISTRADA", "HISTORICAL_UNPRICED",
                null, null, BigDecimal.ZERO.setScale(2), null, null, null,
                false, false, command.observations()
        );
    }

    private void validarProvenienciaImportacaoHistorica(
            RdoHistoricalImportServiceCommand command
    ) {
        List<String> matches = jdbcTemplate.query(
                """
                SELECT rdo.id
                FROM rdo
                JOIN importacao_rdo imported ON imported.id = rdo.importacao_rdo_id
                WHERE rdo.id = ?
                  AND rdo.obra_id = ?
                  AND rdo.fonte_criacao = 'IMPORTACAO_HISTORICA'
                  AND rdo.importacao_rdo_id = ?
                  AND rdo.fonte_arquivo = ?
                  AND imported.nome_arquivo = ?
                  AND rdo.aba_origem = ?
                  AND rdo.linha_origem = ?
                FOR UPDATE OF rdo
                """,
                (rs, rowNumber) -> rs.getString("id"),
                command.rdoId(), command.obraId(), command.importId(),
                command.fileName(), command.fileName(), command.sheetName(),
                command.rowNumber()
        );
        if (matches.size() != 1) {
            conflict("RDO_HISTORICAL_IMPORT_PROVENANCE_INVALID");
        }
    }

    private String resolverUnidadeImportacaoHistorica(
            RdoHistoricalImportServiceCommand command
    ) {
        String informedUnit = normalizeUnit(command.unit());
        String itemContractId = nuloSeVazio(command.itemContractId());
        if (itemContractId == null) {
            if (informedUnit == null) {
                badRequest("RDO_EXECUTION_UNIT_REQUIRED");
            }
            return informedUnit;
        }
        List<String> itemUnits = jdbcTemplate.query(
                """
                SELECT upper(trim(item.unidade_medida)) AS unidade_medida
                FROM item_contratual item
                JOIN rdo ON rdo.id = ? AND rdo.obra_id = item.obra_id
                WHERE item.id = ?
                  AND item.obra_id = ?
                  AND item.status = 'ATIVO'
                  AND (item.vigencia_inicio IS NULL OR item.vigencia_inicio <= rdo.data_rdo)
                  AND (item.vigencia_fim IS NULL OR item.vigencia_fim >= rdo.data_rdo)
                """,
                (rs, rowNumber) -> rs.getString("unidade_medida"),
                command.rdoId(), itemContractId, command.obraId()
        );
        if (itemUnits.size() != 1) {
            badRequest("RDO_HISTORICAL_ITEM_CONTRACT_INVALID");
        }
        String historicalUnit = itemUnits.getFirst();
        if (informedUnit != null && !informedUnit.equals(historicalUnit)) {
            badRequest("RDO_HISTORICAL_ITEM_UNIT_MISMATCH");
        }
        return historicalUnit;
    }

    private List<RemovedChild> listarFilhosAtuais(
            String table,
            String rdoId,
            String nameColumn
    ) {
        return jdbcTemplate.query(
                "SELECT id, " + nameColumn + " AS child_name FROM " + table + " WHERE rdo_id = ?",
                (rs, rowNumber) -> new RemovedChild(
                        rs.getString("id"),
                        rs.getString("child_name")
                ),
                rdoId
        );
    }

    private void inativarAusentes(
            List<RemovedChild> previous,
            List<String> currentIds,
            String entityType,
            String entityTable,
            String rdoId
    ) {
        for (RemovedChild child : previous) {
            if (currentIds.contains(child.id())) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("rdoId", rdoId);
            memoryService.registrarObjeto(
                    entityType,
                    child.id(),
                    child.id(),
                    primeiroNaoVazio(child.name(), child.id()),
                    "INATIVO",
                    FONTE,
                    entityTable,
                    metadata
            );
            memoryService.encerrarRelacoesAtivasDaEntidade(entityType, child.id());
        }
    }

    public List<RdoResponse.ServicoExecutadoItem> listarServicos(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    service_id,
                    price_version_id,
                    servico_nome,
                    item_contratual_id,
                    quantidade_executada,
                    unidade_medida,
                    trecho_inicial,
                    trecho_final,
                    localizacao,
                    turno,
                    status_validacao,
                    estado_receita,
                    revenue_coverage_code,
                    unit_price_snapshot,
                    currency,
                    revenue_amount,
                    revenue_evidence_id,
                    revenue_event_id,
                    accepted_at,
                    retrabalho,
                    producao_rejeitada,
                    observacoes
                FROM execucao_servico_rdo
                WHERE rdo_id = ?
                  AND cancelada = FALSE
                ORDER BY data_execucao, servico_nome, id
                """,
                (rs, rowNumber) -> new RdoResponse.ServicoExecutadoItem(
                        rs.getString("id"),
                        rs.getString("service_id"),
                        rs.getString("price_version_id"),
                        rs.getString("servico_nome"),
                        rs.getString("item_contratual_id"),
                        rs.getBigDecimal("quantidade_executada"),
                        rs.getString("unidade_medida"),
                        rs.getString("trecho_inicial"),
                        rs.getString("trecho_final"),
                        rs.getString("localizacao"),
                        rs.getString("turno"),
                        rs.getString("status_validacao"),
                        rs.getString("estado_receita"),
                        rs.getString("revenue_coverage_code"),
                        rs.getBigDecimal("unit_price_snapshot"),
                        rs.getString("currency"),
                        rs.getBigDecimal("revenue_amount"),
                        rs.getString("revenue_evidence_id"),
                        rs.getString("revenue_event_id"),
                        rs.getTimestamp("accepted_at") == null
                                ? null
                                : rs.getTimestamp("accepted_at").toInstant(),
                        rs.getBoolean("retrabalho"),
                        rs.getBoolean("producao_rejeitada"),
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    public List<RdoResponse.AlocacaoColaboradorItem> listarAlocacoes(
            String rdoId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    colaborador_id,
                    equipe,
                    servico_nome,
                    hora_inicio,
                    hora_fim,
                    minutos,
                    percentual_dia,
                    turno,
                    funcao,
                    centro_custo,
                    tipo_alocacao,
                    fonte,
                    status,
                    observacoes
                FROM alocacao_colaborador
                WHERE rdo_id = ?
                  AND status <> 'CANCELADA'
                ORDER BY data_alocacao, hora_inicio, colaborador_id, id
                """,
                (rs, rowNumber) -> new RdoResponse.AlocacaoColaboradorItem(
                        rs.getString("id"),
                        rs.getString("colaborador_id"),
                        rs.getString("equipe"),
                        rs.getString("servico_nome"),
                        toLocalTime(rs.getTime("hora_inicio")),
                        toLocalTime(rs.getTime("hora_fim")),
                        rs.getInt("minutos"),
                        rs.getBigDecimal("percentual_dia"),
                        rs.getString("turno"),
                        rs.getString("funcao"),
                        rs.getString("centro_custo"),
                        rs.getString("tipo_alocacao"),
                        rs.getString("fonte"),
                        rs.getString("status"),
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    private void apagarDetalhesMutaveis(String rdoId) {
        jdbcTemplate.update(
                "DELETE FROM alocacao_colaborador WHERE rdo_id = ?",
                rdoId
        );
        apagarServicosMutaveis(rdoId);
    }

    private void apagarServicosMutaveis(String rdoId) {
        jdbcTemplate.update(
                """
                DELETE FROM execucao_servico_rdo
                WHERE rdo_id = ?
                  AND revenue_evidence_id IS NULL
                """,
                rdoId
        );
    }

    private Map<String, ExistingAcceptedExecution> listarExecucoesAceitas(
            String rdoId
    ) {
        Map<String, ExistingAcceptedExecution> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT execution.id,
                       execution.service_id,
                       execution.price_version_id,
                       execution.servico_nome,
                       execution.item_contratual_id,
                       execution.quantidade_executada,
                       execution.unidade_medida,
                       execution.trecho_inicial,
                       execution.trecho_final,
                       execution.localizacao,
                       execution.turno,
                       execution.status_validacao,
                       execution.estado_receita,
                       execution.retrabalho,
                       execution.producao_rejeitada,
                       execution.observacoes,
                       execution.unit_price_snapshot,
                       execution.currency,
                       execution.revenue_amount,
                       execution.revenue_coverage_code,
                       execution.revenue_evidence_id,
                       execution.revenue_event_id,
                       execution.accepted_at,
                       service.codigo AS service_code,
                       price.versao AS price_version
                FROM execucao_servico_rdo execution
                JOIN catalogo_servico service ON service.id = execution.service_id
                JOIN service_price_version price ON price.id = execution.price_version_id
                WHERE execution.rdo_id = ?
                  AND execution.revenue_evidence_id IS NOT NULL
                ORDER BY execution.id
                """,
                rs -> {
                    ExistingAcceptedExecution execution =
                            new ExistingAcceptedExecution(
                                    rs.getString("id"),
                                    rs.getString("service_id"),
                                    rs.getString("price_version_id"),
                                    rs.getString("servico_nome"),
                                    rs.getString("item_contratual_id"),
                                    rs.getBigDecimal("quantidade_executada"),
                                    rs.getString("unidade_medida"),
                                    rs.getString("trecho_inicial"),
                                    rs.getString("trecho_final"),
                                    rs.getString("localizacao"),
                                    rs.getString("turno"),
                                    rs.getString("status_validacao"),
                                    rs.getString("estado_receita"),
                                    rs.getBoolean("retrabalho"),
                                    rs.getBoolean("producao_rejeitada"),
                                    rs.getString("observacoes"),
                                    rs.getBigDecimal("unit_price_snapshot"),
                                    rs.getString("currency"),
                                    rs.getBigDecimal("revenue_amount"),
                                    rs.getString("revenue_coverage_code"),
                                    rs.getString("revenue_evidence_id"),
                                    rs.getString("revenue_event_id"),
                                    rs.getTimestamp("accepted_at").toInstant(),
                                    rs.getString("service_code"),
                                    rs.getInt("price_version")
                            );
                    result.put(execution.id(), execution);
                },
                rdoId
        );
        return result;
    }

    private void validarReplayAceitoAntesDeMutar(
            Map<String, ExistingAcceptedExecution> acceptedById,
            List<RdoCreateRequest.ServicoExecutadoItem> incoming
    ) {
        Map<String, RdoCreateRequest.ServicoExecutadoItem> incomingById =
                new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        for (RdoCreateRequest.ServicoExecutadoItem item : incoming) {
            if (item.id() == null || item.id().isBlank()) {
                continue;
            }
            String id = idOuNovo(item.id(), "servicosExecutados.id");
            if (incomingById.put(id, item) != null) {
                duplicateIds.add(id);
            }
        }
        if (!duplicateIds.isEmpty()) {
            conflict("RDO_EXECUTION_ID_DUPLICATED");
        }
        for (ExistingAcceptedExecution accepted : acceptedById.values()) {
            RdoCreateRequest.ServicoExecutadoItem item = incomingById.get(accepted.id());
            if (item == null) {
                conflict("RDO_ACCEPTED_EXECUTION_OMITTED");
            }
            if (!exactAcceptedReplay(accepted, item)) {
                conflict("RDO_ACCEPTED_EXECUTION_IMMUTABLE");
            }
        }
    }

    private boolean exactAcceptedReplay(
            ExistingAcceptedExecution accepted,
            RdoCreateRequest.ServicoExecutadoItem item
    ) {
        if (item.quantidadeExecutada() == null) {
            return false;
        }
        if (item.itemContratualId() != null && !item.itemContratualId().isBlank()) {
            return false;
        }
        return java.util.Objects.equals(accepted.serviceId(), nuloSeVazio(item.serviceId()))
                && java.util.Objects.equals(
                        accepted.priceVersionId(), nuloSeVazio(item.priceVersionId())
                )
                && accepted.quantity().compareTo(escala3(item.quantidadeExecutada())) == 0
                && java.util.Objects.equals(accepted.unit(), normalizeUnit(item.unidade()))
                && java.util.Objects.equals(accepted.initialSegment(), nuloSeVazio(item.trechoInicial()))
                && java.util.Objects.equals(accepted.finalSegment(), nuloSeVazio(item.trechoFinal()))
                && java.util.Objects.equals(accepted.location(), nuloSeVazio(item.localizacao()))
                && java.util.Objects.equals(accepted.turn(), nuloSeVazio(item.turno()))
                && java.util.Objects.equals(
                        accepted.validationStatus(), normalizarStatusValidacao(item.statusValidacao())
                )
                && accepted.rework() == Boolean.TRUE.equals(item.retrabalho())
                && accepted.productionRejected() == Boolean.TRUE.equals(item.producaoRejeitada())
                && java.util.Objects.equals(accepted.observations(), item.observacoes());
    }

    private List<PreparedService> prepararServicos(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<RdoCreateRequest.ServicoExecutadoItem> items,
            Map<String, ExistingAcceptedExecution> acceptedById
    ) {
        List<PreparedService> prepared = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RdoCreateRequest.ServicoExecutadoItem item : items) {
            String id = idOuNovo(item.id(), "servicosExecutados.id");
            if (!ids.add(id)) {
                conflict("RDO_EXECUTION_ID_DUPLICATED");
            }
            ExistingAcceptedExecution replay = acceptedById.get(id);
            if (replay != null) {
                prepared.add(PreparedService.replay(item, replay));
                continue;
            }
            if (item.quantidadeExecutada() == null
                    || item.quantidadeExecutada().compareTo(BigDecimal.ZERO) < 0) {
                badRequest("RDO_EXECUTION_QUANTITY_INVALID");
            }
            if (item.itemContratualId() != null && !item.itemContratualId().isBlank()) {
                badRequest("RDO_LEGACY_ITEM_CONTRACT_UNSUPPORTED");
            }
            CatalogService service = buscarServicoCatalogado(item.serviceId());
            String unit = normalizeUnit(item.unidade());
            if (unit == null) {
                badRequest("RDO_EXECUTION_UNIT_REQUIRED");
            }
            String status = normalizarStatusValidacao(item.statusValidacao());
            boolean rework = Boolean.TRUE.equals(item.retrabalho());
            boolean productionRejected = Boolean.TRUE.equals(item.producaoRejeitada());
            boolean accepted = revenueCalculator.isAccepted(
                    status, rework, productionRejected
            );
            PriceChoice price = null;
            if (item.priceVersionId() != null && !item.priceVersionId().isBlank()) {
                PriceChoice validatedPrice = buscarPrecoExato(
                        item.priceVersionId(), obraId, service.id(), unit, dataRdo
                );
                if (accepted) {
                    price = validatedPrice;
                }
            } else if (accepted) {
                badRequest("RDO_REVENUE_PRICE_REQUIRED");
            }

            BigDecimal quantity = escala3(item.quantidadeExecutada());
            BigDecimal snapshot = accepted ? price.unitPrice() : null;
            BigDecimal revenue = revenueCalculator.calculate(
                    status, rework, productionRejected, quantity,
                    accepted ? snapshot : BigDecimal.ZERO
            );
            String evidenceId = accepted
                    ? stableUuid("cortex:revenue-evidence:v1:" + id)
                    : null;
            String eventId = accepted
                    ? stableUuid("cortex:revenue-event:v1:" + id)
                    : null;
            prepared.add(new PreparedService(
                    item, id, service, price, quantity, unit,
                    primeiroNaoVazio(item.turno(), turnoRdo), status,
                    accepted ? "RECEITA_MEDIDA" : revenueState(status),
                    coverageCode(status, rework, productionRejected),
                    snapshot, revenue, evidenceId, eventId,
                    rework, productionRejected, null
            ));
        }
        return prepared;
    }

    private CatalogService buscarServicoCatalogado(String rawServiceId) {
        String serviceId = normalizedId(rawServiceId);
        if (serviceId == null) {
            badRequest("RDO_REVENUE_SERVICE_REQUIRED");
        }
        List<CatalogService> services = jdbcTemplate.query(
                """
                SELECT id, codigo, nome
                FROM catalogo_servico
                WHERE id = ? AND status = 'ACTIVE'
                """,
                (rs, rowNumber) -> new CatalogService(
                        rs.getString("id"), rs.getString("codigo"), rs.getString("nome")
                ),
                serviceId
        );
        if (services.isEmpty()) {
            badRequest("RDO_REVENUE_SERVICE_INVALID");
        }
        return services.getFirst();
    }

    private PriceChoice buscarPrecoExato(
            String rawPriceId,
            String obraId,
            String serviceId,
            String unit,
            LocalDate dataRdo
    ) {
        String priceId = normalizedId(rawPriceId);
        if (priceId == null) {
            badRequest("RDO_REVENUE_PRICE_REQUIRED");
        }
        List<PriceChoice> prices = jdbcTemplate.query(
                """
                SELECT id, obra_id, service_id, unidade, moeda, versao,
                       valor_unitario, vigencia_inicio,
                       cortex_price_effective_valid_to(id) AS effective_valid_to
                FROM service_price_version
                WHERE id = ?
                """,
                (rs, rowNumber) -> new PriceChoice(
                        rs.getString("id"), rs.getString("obra_id"),
                        rs.getString("service_id"), rs.getString("unidade"),
                        rs.getString("moeda"), rs.getInt("versao"),
                        rs.getBigDecimal("valor_unitario"),
                        rs.getDate("vigencia_inicio").toLocalDate(),
                        rs.getDate("effective_valid_to") == null
                                ? null
                                : rs.getDate("effective_valid_to").toLocalDate()
                ),
                priceId
        );
        if (prices.isEmpty()) {
            badRequest("RDO_REVENUE_PRICE_SCOPE_INVALID");
        }
        PriceChoice selected = prices.getFirst();
        if (!selected.worksiteId().equals(obraId)
                || !selected.serviceId().equals(serviceId)
                || !selected.unit().equals(unit)
                || !"BRL".equals(selected.currency())) {
            badRequest("RDO_REVENUE_PRICE_SCOPE_INVALID");
        }
        if (dataRdo.isBefore(selected.validFrom())
                || (selected.effectiveValidTo() != null
                    && dataRdo.isAfter(selected.effectiveValidTo()))) {
            badRequest("RDO_REVENUE_PRICE_STALE");
        }
        Integer exactCandidates = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM service_price_version price
                WHERE price.obra_id = ?
                  AND price.service_id = ?
                  AND price.unidade = ?
                  AND price.moeda = 'BRL'
                  AND price.vigencia_inicio <= ?
                  AND (
                      cortex_price_effective_valid_to(price.id) IS NULL
                      OR cortex_price_effective_valid_to(price.id) >= ?
                  )
                """,
                Integer.class, obraId, serviceId, unit, dataRdo, dataRdo
        );
        if (exactCandidates == null || exactCandidates != 1) {
            conflict("RDO_REVENUE_PRICE_AMBIGUOUS");
        }
        return selected;
    }

    private RdoResponse.ServicoExecutadoItem toResponse(
            ExistingAcceptedExecution execution
    ) {
        return new RdoResponse.ServicoExecutadoItem(
                execution.id(), execution.serviceId(), execution.priceVersionId(),
                execution.serviceName(), execution.itemContractId(), execution.quantity(),
                execution.unit(), execution.initialSegment(), execution.finalSegment(),
                execution.location(), execution.turn(), execution.validationStatus(),
                execution.revenueState(), execution.coverageCode(),
                execution.unitPriceSnapshot(), execution.currency(),
                execution.revenueAmount(), execution.evidenceId(), execution.eventId(),
                execution.acceptedAt(), execution.rework(),
                execution.productionRejected(), execution.observations()
        );
    }

    private String coverageCode(
            String status,
            boolean rework,
            boolean productionRejected
    ) {
        if (revenueCalculator.isAccepted(status, rework, productionRejected)) {
            return "ACCEPTED_EXACT";
        }
        if (rework) {
            return "UNPRICED_REWORK";
        }
        if ("CANCELADA".equals(status)) {
            return "UNPRICED_CANCELLED";
        }
        if ("REJEITADA".equals(status) || productionRejected) {
            return "UNPRICED_REJECTED";
        }
        return "UNPRICED_REGISTERED";
    }

    private String revenueState(String status) {
        return "VALIDADA".equals(status)
                ? "PRODUCAO_VALIDADA"
                : "PRODUCAO_REGISTRADA";
    }

    private String normalizeUnit(String value) {
        return value == null || value.isBlank()
                ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }

    private String normalizedId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return idOuNovo(value, "identifier");
    }

    private void badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private List<RdoResponse.ServicoExecutadoItem> inserirServicos(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<PreparedService> itens
    ) {
        List<RdoResponse.ServicoExecutadoItem> response =
                new ArrayList<>();

        for (PreparedService prepared : itens) {
            if (prepared.acceptedReplay() != null) {
                response.add(toResponse(prepared.acceptedReplay()));
                continue;
            }
            RdoCreateRequest.ServicoExecutadoItem item = prepared.request();
            CatalogService service = prepared.service();
            PriceChoice price = prepared.price();
            String chaveExecucao =
                    sha256(
                            String.join(
                                    "|",
                                    rdoId,
                                    prepared.id(),
                                    service.id(),
                                    nullToEmpty(price == null ? null : price.id()),
                                    prepared.quantity().toPlainString(),
                                    prepared.unit(),
                                    nullToEmpty(item.trechoInicial()),
                                    nullToEmpty(item.trechoFinal())
                            )
                    );

            Instant acceptedAt = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO execucao_servico_rdo (
                        id,
                        rdo_id,
                        obra_id,
                        programacao_id,
                        servico_nome,
                        item_contratual_id,
                        service_id,
                        price_version_id,
                        quantidade_executada,
                        unidade_medida,
                        trecho_inicial,
                        trecho_final,
                        localizacao,
                        data_execucao,
                        turno,
                        status_validacao,
                        estado_receita,
                        retrabalho,
                        producao_rejeitada,
                        fonte,
                        chave_execucao,
                        observacoes,
                        unit_price_snapshot,
                        currency,
                        revenue_amount,
                        revenue_coverage_code,
                        revenue_evidence_id,
                        revenue_event_id,
                        accepted_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, CASE WHEN ?::varchar IS NULL THEN NULL ELSE now() END
                    )
                    RETURNING accepted_at
                    """,
                    (rs, rowNumber) -> rs.getTimestamp("accepted_at") == null
                            ? null
                            : rs.getTimestamp("accepted_at").toInstant(),
                    prepared.id(),
                    rdoId,
                    obraId,
                    programacaoId,
                    service.name(),
                    service.id(),
                    price == null ? null : price.id(),
                    prepared.quantity(),
                    prepared.unit(),
                    nuloSeVazio(item.trechoInicial()),
                    nuloSeVazio(item.trechoFinal()),
                    nuloSeVazio(item.localizacao()),
                    dataRdo,
                    prepared.turn(),
                    prepared.validationStatus(),
                    prepared.revenueState(),
                    prepared.rework(),
                    prepared.productionRejected(),
                    "RDO",
                    chaveExecucao,
                    item.observacoes(),
                    prepared.unitPriceSnapshot(),
                    price == null ? null : price.currency(),
                    prepared.revenueAmount(),
                    prepared.coverageCode(),
                    prepared.evidenceId(),
                    prepared.eventId(),
                    prepared.evidenceId()
            );

            if (prepared.evidenceId() != null) {
                revenuePublisher.publishAccepted(new RevenueEvidence(
                        prepared.evidenceId(), prepared.eventId(), prepared.id(),
                        rdoId, obraId, dataRdo, service.id(), service.code(),
                        service.name(), price.id(), price.version(),
                        prepared.quantity(), prepared.unit(), price.currency(),
                        prepared.unitPriceSnapshot(), prepared.revenueAmount(),
                        acceptedAt
                ));
            }

            response.add(new RdoResponse.ServicoExecutadoItem(
                    prepared.id(),
                    service.id(),
                    price == null ? null : price.id(),
                    service.name(),
                    null,
                    prepared.quantity(),
                    prepared.unit(),
                    nuloSeVazio(item.trechoInicial()),
                    nuloSeVazio(item.trechoFinal()),
                    nuloSeVazio(item.localizacao()),
                    prepared.turn(),
                    prepared.validationStatus(),
                    prepared.revenueState(),
                    prepared.coverageCode(),
                    prepared.unitPriceSnapshot(),
                    price == null ? null : price.currency(),
                    prepared.revenueAmount(),
                    prepared.evidenceId(),
                    prepared.eventId(),
                    acceptedAt,
                    prepared.rework(),
                    prepared.productionRejected(),
                    item.observacoes()
            ));
        }

        return response;
    }

    private List<RdoResponse.AlocacaoColaboradorItem> inserirAlocacoes(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<RdoCreateRequest.AlocacaoColaboradorItem> itens
    ) {
        List<RdoResponse.AlocacaoColaboradorItem> response =
                new ArrayList<>();
        Map<String, AcumuladorAlocacao> acumuladores =
                new LinkedHashMap<>();

        int index = 0;
        for (RdoCreateRequest.AlocacaoColaboradorItem item : listaSegura(itens)) {
            index++;

            ColaboradorDados colaborador =
                    buscarColaboradorAtivo(item.colaboradorId());
            IntervaloAlocacao intervalo =
                    intervalo(item.horaInicio(), item.horaFim(), item.percentualDia());
            BigDecimal percentual =
                    percentualDia(item.percentualDia(), intervalo.minutos());
            String tipoAlocacao =
                    normalizarTipoAlocacao(item.tipoAlocacao());
            String status =
                    normalizarStatusAlocacao(item.status());

            validarSemSobreposicao(
                    rdoId,
                    colaborador.id(),
                    dataRdo,
                    intervalo
            );

            AcumuladorAlocacao acumulador =
                    acumuladores.computeIfAbsent(
                            colaborador.id(),
                            ignored -> acumuladorExistente(
                                    rdoId,
                                    colaborador.id(),
                                    dataRdo
                            )
                    );
            acumulador.adicionar(intervalo.minutos(), percentual);

            String id = idOuNovo(item.id(), "alocacoesColaboradores.id");
            String chaveAlocacao =
                    sha256(
                            String.join(
                                    "|",
                                    rdoId,
                                    id,
                                    colaborador.id(),
                                    dataRdo.toString(),
                                    nullToEmpty(intervalo.horaInicio()),
                                    nullToEmpty(intervalo.horaFim()),
                                    tipoAlocacao,
                                    nullToEmpty(item.servicoNome())
                            )
                    );

            jdbcTemplate.update(
                    """
                    INSERT INTO alocacao_colaborador (
                        id,
                        colaborador_id,
                        data_alocacao,
                        hora_inicio,
                        hora_fim,
                        minutos,
                        percentual_dia,
                        obra_id,
                        equipe,
                        servico_nome,
                        rdo_id,
                        programacao_id,
                        turno,
                        funcao,
                        centro_custo,
                        tipo_alocacao,
                        fonte,
                        status,
                        observacoes,
                        chave_alocacao
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    colaborador.id(),
                    dataRdo,
                    intervalo.horaInicio(),
                    intervalo.horaFim(),
                    intervalo.minutos(),
                    percentual,
                    obraId,
                    nuloSeVazio(item.equipe()),
                    nuloSeVazio(item.servicoNome()),
                    rdoId,
                    programacaoId,
                    primeiroNaoVazio(item.turno(), turnoRdo),
                    nuloSeVazio(item.funcao()),
                    nuloSeVazio(item.centroCusto()),
                    tipoAlocacao,
                    primeiroNaoVazio(item.fonte(), "RDO"),
                    status,
                    item.observacoes(),
                    chaveAlocacao
            );

            registrarAlocacaoNaMemoria(
                    id,
                    colaborador,
                    obraId,
                    rdoId,
                    programacaoId,
                    item.equipe(),
                    item.servicoNome(),
                    item.funcao(),
                    tipoAlocacao,
                    status,
                    intervalo.minutos()
            );

            response.add(new RdoResponse.AlocacaoColaboradorItem(
                    id,
                    colaborador.id(),
                    nuloSeVazio(item.equipe()),
                    nuloSeVazio(item.servicoNome()),
                    intervalo.horaInicio(),
                    intervalo.horaFim(),
                    intervalo.minutos(),
                    percentual,
                    primeiroNaoVazio(item.turno(), turnoRdo),
                    nuloSeVazio(item.funcao()),
                    nuloSeVazio(item.centroCusto()),
                    tipoAlocacao,
                    primeiroNaoVazio(item.fonte(), "RDO"),
                    status,
                    item.observacoes()
            ));
        }

        return response;
    }

    private ColaboradorDados buscarColaboradorAtivo(String colaboradorId) {
        if (colaboradorId == null || colaboradorId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "colaboradorId é obrigatório em alocações."
            );
        }

        List<ColaboradorDados> colaboradores = jdbcTemplate.query(
                """
                SELECT id, nome, ativo
                FROM colaborador
                WHERE id = ?
                  AND deletado_em IS NULL
                """,
                (rs, rowNumber) -> new ColaboradorDados(
                        rs.getString("id"),
                        rs.getString("nome"),
                        rs.getBoolean("ativo")
                ),
                colaboradorId.trim()
        );

        if (colaboradores.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador não encontrado."
            );
        }

        ColaboradorDados colaborador = colaboradores.getFirst();

        if (!colaborador.ativo()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador inativo não pode ser alocado."
            );
        }

        return colaborador;
    }

    private void validarSemSobreposicao(
            String rdoId,
            String colaboradorId,
            LocalDate dataRdo,
            IntervaloAlocacao intervalo
    ) {
        if (intervalo.horaInicio() == null || intervalo.horaFim() == null) {
            return;
        }

        Integer overlaps = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM alocacao_colaborador
                WHERE colaborador_id = ?
                  AND data_alocacao = ?
                  AND status <> 'CANCELADA'
                  AND (rdo_id IS NULL OR rdo_id <> ?)
                  AND hora_inicio IS NOT NULL
                  AND hora_fim IS NOT NULL
                  AND hora_inicio < ?
                  AND hora_fim > ?
                """,
                Integer.class,
                colaboradorId,
                dataRdo,
                rdoId,
                intervalo.horaFim(),
                intervalo.horaInicio()
        );

        if (overlaps != null && overlaps > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Alocação sobreposta para o colaborador na mesma data."
            );
        }
    }

    private AcumuladorAlocacao acumuladorExistente(
            String rdoId,
            String colaboradorId,
            LocalDate dataRdo
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COALESCE(SUM(minutos), 0) AS minutos,
                    COALESCE(SUM(percentual_dia), 0) AS percentual
                FROM alocacao_colaborador
                WHERE colaborador_id = ?
                  AND data_alocacao = ?
                  AND status <> 'CANCELADA'
                  AND (rdo_id IS NULL OR rdo_id <> ?)
                """,
                (rs, rowNumber) -> new AcumuladorAlocacao(
                        rs.getInt("minutos"),
                        rs.getBigDecimal("percentual")
                ),
                colaboradorId,
                dataRdo,
                rdoId
        );
    }

    private IntervaloAlocacao intervalo(
            LocalTime horaInicio,
            LocalTime horaFim,
            BigDecimal percentualDia
    ) {
        if (horaInicio != null || horaFim != null) {
            if (horaInicio == null || horaFim == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "horaInicio e horaFim devem ser informados juntos."
                );
            }

            long minutos = ChronoUnit.MINUTES.between(horaInicio, horaFim);

            if (minutos <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "horaFim deve ser maior que horaInicio."
                );
            }

            if (minutos > 1440) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Uma alocação não pode exceder 24 horas."
                );
            }

            return new IntervaloAlocacao(horaInicio, horaFim, (int) minutos);
        }

        if (percentualDia == null
                || percentualDia.compareTo(BigDecimal.ZERO) <= 0
                || percentualDia.compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe horas ou percentualDia entre 0 e 1 para a alocação."
            );
        }

        int minutos = percentualDia
                .multiply(MINUTOS_DIA_PADRAO)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        return new IntervaloAlocacao(null, null, minutos);
    }

    private BigDecimal percentualDia(BigDecimal percentual, int minutos) {
        if (percentual != null) {
            return percentual.setScale(6, RoundingMode.HALF_UP);
        }

        return BigDecimal.valueOf(minutos)
                .divide(MINUTOS_DIA_PADRAO, 6, RoundingMode.HALF_UP);
    }

    private String normalizarStatusValidacao(String value) {
        String status = primeiroNaoVazio(value, "REGISTRADA")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of("REGISTRADA", "VALIDADA", "REJEITADA", "CANCELADA")
                .contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "statusValidacao inválido para serviço executado."
            );
        }

        return status;
    }

    private String normalizarTipoAlocacao(String value) {
        String tipo = primeiroNaoVazio(value, "TRABALHO")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of(
                "TRABALHO",
                "DESLOCAMENTO",
                "TREINAMENTO",
                "MANUTENCAO",
                "APOIO",
                "ADMINISTRATIVO",
                "AFASTAMENTO",
                "OUTRO"
        ).contains(tipo)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "tipoAlocacao inválido."
            );
        }

        return tipo;
    }

    private String normalizarStatusAlocacao(String value) {
        String status = primeiroNaoVazio(value, "REGISTRADA")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of("REGISTRADA", "VALIDADA", "CONFLITO", "CANCELADA")
                .contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status inválido para alocação."
            );
        }

        return status;
    }

    private void registrarAlocacaoNaMemoria(
            String alocacaoId,
            ColaboradorDados colaborador,
            String obraId,
            String rdoId,
            String programacaoId,
            String equipe,
            String servicoNome,
            String funcao,
            String tipoAlocacao,
            String status,
            int minutos
    ) {
        memoryService.registrarObjeto(
                "COLABORADOR",
                colaborador.id(),
                colaborador.id(),
                colaborador.nome(),
                colaborador.ativo() ? "ATIVO" : "INATIVO",
                FONTE
        );
        memoryService.registrarObjeto(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                alocacaoId,
                "Alocação de " + colaborador.nome(),
                status,
                FONTE,
                "alocacao_colaborador",
                Map.of("rdoId", rdoId, "obraId", obraId)
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR",
                colaborador.id(),
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "POSSUI_ALOCACAO",
                FONTE,
                "Colaborador possui alocação temporal."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "OBRA",
                obraId,
                "OCORRE_EM",
                FONTE,
                "Alocação ocorre em uma obra."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "RDO",
                rdoId,
                "REGISTRADA_NO",
                FONTE,
                "Alocação registrada no RDO."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "PROGRAMACAO_OPERACIONAL",
                programacaoId,
                "GERADA_A_PARTIR_DE",
                FONTE,
                "Alocação relacionada à programação operacional."
        );

        if (servicoNome != null && !servicoNome.isBlank()) {
            ServicoCatalogado servico = servicoCatalogado(servicoNome);
            registrarServicoCatalogado(servico);
            memoryService.registrarRelacaoAtiva(
                    "ALOCACAO_COLABORADOR",
                    alocacaoId,
                    "SERVICO",
                    servico.id(),
                    "EXECUTA",
                    FONTE,
                    "Alocação executa serviço."
            );
            memoryService.registrarRelacaoAtiva(
                    "COLABORADOR",
                    colaborador.id(),
                    "SERVICO",
                    servico.id(),
                    "ATUOU_EM_SERVICO",
                    FONTE,
                    "Colaborador atuou historicamente neste tipo de serviço."
            );

            if (ehResponsavelOperacional(funcao)) {
                memoryService.registrarRelacaoAtiva(
                        "COLABORADOR",
                        colaborador.id(),
                        "SERVICO",
                        servico.id(),
                        "RESPONSAVEL_POR",
                        FONTE,
                        "Colaborador identificado como encarregado ou responsável pelo serviço."
                );
                memoryService.registrarRelacaoAtiva(
                        "ALOCACAO_COLABORADOR",
                        alocacaoId,
                        "SERVICO",
                        servico.id(),
                        "RESPONSAVEL_PELO_SERVICO",
                        FONTE,
                        "Alocação marca encarregado ou responsável pelo serviço."
                );
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("colaboradorId", colaborador.id());
        payload.put("obraId", obraId);
        payload.put("rdoId", rdoId);
        payload.put("programacaoId", programacaoId);
        payload.put("equipe", equipe);
        payload.put("servico", servicoNome);
        payload.put("funcao", funcao);
        payload.put("tipoAlocacao", tipoAlocacao);
        payload.put("status", status);
        payload.put("minutos", minutos);

        memoryService.registrarEvento(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "ALOCACAO_COLABORADOR_CRIADA",
                FONTE,
                obraId,
                payload
        );
    }

    private void registrarServicoCatalogado(
            ServicoCatalogado servico
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("codigoServico", servico.codigo());
        metadata.put("nomeServico", servico.nome());
        metadata.put("nomeCanonico", servico.canonicalName());
        metadata.put("valorOriginal", servico.rawName());

        memoryService.registrarObjeto(
                "SERVICO",
                servico.id(),
                servico.codigo(),
                servico.displayName(),
                "ATIVO",
                FONTE,
                "catalogo_tipo_servico",
                metadata
        );

        Map<String, Object> evidencias = new LinkedHashMap<>();
        evidencias.put("codigo_servico", servico.codigo());
        evidencias.put("nome_servico", servico.nome());
        evidencias.put("nome_canonico", servico.canonicalName());
        evidencias.put("display_name", servico.displayName());
        memoryService.registrarEvidencias(
                "SERVICO",
                servico.id(),
                FONTE,
                evidencias
        );
    }

    private ServicoCatalogado servicoCatalogado(
            String servicoNome
    ) {
        String rawName = primeiroNaoVazio(servicoNome, "Serviço sem nome");
        Matcher matcher = CODIGO_SERVICO_PATTERN.matcher(rawName);

        String codigo = null;
        String nome = rawName;
        if (matcher.matches()) {
            codigo = matcher.group(1).trim();
            nome = matcher.group(2).trim();
        }

        String canonicalName = canonicalText(nome);
        String stableKey = codigo == null
                ? "SERVICO|" + canonicalName
                : "SERVICO|" + codigo + "|" + canonicalName;
        String id = stableUuid(stableKey);
        String displayName = codigo == null
                ? nome
                : codigo + " - " + nome;

        return new ServicoCatalogado(
                id,
                codigo,
                nome,
                displayName,
                canonicalName,
                rawName
        );
    }

    private boolean ehResponsavelOperacional(String funcao) {
        String canonical = canonicalText(funcao);
        return canonical.contains("encarregado")
                || canonical.contains("responsavel")
                || canonical.contains("supervisor")
                || canonical.contains("lider");
    }

    private String canonicalText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private BigDecimal escala3(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível gerar hash operacional.",
                    exception
            );
        }
    }

    private String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private String idOuNovo(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(value.strip()).toString();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " deve ser UUID."
            );
        }
    }

    private <T> List<T> listaSegura(List<T> lista) {
        return lista == null ? List.of() : lista;
    }

    private String primeiroNaoVazio(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor.trim();
            }
        }
        return null;
    }

    private String nuloSeVazio(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String nullToEmpty(LocalTime value) {
        return value == null ? "" : value.toString();
    }

    private LocalTime toLocalTime(java.sql.Time time) {
        return time == null ? null : time.toLocalTime();
    }

    public record RdoOperationalDetails(
            List<RdoResponse.ServicoExecutadoItem> servicosExecutados,
            List<RdoResponse.AlocacaoColaboradorItem> alocacoesColaboradores
    ) {
    }

    private record CatalogService(
            String id,
            String code,
            String name
    ) {
    }

    private record PriceChoice(
            String id,
            String worksiteId,
            String serviceId,
            String unit,
            String currency,
            int version,
            BigDecimal unitPrice,
            LocalDate validFrom,
            LocalDate effectiveValidTo
    ) {
    }

    private record ExistingAcceptedExecution(
            String id,
            String serviceId,
            String priceVersionId,
            String serviceName,
            String itemContractId,
            BigDecimal quantity,
            String unit,
            String initialSegment,
            String finalSegment,
            String location,
            String turn,
            String validationStatus,
            String revenueState,
            boolean rework,
            boolean productionRejected,
            String observations,
            BigDecimal unitPriceSnapshot,
            String currency,
            BigDecimal revenueAmount,
            String coverageCode,
            String evidenceId,
            String eventId,
            Instant acceptedAt,
            String serviceCode,
            int priceVersion
    ) {
    }

    private record PreparedService(
            RdoCreateRequest.ServicoExecutadoItem request,
            String id,
            CatalogService service,
            PriceChoice price,
            BigDecimal quantity,
            String unit,
            String turn,
            String validationStatus,
            String revenueState,
            String coverageCode,
            BigDecimal unitPriceSnapshot,
            BigDecimal revenueAmount,
            String evidenceId,
            String eventId,
            boolean rework,
            boolean productionRejected,
            ExistingAcceptedExecution acceptedReplay
    ) {
        private static PreparedService replay(
                RdoCreateRequest.ServicoExecutadoItem request,
                ExistingAcceptedExecution replay
        ) {
            return new PreparedService(
                    request, replay.id(), null, null, replay.quantity(),
                    replay.unit(), replay.turn(), replay.validationStatus(),
                    replay.revenueState(), replay.coverageCode(),
                    replay.unitPriceSnapshot(), replay.revenueAmount(),
                    replay.evidenceId(), replay.eventId(), replay.rework(),
                    replay.productionRejected(), replay
            );
        }
    }

    private record ColaboradorDados(
            String id,
            String nome,
            boolean ativo
    ) {
    }

    private record ServicoCatalogado(
            String id,
            String codigo,
            String nome,
            String displayName,
            String canonicalName,
            String rawName
    ) {
    }

    private record IntervaloAlocacao(
            LocalTime horaInicio,
            LocalTime horaFim,
            int minutos
    ) {
    }

    private record RemovedChild(
            String id,
            String name
    ) {
    }

    private static final class AcumuladorAlocacao {
        private int minutos;
        private BigDecimal percentual;

        private AcumuladorAlocacao(int minutos, BigDecimal percentual) {
            this.minutos = minutos;
            this.percentual = percentual == null ? BigDecimal.ZERO : percentual;
        }

        private void adicionar(int novosMinutos, BigDecimal novoPercentual) {
            this.minutos += novosMinutos;
            this.percentual = this.percentual.add(novoPercentual);

            if (this.minutos > 1440) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Total de horas do colaborador excede 24 horas no dia."
                );
            }

            if (this.percentual.compareTo(BigDecimal.ONE) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Percentual total do colaborador excede 100% no dia."
                );
            }
        }
    }
}
