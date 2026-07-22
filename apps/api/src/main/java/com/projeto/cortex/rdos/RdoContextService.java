package com.projeto.cortex.rdos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RdoContextService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RdoCreationPayloadHasher payloadHasher;

    public RdoContextService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public RdoContextService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.payloadHasher = new RdoCreationPayloadHasher(objectMapper);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public RdoContextResponse buscarContexto(String obraId, LocalDate data) {
        if (obraId == null || obraId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "obraId é obrigatório.");
        }
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data é obrigatória.");
        }
        RdoContextResponse.ObraContexto obra = buscarObra(obraId);

        RdoContextResponse.PreviousRdo previousRdo = buscarRdoAnterior(obraId, data);

        List<RdoContextResponse.PreviousWorkforceItem> previousWorkforce =
                previousRdo == null
                        ? List.of()
                        : buscarEquipeAnterior(previousRdo.id(), obraId);

        List<RdoContextResponse.ProgramacaoContexto> programacoes =
                buscarProgramacoesDaObraNaData(obraId, data);

        List<RdoContextResponse.ColaboradorContexto> colaboradores =
                listarColaboradoresAtivosDaObra(obraId);

        List<RdoContextResponse.EquipamentoContexto> equipamentos =
                listarEquipamentosAtivosDaObra(obraId);

        long catalogRevision = buscarCatalogRevision();
        List<RdoContextResponse.ServiceCatalogContext> serviceCatalog =
                listarCatalogoServicos(obraId, data, catalogRevision);
        long priceChoiceCount = serviceCatalog.stream()
                .mapToLong(service -> service.priceChoices().size())
                .sum();

        long sourceVersion = calcularSourceVersion(obraId);
        String nextNumberSuggestion = sugerirProximoNumero(obraId);
        Instant generatedAt = Instant.now();
        Instant staleAfter = generatedAt.plus(15, ChronoUnit.MINUTES);
        RdoContextResponse.ContextCoverage coverage = new RdoContextResponse.ContextCoverage(
                complete(previousWorkforce.size()),
                complete(programacoes.size()),
                complete(colaboradores.size()),
                complete(equipamentos.size()),
                complete(serviceCatalog.size()),
                complete(priceChoiceCount)
        );
        Map<String, Object> snapshotPayload = new LinkedHashMap<>();
        snapshotPayload.put("obra", obra);
        snapshotPayload.put("data", data);
        snapshotPayload.put("nextNumberSuggestion", nextNumberSuggestion);
        snapshotPayload.put("previousRdo", previousRdo);
        snapshotPayload.put("previousWorkforce", previousWorkforce);
        snapshotPayload.put("programacoes", programacoes);
        snapshotPayload.put("colaboradores", colaboradores);
        snapshotPayload.put("equipamentos", equipamentos);
        snapshotPayload.put("serviceCatalog", serviceCatalog);
        snapshotPayload.put("coverage", coverage);
        snapshotPayload.put("sourceVersion", sourceVersion);
        snapshotPayload.put("catalogRevision", catalogRevision);
        long receiptVersion = persistirSnapshot(
                obraId,
                data,
                previousRdo == null ? null : previousRdo.id(),
                sourceVersion,
                payloadHasher.hashValue(snapshotPayload),
                coverage,
                generatedAt,
                staleAfter
        );

        return new RdoContextResponse(
                obra,
                data,
                nextNumberSuggestion,
                previousRdo,
                previousWorkforce,
                programacoes,
                colaboradores,
                equipamentos,
                serviceCatalog,
                coverage,
                new RdoContextResponse.ContextFreshness(
                        "FRESH", sourceVersion, catalogRevision,
                        generatedAt, staleAfter
                ),
                new RdoContextResponse.CreationProvenance(
                        receiptVersion,
                        sourceVersion,
                        obraId,
                        data,
                        previousRdo == null ? null : previousRdo.id(),
                        generatedAt
                )
        );
    }

    private RdoContextResponse.CoverageSection complete(long count) {
        return new RdoContextResponse.CoverageSection("COMPLETE", count, count, true);
    }

    private long persistirSnapshot(
            String obraId,
            LocalDate data,
            String previousRdoId,
            long sourceVersion,
            String payloadHash,
            RdoContextResponse.ContextCoverage coverage,
            Instant generatedAt,
            Instant staleAfter
    ) {
        try {
            Long receiptVersion = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO rdo_creation_context_snapshot (
                        snapshot_id, obra_id, selected_date,
                        previous_rdo_id, source_version, payload_hash,
                        coverage_json, generated_at, stale_after
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    RETURNING receipt_version
                    """,
                    Long.class,
                    java.util.UUID.randomUUID().toString(),
                    obraId,
                    data,
                    previousRdoId,
                    sourceVersion,
                    payloadHash,
                    objectMapper.writeValueAsString(coverage),
                    java.sql.Timestamp.from(generatedAt),
                    java.sql.Timestamp.from(staleAfter)
            );
            if (receiptVersion == null || receiptVersion <= 0) {
                throw new IllegalStateException("Receipt de contexto inválido retornado pelo PostgreSQL.");
            }
            return receiptVersion;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Não foi possível serializar a cobertura do contexto.", exception);
        }
    }

    private RdoContextResponse.ObraContexto buscarObra(String obraId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        codigo_contrato,
                        codigo_cw,
                        nome,
                        cliente,
                        cidade,
                        uf,
                        rodovia,
                        status,
                        versao_linha
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    (rs, rowNum) -> new RdoContextResponse.ObraContexto(
                            rs.getString("id"),
                            rs.getString("codigo_contrato"),
                            rs.getString("codigo_cw"),
                            rs.getString("nome"),
                            rs.getString("cliente"),
                            rs.getString("cidade"),
                            rs.getString("uf"),
                            rs.getString("rodovia"),
                            rs.getString("status"),
                            rs.getLong("versao_linha")
                    ),
                    obraId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada: " + obraId
            );
        }
    }

    /**
     * The source is strictly before the selected date. Same-day RDOs are not a
     * workforce source because the new report may itself be an offline replay
     * for that date. Ties are resolved by persisted recency and finally by ID.
     */
    private RdoContextResponse.PreviousRdo buscarRdoAnterior(
            String obraId,
            LocalDate selectedDate
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, numero_rdo, data_rdo, status, versao_linha
                FROM rdo
                WHERE obra_id = ?
                  AND data_rdo < ?
                  AND status <> 'CANCELADO'
                  AND cancelado_em IS NULL
                ORDER BY data_rdo DESC,
                         atualizado_em DESC,
                         criado_em DESC,
                         id DESC
                LIMIT 1
                """,
                rs -> rs.next()
                        ? new RdoContextResponse.PreviousRdo(
                                rs.getString("id"),
                                rs.getString("numero_rdo"),
                                rs.getDate("data_rdo").toLocalDate(),
                                rs.getString("status"),
                                rs.getLong("versao_linha")
                        )
                        : null,
                obraId,
                selectedDate
        );
    }

    private List<RdoContextResponse.PreviousWorkforceItem> buscarEquipeAnterior(
            String previousRdoId,
            String obraId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    item.id,
                    item.rdo_id,
                    item.colaborador_id,
                    item.nome_colaborador,
                    item.cargo,
                    item.tipo_vinculo,
                    item.quantidade,
                    item.hora_inicio,
                    item.hora_fim,
                    item.observacoes,
                    CASE WHEN collaborator.id IS NOT NULL
                              AND link.status = 'ATIVO'
                         THEN 'AVAILABLE'
                         ELSE 'UNAVAILABLE'
                    END AS availability
                FROM rdo_mao_obra item
                JOIN rdo source_rdo
                  ON source_rdo.id = item.rdo_id
                 AND source_rdo.obra_id = ?
                LEFT JOIN colaborador collaborator
                  ON collaborator.id = item.colaborador_id
                 AND collaborator.ativo = TRUE
                 AND collaborator.deletado_em IS NULL
                LEFT JOIN vinculo_colaborador_obra link
                  ON link.colaborador_id = collaborator.id
                 AND link.obra_id = source_rdo.obra_id
                 AND link.status = 'ATIVO'
                WHERE item.rdo_id = ?
                ORDER BY item.cargo, item.nome_colaborador, item.id
                """,
                (rs, rowNum) -> new RdoContextResponse.PreviousWorkforceItem(
                        rs.getString("id"),
                        rs.getString("rdo_id"),
                        rs.getString("colaborador_id"),
                        rs.getString("nome_colaborador"),
                        rs.getString("cargo"),
                        rs.getString("tipo_vinculo"),
                        rs.getBigDecimal("quantidade"),
                        toLocalTime(rs.getTime("hora_inicio")),
                        toLocalTime(rs.getTime("hora_fim")),
                        rs.getString("observacoes"),
                        rs.getString("availability")
                ),
                obraId,
                previousRdoId
        );
    }

    private List<RdoContextResponse.ProgramacaoContexto> buscarProgramacoesDaObraNaData(
            String obraId,
            LocalDate data
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    data_programacao,
                    equipe,
                    encarregado,
                    engenheiro,
                    cliente,
                    servico,
                    tipo_servico,
                    cidade,
                    uf,
                    rodovia,
                    sentido,
                    faixa,
                    km_inicial,
                    km_final,
                    extensao_m,
                    largura_m,
                    espessura_cm,
                    area_m2,
                    volume_m3,
                    status
                FROM programacao_operacional
                WHERE obra_id = ?
                  AND data_programacao = ?
                  AND cancelado_em IS NULL
                ORDER BY equipe, servico, km_inicial, id
                """,
                (rs, rowNum) -> new RdoContextResponse.ProgramacaoContexto(
                        rs.getString("id"),
                        rs.getDate("data_programacao").toLocalDate(),
                        rs.getString("equipe"),
                        rs.getString("encarregado"),
                        rs.getString("engenheiro"),
                        rs.getString("cliente"),
                        rs.getString("servico"),
                        rs.getString("tipo_servico"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getString("rodovia"),
                        rs.getString("sentido"),
                        rs.getString("faixa"),
                        rs.getString("km_inicial"),
                        rs.getString("km_final"),
                        rs.getBigDecimal("extensao_m"),
                        rs.getBigDecimal("largura_m"),
                        rs.getBigDecimal("espessura_cm"),
                        rs.getBigDecimal("area_m2"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getString("status")
                ),
                obraId,
                data
        );
    }

    private List<RdoContextResponse.ColaboradorContexto> listarColaboradoresAtivosDaObra(
            String obraId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    collaborator.id,
                    collaborator.codigo_colaborador,
                    collaborator.nome,
                    link.papel_na_obra,
                    collaborator.nome_perfil
                FROM vinculo_colaborador_obra link
                JOIN colaborador collaborator
                  ON collaborator.id = link.colaborador_id
                 AND collaborator.ativo = TRUE
                 AND collaborator.deletado_em IS NULL
                WHERE link.obra_id = ?
                  AND link.status = 'ATIVO'
                ORDER BY collaborator.nome, collaborator.id
                """,
                (rs, rowNum) -> new RdoContextResponse.ColaboradorContexto(
                        rs.getString("id"),
                        rs.getString("codigo_colaborador"),
                        rs.getString("nome"),
                        rs.getString("papel_na_obra"),
                        rs.getString("nome_perfil")
                ),
                obraId
        );
    }

    private String sugerirProximoNumero(String obraId) {
        Long next = jdbcTemplate.queryForObject(
                """
                SELECT GREATEST(
                    COALESCE((
                        SELECT next_value
                        FROM rdo_number_sequence
                        WHERE obra_id = ?
                    ), 1),
                    COALESCE((
                        SELECT MAX(substring(numero_rdo FROM '^RDO-([0-9]{1,18})$')::bigint) + 1
                        FROM rdo
                        WHERE obra_id = ?
                          AND numero_rdo ~ '^RDO-[0-9]{1,18}$'
                    ), 1)
                )
                """,
                Long.class,
                obraId,
                obraId
        );
        return formatNumber(next == null ? 1L : next);
    }

    private long calcularSourceVersion(String obraId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT GREATEST(
                    1,
                    COALESCE((
                        SELECT floor(extract(epoch FROM MAX(changed_at)) * 1000000)::bigint
                        FROM (
                            SELECT atualizado_em AS changed_at FROM obra WHERE id = ?
                            UNION ALL
                            SELECT atualizado_em FROM rdo WHERE obra_id = ?
                            UNION ALL
                            SELECT item.atualizado_em
                            FROM rdo_mao_obra item
                            JOIN rdo ON rdo.id = item.rdo_id
                            WHERE rdo.obra_id = ?
                            UNION ALL
                            SELECT atualizado_em
                            FROM programacao_operacional
                            WHERE obra_id = ?
                            UNION ALL
                            SELECT atualizado_em
                            FROM vinculo_colaborador_obra
                            WHERE obra_id = ?
                            UNION ALL
                            SELECT collaborator.atualizado_em
                            FROM colaborador collaborator
                            JOIN vinculo_colaborador_obra link
                              ON link.colaborador_id = collaborator.id
                            WHERE link.obra_id = ?
                            UNION ALL
                            SELECT item.atualizado_em
                            FROM rdo_equipamento item
                            JOIN rdo ON rdo.id = item.rdo_id
                            WHERE rdo.obra_id = ?
                            UNION ALL
                            SELECT atualizado_em
                            FROM asset_obra_eligibilidade
                            WHERE obra_id = ?
                            UNION ALL
                            SELECT asset.updated_at
                            FROM asset
                            JOIN asset_obra_eligibilidade eligibility
                              ON eligibility.asset_id = asset.id
                            WHERE eligibility.obra_id = ?
                        ) revisions
                    ), 1)
                )
                """,
                Long.class,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId
        );
        return version == null ? 1L : version;
    }

    static String formatNumber(long value) {
        return "RDO-%04d".formatted(value);
    }

    private long buscarCatalogRevision() {
        Long revision = jdbcTemplate.queryForObject(
                "SELECT revision FROM service_catalog_revision WHERE singleton = TRUE",
                Long.class
        );
        return revision == null ? 0L : revision;
    }

    private List<RdoContextResponse.ServiceCatalogContext> listarCatalogoServicos(
            String obraId,
            LocalDate selectedDate,
            long catalogRevision
    ) {
        List<ServiceContextRow> services = jdbcTemplate.query(
                """
                SELECT id, codigo, nome, descricao
                FROM catalogo_servico
                WHERE status = 'ACTIVE'
                  AND commit_revision <= ?
                ORDER BY codigo, nome, id
                """,
                (rs, rowNumber) -> new ServiceContextRow(
                        rs.getString("id"), rs.getString("codigo"),
                        rs.getString("nome"), rs.getString("descricao")
                ),
                catalogRevision
        );
        Map<String, List<RdoContextResponse.ServicePriceChoice>> pricesByService =
                new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT price.id, price.service_id, price.unidade,
                       price.versao, price.vigencia_inicio,
                       cortex_price_effective_valid_to(price.id) AS effective_valid_to
                FROM service_price_version price
                JOIN catalogo_servico service ON service.id = price.service_id
                WHERE price.obra_id = ?
                  AND price.moeda = 'BRL'
                  AND price.commit_revision <= ?
                  AND service.commit_revision <= ?
                  AND price.vigencia_inicio <= ?
                  AND (
                      cortex_price_effective_valid_to(price.id) IS NULL
                      OR cortex_price_effective_valid_to(price.id) >= ?
                  )
                ORDER BY service.codigo, price.unidade, price.versao, price.id
                """,
                rs -> {
                    pricesByService.computeIfAbsent(
                            rs.getString("service_id"), ignored -> new ArrayList<>()
                    ).add(new RdoContextResponse.ServicePriceChoice(
                            rs.getString("id"), rs.getString("service_id"),
                            rs.getString("unidade"), rs.getInt("versao"),
                            rs.getDate("vigencia_inicio").toLocalDate(),
                            rs.getDate("effective_valid_to") == null
                                    ? null
                                    : rs.getDate("effective_valid_to").toLocalDate()
                    ));
                },
                obraId, catalogRevision, catalogRevision,
                selectedDate, selectedDate
        );
        return services.stream()
                .map(service -> new RdoContextResponse.ServiceCatalogContext(
                        service.id(), service.code(), service.name(),
                        service.description(),
                        List.copyOf(pricesByService.getOrDefault(
                                service.id(), List.of()
                        ))
                ))
                .toList();
    }

    private LocalTime toLocalTime(java.sql.Time value) {
        return value == null ? null : value.toLocalTime();
    }

    private List<RdoContextResponse.EquipamentoContexto> listarEquipamentosAtivosDaObra(
            String obraId
    ) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT
                    asset.id,
                    asset.external_code,
                    asset.name,
                    asset.category
                FROM asset_obra_eligibilidade eligibility
                JOIN asset ON asset.id = eligibility.asset_id
                WHERE eligibility.obra_id = ?
                  AND eligibility.status = 'ATIVO'
                  AND asset.active = TRUE
                  AND asset.deleted_at IS NULL
                ORDER BY asset.external_code, asset.name, asset.id
                """,
                (rs, rowNum) -> new RdoContextResponse.EquipamentoContexto(
                        rs.getString("id"),
                        rs.getString("external_code"),
                        rs.getString("name"),
                        rs.getString("category")
                ),
                obraId
        );
    }

    private record ServiceContextRow(
            String id,
            String code,
            String name,
            String description
    ) {
    }
}
