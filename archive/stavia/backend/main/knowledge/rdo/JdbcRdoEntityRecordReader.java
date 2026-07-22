package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class JdbcRdoEntityRecordReader implements RdoEntityRecordReader {

    private final JdbcTemplate jdbcTemplate;
    private final RdoRecordSqlBuilder sqlBuilder;

    public JdbcRdoEntityRecordReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlBuilder = new RdoRecordSqlBuilder();
    }

    @Override
    public List<Map<String, Object>> findRecords(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    ) {
        return jdbcTemplate.query(
                sqlBuilder.selectRecords(entity, query),
                (resultSet, rowNumber) -> mapRecord(entity, resultSet),
                sqlBuilder.recordParams(entity, query)
        );
    }

    @Override
    public List<Map<String, Object>> aggregate(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute,
            AggregationSpec spec,
            RdoRecordQuery query
    ) {
        boolean grouped = "rdo".equals(spec.groupBy());

        return jdbcTemplate.query(
                sqlBuilder.selectAggregate(entity, attribute, spec, query),
                (resultSet, rowNumber) ->
                        mapAggregate(resultSet, grouped),
                sqlBuilder.aggregateParams(entity, query)
        );
    }

    private Map<String, Object> mapRecord(
            RdoOntologyEntity entity,
            ResultSet resultSet
    ) throws SQLException {
        Map<String, Object> record = new LinkedHashMap<>();

        putText(record, "registroId", resultSet.getString("registro_id"));
        putText(record, "rdoId", resultSet.getString("rdo_id"));
        putText(record, "numeroRdo", resultSet.getString("numero_rdo"));
        putText(record, "dataRdo", text(resultSet.getDate("data_rdo")));
        putText(record, "statusRdo", resultSet.getString("status_rdo"));
        putText(record, "obraId", resultSet.getString("obra_id"));

        for (RdoOntologyAttribute attribute : entity.attributes()) {
            putText(
                    record,
                    attribute.name(),
                    columnText(resultSet, attribute.column())
            );
        }

        for (String unitColumn : unitColumns(entity)) {
            putText(
                    record,
                    unitColumn,
                    columnText(resultSet, unitColumn)
            );
        }

        return record;
    }

    private Map<String, Object> mapAggregate(
            ResultSet resultSet,
            boolean grouped
    ) throws SQLException {
        Map<String, Object> record = new LinkedHashMap<>();

        if (grouped) {
            putText(record, "rdoId", resultSet.getString("rdo_id"));
            putText(record, "numeroRdo", resultSet.getString("numero_rdo"));
            putText(record, "dataRdo", text(resultSet.getDate("data_rdo")));
        }

        putText(record, "valor", decimalText(resultSet, "valor"));
        putText(record, "linhas", resultSet.getString("linhas"));

        return record;
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

    private String columnText(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Object value = resultSet.getObject(column);

        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }

        return String.valueOf(value);
    }

    private String decimalText(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);

        return value == null
                ? null
                : value.stripTrailingZeros().toPlainString();
    }

    private String text(java.sql.Date value) {
        return value == null ? null : value.toLocalDate().toString();
    }

    private void putText(
            Map<String, Object> record,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            record.put(key, value.trim());
        }
    }
}
