package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the SQL for the generic RDO record source from the ontology.
 * Every identifier comes from the versioned ontology resource, never from
 * user input; the identifier check below is defence in depth. User-provided
 * values (worksite, period, identity term) are always bound parameters.
 */
public class RdoRecordSqlBuilder {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[a-z0-9_]+");

    private static final int RANKING_LIMIT = 5;

    public String selectRecords(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    ) {
        StringBuilder sql = new StringBuilder("SELECT ");

        sql.append(contextColumns(entity));

        for (RdoOntologyAttribute attribute : entity.attributes()) {
            sql.append(", ")
                    .append(column(entity, attribute.column()));
        }

        for (String unitColumn : unitColumns(entity)) {
            sql.append(", ")
                    .append(column(entity, unitColumn));
        }

        sql.append(fromClause(entity));
        sql.append(whereClause(entity, query));
        sql.append(" ORDER BY r.data_rdo DESC, r.id DESC LIMIT ?");

        return sql.toString();
    }

    public Object[] recordParams(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    ) {
        List<Object> params = whereParams(entity, query);
        params.add(query.limit());
        return params.toArray();
    }

    public String selectAggregate(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute,
            AggregationSpec spec,
            RdoRecordQuery query
    ) {
        StringBuilder sql = new StringBuilder("SELECT ");

        boolean grouped = "rdo".equals(spec.groupBy());

        if (grouped) {
            sql.append("r.id AS rdo_id, r.numero_rdo, r.data_rdo, ");
        }

        sql.append(aggregateFunction(entity, attribute, spec))
                .append(" AS valor, COUNT(*) AS linhas");

        sql.append(fromClause(entity));
        sql.append(whereClause(entity, query));

        if (grouped) {
            sql.append(" GROUP BY r.id, r.numero_rdo, r.data_rdo");
            sql.append(" ORDER BY valor ");
            sql.append(
                    "MIN".equalsIgnoreCase(spec.function())
                            ? "ASC"
                            : "DESC"
            );
            sql.append(" LIMIT ").append(RANKING_LIMIT);
        }

        return sql.toString();
    }

    public Object[] aggregateParams(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    ) {
        return whereParams(entity, query).toArray();
    }

    private String aggregateFunction(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute,
            AggregationSpec spec
    ) {
        String function = spec.function() == null
                ? "SUM"
                : spec.function().toUpperCase(Locale.ROOT);

        if ("COUNT".equals(function)) {
            if (attribute.name().equals(entity.countAttribute())) {
                return "SUM("
                        + column(entity, attribute.column())
                        + ")";
            }

            return "COUNT(*)";
        }

        if (!Set.of("SUM", "AVG", "MAX", "MIN").contains(function)) {
            throw new IllegalStateException(
                    "Função de agregação não suportada: " + function
            );
        }

        return function
                + "("
                + column(entity, attribute.column())
                + ")";
    }

    private String contextColumns(RdoOntologyEntity entity) {
        if (entity.header()) {
            return "r.id AS registro_id, r.id AS rdo_id, r.numero_rdo, "
                    + "r.data_rdo, r.status AS status_rdo, r.obra_id";
        }

        return "t.id AS registro_id, r.id AS rdo_id, r.numero_rdo, "
                + "r.data_rdo, r.status AS status_rdo, r.obra_id";
    }

    private String fromClause(RdoOntologyEntity entity) {
        if (entity.header()) {
            return " FROM rdo r";
        }

        return " FROM "
                + safeIdentifier(entity.table())
                + " t JOIN rdo r ON r.id = t.rdo_id";
    }

    private String whereClause(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    ) {
        StringBuilder where = new StringBuilder(
                " WHERE r.obra_id = ? AND r.cancelado_em IS NULL"
        );

        if (entity.filter() != null) {
            where.append(" AND ").append(entity.filter());
        }

        if (query.startDate() != null) {
            where.append(" AND r.data_rdo >= ?");
        }

        if (query.endDate() != null) {
            where.append(" AND r.data_rdo <= ?");
        }

        if (query.rdoId() != null) {
            where.append(" AND r.id = ?");
        }

        List<RdoOntologyAttribute> identityAttributes =
                entity.identityAttributes();

        if (query.identityTerm() != null
                && !identityAttributes.isEmpty()) {
            where.append(" AND (");

            for (int index = 0; index < identityAttributes.size(); index++) {
                if (index > 0) {
                    where.append(" OR ");
                }

                where.append(
                        column(
                                entity,
                                identityAttributes.get(index).column()
                        )
                ).append(" LIKE ?");
            }

            where.append(")");
        }

        return where.toString();
    }

    private List<Object> whereParams(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    ) {
        List<Object> params = new ArrayList<>();
        params.add(query.worksiteId());

        if (query.startDate() != null) {
            params.add(query.startDate());
        }

        if (query.endDate() != null) {
            params.add(query.endDate());
        }

        if (query.rdoId() != null) {
            params.add(query.rdoId());
        }

        if (query.identityTerm() != null
                && !entity.identityAttributes().isEmpty()) {
            for (int index = 0;
                    index < entity.identityAttributes().size();
                    index++) {
                params.add("%" + query.identityTerm() + "%");
            }
        }

        return params;
    }

    private Set<String> unitColumns(RdoOntologyEntity entity) {
        Set<String> columns = new LinkedHashSet<>();

        for (RdoOntologyAttribute attribute : entity.attributes()) {
            if (attribute.unitColumn() != null) {
                columns.add(attribute.unitColumn());
            }
        }

        for (RdoOntologyAttribute attribute : entity.attributes()) {
            columns.remove(attribute.column());
        }

        return columns;
    }

    private String column(RdoOntologyEntity entity, String name) {
        String prefix = entity.header() ? "r." : "t.";
        return prefix + safeIdentifier(name);
    }

    private String safeIdentifier(String value) {
        if (value == null
                || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException(
                    "Identificador SQL inválido na ontologia RDO: " + value
            );
        }

        return value;
    }
}
