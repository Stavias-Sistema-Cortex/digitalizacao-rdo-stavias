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
                   price.vigencia_inicio,
                   price.vigencia_fim,
                   price.supersedes_id,
                   CASE
                       WHEN cancellation.id IS NOT NULL THEN 'CANCELLED'
                       WHEN successor.id IS NOT NULL THEN 'SUPERSEDED'
                       ELSE 'ACTIVE'
                   END AS effective_status,
                   cortex_price_effective_valid_to(price.id) AS effective_valid_to,
                   price.criado_em
            FROM service_price_version price
            LEFT JOIN service_price_version successor
              ON successor.supersedes_id = price.id
            LEFT JOIN service_price_version_cancellation cancellation
              ON cancellation.price_version_id = price.id
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PostgresqlServicePriceCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
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
        List<Object> filterParameters = new ArrayList<>();
        String filterSql = searchFilter(normalizedQuery, filterParameters);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM catalogo_servico service " + filterSql,
                Long.class,
                filterParameters.toArray()
        );

        List<Object> pageParameters = new ArrayList<>(filterParameters);
        StringBuilder pageSql = new StringBuilder("""
                SELECT id, codigo, nome, descricao, status, criado_em
                FROM catalogo_servico service
                """).append(filterSql);
        if (decoded != null) {
            pageSql.append(filterSql.isEmpty() ? " WHERE " : " AND ")
                    .append("(service.codigo > ? OR (service.codigo = ? AND service.id > ?))");
            pageParameters.add(decoded.code());
            pageParameters.add(decoded.code());
            pageParameters.add(decoded.id());
        }
        pageSql.append(" ORDER BY service.codigo, service.id LIMIT ?");
        pageParameters.add(limit + 1);

        List<ServiceCatalogEntry> loaded = jdbc.query(
                pageSql.toString(),
                (resultSet, rowNumber) -> mapService(resultSet),
                pageParameters.toArray()
        );
        boolean hasMore = loaded.size() > limit;
        List<ServiceCatalogEntry> services = hasMore
                ? List.copyOf(loaded.subList(0, limit))
                : List.copyOf(loaded);
        Map<String, List<ServicePriceVersion>> prices = pricesByService(
                obraId,
                services.stream().map(ServiceCatalogEntry::id).toList()
        );
        List<ServiceCatalogRow> rows = services.stream()
                .map(service -> new ServiceCatalogRow(
                        service,
                        prices.getOrDefault(service.id(), List.of())
                ))
                .toList();
        String nextCursor = hasMore && !services.isEmpty()
                ? encodeCursor(services.getLast(), normalizedQuery)
                : null;
        return new ServiceCatalogPage(
                rows,
                nextCursor,
                total == null ? 0L : total,
                rows.size(),
                nextCursor == null ? "COMPLETE" : "PARTIAL",
                highWaterMark(obraId)
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
            if (contains(exception, "SERVICE_PRICE_SUPERSESSION_INVALID")
                    || contains(exception, "SERVICE_PRICE_ALREADY_TERMINATED")
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
            List<String> serviceIds
    ) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("obraId", obraId)
                .addValue("serviceIds", serviceIds);
        List<ServicePriceVersion> prices = namedJdbc.query(
                PRICE_PROJECTION + """
                         WHERE price.obra_id = :obraId
                           AND price.service_id IN (:serviceIds)
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

    private Instant highWaterMark(String obraId) {
        Timestamp timestamp = jdbc.queryForObject("""
                SELECT GREATEST(
                    COALESCE((SELECT max(criado_em) FROM catalogo_servico),
                             '1970-01-01T00:00:00Z'::timestamptz),
                    COALESCE((SELECT max(criado_em) FROM service_price_version
                              WHERE obra_id = ?),
                             '1970-01-01T00:00:00Z'::timestamptz),
                    COALESCE((SELECT max(criado_em)
                              FROM service_price_version_cancellation
                              WHERE obra_id = ?),
                             '1970-01-01T00:00:00Z'::timestamptz)
                )
                """, Timestamp.class, obraId, obraId);
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private String searchFilter(String query, List<Object> parameters) {
        if (query == null) {
            return "";
        }
        String pattern = "%" + escapeLike(query) + "%";
        parameters.add(pattern);
        parameters.add(pattern);
        parameters.add(pattern);
        return """
                 WHERE (
                    service.codigo ILIKE ? ESCAPE '\\'
                    OR service.nome ILIKE ? ESCAPE '\\'
                    OR COALESCE(service.descricao, '') ILIKE ? ESCAPE '\\'
                 )
                """;
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
                resultSet.getTimestamp("criado_em").toInstant()
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
            if (fields.length != 4
                    || !"v1".equals(fields[0])
                    || !queryHash(query).equals(fields[1])
                    || fields[2].isBlank()
                    || fields[2].length() > 80) {
                throw invalidCursor();
            }
            return new CatalogCursor(
                    fields[2], UUID.fromString(fields[3]).toString()
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static String encodeCursor(
            ServiceCatalogEntry service,
            String query
    ) {
        String value = String.join(
                "\n", "v1", queryHash(query), service.code(), service.id()
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

    private record CatalogCursor(String code, String id) {
    }
}
