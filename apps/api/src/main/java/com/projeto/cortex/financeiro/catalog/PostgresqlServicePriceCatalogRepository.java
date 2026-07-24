package com.projeto.cortex.financeiro.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class PostgresqlServicePriceCatalogRepository
        implements ServicePriceCatalogRepository {

    private static final String PRICE_PROJECTION = """
            SELECT price.id,
                   price.obra_id,
                   price.service_id,
                   price.unidade,
                   price.moeda,
                   price.versao,
                   price.valor_unitario,
                   price.fonte,
                   price.vigencia_inicio,
                   price.vigencia_fim,
                   price.supersedes_id,
                   CASE
                       WHEN cancellation.id IS NOT NULL THEN 'CANCELLED'
                       WHEN successor.id IS NOT NULL THEN 'SUPERSEDED'
                       ELSE 'ACTIVE'
                   END AS effective_status,
                   cortex_price_effective_valid_to(price.id) AS effective_valid_to,
                   price.criado_em,
                   COALESCE((
                       SELECT state.versao_entidade
                       FROM cortex_estado_entidade state
                       WHERE state.tipo_entidade = 'SERVICE_PRICE_VERSION'
                         AND state.entidade_id = price.id
                   ), 0) AS entity_version
            FROM service_price_version price
            LEFT JOIN service_price_version successor
              ON successor.supersedes_id = price.id
            LEFT JOIN service_price_version_cancellation cancellation
              ON cancellation.price_version_id = price.id
            """;
    private static final String SNAPSHOT_PRICE_PROJECTION = """
            SELECT price.id,
                   price.obra_id,
                   price.service_id,
                   price.unidade,
                   price.moeda,
                   price.versao,
                   price.valor_unitario,
                   price.fonte,
                   price.vigencia_inicio,
                   price.vigencia_fim,
                   price.supersedes_id,
                   CASE
                       WHEN cancellation.id IS NOT NULL THEN 'CANCELLED'
                       WHEN successor.id IS NOT NULL THEN 'SUPERSEDED'
                       ELSE 'ACTIVE'
                   END AS effective_status,
                   CASE
                       WHEN price.vigencia_fim IS NULL
                            AND successor.vigencia_inicio IS NULL
                            AND cancellation.vigencia_cancelamento IS NULL
                           THEN NULL
                       ELSE LEAST(
                           COALESCE(price.vigencia_fim, 'infinity'::date),
                           COALESCE(successor.vigencia_inicio - 1, 'infinity'::date),
                           COALESCE(
                               cancellation.vigencia_cancelamento - 1,
                               'infinity'::date
                           )
                       )
                   END AS effective_valid_to,
                   price.criado_em,
                   COALESCE((
                       SELECT state.versao_entidade
                       FROM cortex_estado_entidade state
                       WHERE state.tipo_entidade = 'SERVICE_PRICE_VERSION'
                         AND state.entidade_id = price.id
                   ), 0) AS entity_version
            FROM service_price_version price
            LEFT JOIN service_price_version successor
              ON successor.supersedes_id = price.id
             AND successor.commit_revision <= :snapshotRevision
            LEFT JOIN service_price_version_cancellation cancellation
              ON cancellation.price_version_id = price.id
             AND cancellation.commit_revision <= :snapshotRevision
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PostgresqlServicePriceCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public boolean worksiteExists(String obraId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM obra
                WHERE id = ? AND arquivado_em IS NULL
                """, Integer.class, obraId);
        return count != null && count == 1;
    }

    @Override
    public Optional<CatalogMutation> findMutation(
            String actorId,
            String clientMutationId
    ) {
        return jdbc.query("""
                SELECT operation_type, entity_id, request_hash
                FROM service_catalog_mutation
                WHERE ator_id = ?
                  AND client_mutation_id = ?
                """, resultSet -> resultSet.next()
                ? Optional.of(new CatalogMutation(
                        resultSet.getString("operation_type"),
                        resultSet.getString("entity_id"),
                        resultSet.getString("request_hash")
                ))
                : Optional.empty(), actorId, clientMutationId);
    }

    @Override
    public Optional<ServiceCatalogEntry> findService(String serviceId) {
        return jdbc.query("""
                SELECT id, codigo, nome, descricao, status, criado_em
                FROM catalogo_servico
                WHERE id = ?
                """, resultSet -> resultSet.next()
                ? Optional.of(mapService(resultSet))
                : Optional.empty(), serviceId);
    }

    @Override
    public Optional<ServicePriceVersion> findPrice(String obraId, String priceId) {
        return jdbc.query(
                PRICE_PROJECTION + " WHERE price.obra_id = ? AND price.id = ?",
                resultSet -> resultSet.next()
                        ? Optional.of(mapPrice(resultSet))
                        : Optional.empty(),
                obraId,
                priceId
        );
    }

    @Override
    public ServiceCatalogEntry createService(CreateServiceRecord record) {
        rejectConcurrentMutationReplay(
                record.actorId(), record.clientMutationId()
        );
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                "catalogo_servico:" + record.code()
        );
        try {
            jdbc.update("""
                    INSERT INTO catalogo_servico (
                        id, codigo, nome, descricao, status,
                        obra_autorizadora_id, criado_por, criado_em
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    """,
                    record.id(),
                    record.code(),
                    record.name(),
                    record.description(),
                    record.worksiteId(),
                    record.actorId(),
                    Timestamp.from(record.createdAt())
            );
            insertMutation(
                    record.actorId(), record.clientMutationId(),
                    "SERVICE_CREATED", record.id(), record.requestHash(),
                    record.createdAt()
            );
        } catch (DataAccessException exception) {
            if (contains(exception, "uq_catalogo_servico_codigo_normalizado")
                    || contains(exception, "catalogo_servico_codigo")) {
                throw new ServiceCatalogCodeConflictException();
            }
            throw exception;
        }
        return findService(record.id()).orElseThrow();
    }

    @Override
    public ServicePriceVersion createPrice(CreatePriceRecord record) {
        return insertPrice(record, "SERVICE_PRICE_VERSION_CREATED");
    }

    @Override
    public ServicePriceVersion supersedePrice(CreatePriceRecord record) {
        if (record.supersedesId() == null) {
            throw new IllegalArgumentException("Superseded price id is required.");
        }
        return insertPrice(record, "SERVICE_PRICE_VERSION_SUPERSEDED");
    }

    @Override
    public ServicePriceVersion cancelPrice(CancelPriceRecord record) {
        rejectConcurrentMutationReplay(
                record.actorId(), record.clientMutationId()
        );
        try {
            jdbc.update("""
                    INSERT INTO service_price_version_cancellation (
                        id, price_version_id, obra_id, vigencia_cancelamento,
                        motivo, criado_por, criado_em
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.id(),
                    record.priceId(),
                    record.obraId(),
                    record.effectiveAt(),
                    record.reason(),
                    record.actorId(),
                    Timestamp.from(record.createdAt())
            );
            insertMutation(
                    record.actorId(), record.clientMutationId(),
                    "SERVICE_PRICE_VERSION_CANCELLED", record.priceId(),
                    record.requestHash(), record.createdAt()
            );
        } catch (DataAccessException exception) {
            if (contains(exception, "SERVICE_PRICE_CANCELLATION_INVALID")) {
                throw new ServicePriceCancellationException(
                        "SERVICE_PRICE_CANCELLATION_INVALID"
                );
            }
            if (contains(exception, "uq_service_price_version_cancellation")) {
                throw new ServicePriceCancellationException(
                        "SERVICE_PRICE_ALREADY_TERMINATED"
                );
            }
            throw exception;
        }
        return findPrice(record.obraId(), record.priceId()).orElseThrow();
    }

    @Override
    public ServiceCatalogPage list(
            String obraId,
            String query,
            String cursor,
            int limit
    ) {
        String normalizedQuery = normalizeQuery(query);
        CatalogCursor decoded = decodeCursor(cursor, normalizedQuery);
        long currentRevision = currentRevision();
        if (decoded != null && decoded.highWaterMark() != currentRevision) {
            throw staleSnapshot();
        }
        long snapshotRevision = decoded == null
                ? currentRevision
                : decoded.highWaterMark();
        CatalogCoverageCounts totals = coverageCounts(
                obraId, normalizedQuery, snapshotRevision
        );

        MapSqlParameterSource pageParameters = snapshotParameters(
                obraId, normalizedQuery, snapshotRevision
        ).addValue("limit", limit + 1);
        StringBuilder pageSql = new StringBuilder("""
                SELECT id, codigo, nome, descricao, status, criado_em
                FROM catalogo_servico service
                WHERE service.commit_revision <= :snapshotRevision
                """).append(searchPredicate(normalizedQuery));
        if (decoded != null) {
            pageSql.append("""
                     AND (
                         service.codigo > :cursorCode
                         OR (service.codigo = :cursorCode AND service.id > :cursorId)
                     )
                    """);
            pageParameters.addValue("cursorCode", decoded.code());
            pageParameters.addValue("cursorId", decoded.id());
        }
        pageSql.append(" ORDER BY service.codigo, service.id LIMIT :limit");

        List<ServiceCatalogEntry> loaded = namedJdbc.query(
                pageSql.toString(),
                pageParameters,
                (resultSet, rowNumber) -> mapService(resultSet)
        );
        boolean hasMore = loaded.size() > limit;
        List<ServiceCatalogEntry> services = hasMore
                ? List.copyOf(loaded.subList(0, limit))
                : List.copyOf(loaded);
        Map<String, List<ServicePriceVersion>> prices = pricesByService(
                obraId,
                services.stream().map(ServiceCatalogEntry::id).toList(),
                snapshotRevision
        );
        List<ServiceCatalogRow> rows = services.stream()
                .map(service -> new ServiceCatalogRow(
                        service,
                        prices.getOrDefault(service.id(), List.of())
                ))
                .toList();
        String nextCursor = hasMore && !services.isEmpty()
                ? encodeCursor(
                        services.getLast(), normalizedQuery, snapshotRevision
                )
                : null;
        int returnedPriceVersions = prices.values().stream()
                .mapToInt(List::size)
                .sum();
        int returnedCancellations = cancellationCount(
                obraId,
                services.stream().map(ServiceCatalogEntry::id).toList(),
                snapshotRevision
        );
        return new ServiceCatalogPage(
                rows,
                nextCursor,
                totals.serviceCount(),
                totals.priceVersionCount(),
                totals.cancellationCount(),
                rows.size(),
                returnedPriceVersions,
                returnedCancellations,
                nextCursor == null ? "COMPLETE" : "PARTIAL",
                snapshotRevision
        );
    }

    private ServicePriceVersion insertPrice(
            CreatePriceRecord record,
            String operationType
    ) {
        rejectConcurrentMutationReplay(
                record.actorId(), record.clientMutationId()
        );
        lockPriceKey(record);
        Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(max(versao), 0) + 1
                FROM service_price_version
                WHERE obra_id = ?
                  AND service_id = ?
                  AND unidade = ?
                  AND moeda = ?
                """, Integer.class, record.obraId(), record.serviceId(),
                record.unit(), record.currency());
        try {
            jdbc.update("""
                    INSERT INTO service_price_version (
                        id, obra_id, service_id, unidade, moeda, versao,
                        valor_unitario, vigencia_inicio, vigencia_fim, fonte,
                        supersedes_id, criado_por, criado_em
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.id(),
                    record.obraId(),
                    record.serviceId(),
                    record.unit(),
                    record.currency(),
                    nextVersion,
                    record.unitPrice(),
                    record.validFrom(),
                    record.validTo(),
                    record.source(),
                    record.supersedesId(),
                    record.actorId(),
                    Timestamp.from(record.createdAt())
            );
            insertMutation(
                    record.actorId(), record.clientMutationId(), operationType,
                    record.id(), record.requestHash(), record.createdAt()
            );
        } catch (DataAccessException exception) {
            if (contains(exception, "SERVICE_PRICE_VALIDITY_OVERLAP")) {
                throw new ServicePriceValidityOverlapException();
            }
            if (contains(exception, "SERVICE_PRICE_SUPERSESSION_INVALID")) {
                throw new ServicePriceCancellationException(
                        "SERVICE_PRICE_SUPERSESSION_INVALID"
                );
            }
            if (contains(exception, "SERVICE_PRICE_ALREADY_TERMINATED")
                    || contains(exception, "uq_service_price_version_supersedes")) {
                throw new ServicePriceCancellationException(
                        "SERVICE_PRICE_ALREADY_TERMINATED"
                );
            }
            throw exception;
        }
        return findPrice(record.obraId(), record.id()).orElseThrow();
    }

    private void lockPriceKey(CreatePriceRecord record) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                String.join(":", "service_price", record.obraId(),
                        record.serviceId(), record.unit(), record.currency())
        );
    }

    private void rejectConcurrentMutationReplay(
            String actorId,
            String clientMutationId
    ) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                "service_catalog_mutation:" + actorId + ":" + clientMutationId
        );
        findMutation(actorId, clientMutationId)
                .ifPresent(receipt -> {
                    throw new CatalogMutationReplayException(receipt);
                });
    }

    private void insertMutation(
            String actorId,
            String clientMutationId,
            String operationType,
            String entityId,
            String requestHash,
            Instant createdAt
    ) {
        jdbc.update("""
                INSERT INTO service_catalog_mutation (
                    id, ator_id, client_mutation_id, operation_type,
                    entity_id, request_hash, criado_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                actorId,
                clientMutationId,
                operationType,
                entityId,
                requestHash,
                Timestamp.from(createdAt)
        );
    }

    private Map<String, List<ServicePriceVersion>> pricesByService(
            String obraId,
            List<String> serviceIds,
            long snapshotRevision
    ) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("obraId", obraId)
                .addValue("serviceIds", serviceIds)
                .addValue("snapshotRevision", snapshotRevision);
        List<ServicePriceVersion> prices = namedJdbc.query(
                SNAPSHOT_PRICE_PROJECTION + """
                         WHERE price.obra_id = :obraId
                           AND price.service_id IN (:serviceIds)
                           AND price.commit_revision <= :snapshotRevision
                         ORDER BY price.service_id, price.versao DESC, price.id
                        """,
                parameters,
                (resultSet, rowNumber) -> mapPrice(resultSet)
        );
        Map<String, List<ServicePriceVersion>> grouped = new LinkedHashMap<>();
        prices.forEach(price -> grouped.computeIfAbsent(
                price.serviceId(), ignored -> new ArrayList<>()
        ).add(price));
        Map<String, List<ServicePriceVersion>> immutable = new HashMap<>();
        grouped.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private int cancellationCount(
            String obraId,
            List<String> serviceIds,
            long snapshotRevision
    ) {
        if (serviceIds.isEmpty()) {
            return 0;
        }
        Integer count = namedJdbc.queryForObject("""
                SELECT count(*)
                FROM service_price_version_cancellation cancellation
                JOIN service_price_version price
                  ON price.id = cancellation.price_version_id
                 AND price.obra_id = cancellation.obra_id
                WHERE cancellation.obra_id = :obraId
                  AND cancellation.commit_revision <= :snapshotRevision
                  AND price.commit_revision <= :snapshotRevision
                  AND price.service_id IN (:serviceIds)
                """, new MapSqlParameterSource()
                .addValue("obraId", obraId)
                .addValue("snapshotRevision", snapshotRevision)
                .addValue("serviceIds", serviceIds), Integer.class);
        return count == null ? 0 : count;
    }

    private CatalogCoverageCounts coverageCounts(
            String obraId,
            String query,
            long snapshotRevision
    ) {
        MapSqlParameterSource parameters = snapshotParameters(
                obraId, query, snapshotRevision
        );
        String search = searchPredicate(query);
        Long serviceCount = namedJdbc.queryForObject("""
                SELECT count(*)
                FROM catalogo_servico service
                WHERE service.commit_revision <= :snapshotRevision
                """ + search, parameters, Long.class);
        Long priceCount = namedJdbc.queryForObject("""
                SELECT count(*)
                FROM service_price_version price
                JOIN catalogo_servico service ON service.id = price.service_id
                WHERE price.obra_id = :obraId
                  AND price.commit_revision <= :snapshotRevision
                  AND service.commit_revision <= :snapshotRevision
                """ + search, parameters, Long.class);
        Long cancellationCount = namedJdbc.queryForObject("""
                SELECT count(*)
                FROM service_price_version_cancellation cancellation
                JOIN service_price_version price
                  ON price.id = cancellation.price_version_id
                 AND price.obra_id = cancellation.obra_id
                JOIN catalogo_servico service ON service.id = price.service_id
                WHERE cancellation.obra_id = :obraId
                  AND cancellation.commit_revision <= :snapshotRevision
                  AND price.commit_revision <= :snapshotRevision
                  AND service.commit_revision <= :snapshotRevision
                """ + search, parameters, Long.class);
        return new CatalogCoverageCounts(
                serviceCount == null ? 0L : serviceCount,
                priceCount == null ? 0L : priceCount,
                cancellationCount == null ? 0L : cancellationCount
        );
    }

    private static MapSqlParameterSource snapshotParameters(
            String obraId,
            String query,
            long snapshotRevision
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("obraId", obraId)
                .addValue("snapshotRevision", snapshotRevision);
        if (query != null) {
            parameters.addValue("searchPattern", "%" + escapeLike(query) + "%");
        }
        return parameters;
    }

    private static String searchPredicate(String query) {
        if (query == null) {
            return "";
        }
        return """
                 AND (
                    service.codigo ILIKE :searchPattern ESCAPE '\\'
                    OR service.nome ILIKE :searchPattern ESCAPE '\\'
                    OR COALESCE(service.descricao, '')
                        ILIKE :searchPattern ESCAPE '\\'
                 )
                """;
    }

    private long currentRevision() {
        Long revision = jdbc.queryForObject("""
                SELECT revision FROM service_catalog_revision
                WHERE singleton = TRUE
                """, Long.class);
        if (revision == null || revision < 0) {
            throw new IllegalStateException("Service catalog revision unavailable.");
        }
        return revision;
    }

    private static String normalizeQuery(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static ServiceCatalogEntry mapService(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new ServiceCatalogEntry(
                resultSet.getString("id"),
                resultSet.getString("codigo"),
                resultSet.getString("nome"),
                resultSet.getString("descricao"),
                resultSet.getString("status"),
                resultSet.getTimestamp("criado_em").toInstant()
        );
    }

    private static ServicePriceVersion mapPrice(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new ServicePriceVersion(
                resultSet.getString("id"),
                resultSet.getString("obra_id"),
                resultSet.getString("service_id"),
                resultSet.getString("unidade"),
                resultSet.getString("moeda"),
                resultSet.getInt("versao"),
                resultSet.getBigDecimal("valor_unitario"),
                resultSet.getObject("vigencia_inicio", LocalDate.class),
                resultSet.getObject("vigencia_fim", LocalDate.class),
                resultSet.getString("supersedes_id"),
                resultSet.getString("effective_status"),
                resultSet.getObject("effective_valid_to", LocalDate.class),
                resultSet.getTimestamp("criado_em").toInstant(),
                resultSet.getString("fonte"),
                resultSet.getLong("entity_version")
        );
    }

    private static CatalogCursor decodeCursor(String token, String query) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > 512) {
                throw invalidCursor();
            }
            String[] fields = new String(bytes, StandardCharsets.UTF_8)
                    .split("\\n", -1);
            if (fields.length != 5
                    || !"v2".equals(fields[0])
                    || !queryHash(query).equals(fields[1])
                    || fields[3].isBlank()
                    || fields[3].length() > 80) {
                throw invalidCursor();
            }
            long highWaterMark = Long.parseLong(fields[2]);
            if (highWaterMark < 0) {
                throw invalidCursor();
            }
            return new CatalogCursor(
                    highWaterMark,
                    fields[3],
                    UUID.fromString(fields[4]).toString()
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static String encodeCursor(
            ServiceCatalogEntry service,
            String query,
            long highWaterMark
    ) {
        String value = String.join(
                "\n", "v2", queryHash(query), Long.toString(highWaterMark),
                service.code(), service.id()
        );
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryHash(String query) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((query == null ? "" : query)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível.", impossible);
        }
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "SERVICE_CATALOG_CURSOR_INVALID"
        );
    }

    private static ResponseStatusException staleSnapshot() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "SERVICE_CATALOG_SNAPSHOT_STALE"
        );
    }

    private static boolean contains(Throwable failure, String fragment) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record CatalogCursor(long highWaterMark, String code, String id) {
    }

    private record CatalogCoverageCounts(
            long serviceCount,
            long priceVersionCount,
            long cancellationCount
    ) {
    }
}
