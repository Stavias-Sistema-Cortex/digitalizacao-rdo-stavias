package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.AllocationSourceType;

import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceAllocationRepository {

    private final JdbcTemplate jdbc;

    public FinanceAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AllocationMutation> findMutation(
            String actorId,
            String clientMutationId
    ) {
        return findMutation(actorId, clientMutationId, false);
    }

    public Optional<AllocationMutation> findMutationCurrent(
            String actorId,
            String clientMutationId
    ) {
        return findMutation(actorId, clientMutationId, true);
    }

    private Optional<AllocationMutation> findMutation(
            String actorId,
            String clientMutationId,
            boolean currentRead
    ) {
        List<AllocationMutation> rows = jdbc.query(
                """
                SELECT rateio_id, origem_tipo, origem_id
                FROM finance_rateio_mutacao
                WHERE ator_id = ?
                  AND client_mutation_id = ?
                LIMIT 1%s
                """.formatted(currentRead ? " FOR UPDATE" : ""),
                (rs, rowNum) -> new AllocationMutation(
                        rs.getString("rateio_id"),
                        AllocationSourceType.valueOf(
                                rs.getString("origem_tipo")
                        ),
                        rs.getString("origem_id")
                ),
                actorId,
                clientMutationId
        );
        return rows.stream().findFirst();
    }

    public void claimMutation(
            String actorId,
            String clientMutationId,
            AllocationSourceType sourceType,
            String sourceId
    ) {
        jdbc.update(
                """
                INSERT INTO finance_rateio_mutacao (
                    ator_id, client_mutation_id, origem_tipo, origem_id
                ) VALUES (?, ?, ?, ?)
                """,
                actorId,
                clientMutationId,
                sourceType.name(),
                sourceId
        );
    }

    public void completeMutation(
            String actorId,
            String clientMutationId,
            String allocationId
    ) {
        if (jdbc.update(
                """
                UPDATE finance_rateio_mutacao
                SET rateio_id = ?, concluido_em = CURRENT_TIMESTAMP(6)
                WHERE ator_id = ?
                  AND client_mutation_id = ?
                  AND rateio_id IS NULL
                """,
                allocationId,
                actorId,
                clientMutationId
        ) != 1) {
            throw new IllegalStateException(
                    "A reserva idempotente do rateio não pôde ser concluída."
            );
        }
    }

    public Optional<AllocationSource> lockSource(
            AllocationSourceType type,
            String sourceId
    ) {
        String sql = switch (type) {
            case COMPRA -> """
                    SELECT id, obra_id, moeda,
                           COALESCE(valor_contratado, valor_previsto)
                               AS valor_total,
                           (arquivado_em IS NOT NULL) AS arquivado
                    FROM finance_compra
                    WHERE id = ?
                    FOR UPDATE
                    """;
            case NOTA_FISCAL -> """
                    SELECT id, obra_id, moeda, valor_liquido AS valor_total,
                           (arquivado_em IS NOT NULL) AS arquivado
                    FROM finance_nota_fiscal
                    WHERE id = ?
                    FOR UPDATE
                    """;
            case LANCAMENTO -> """
                    SELECT id, obra_id, moeda, valor_original AS valor_total,
                           (arquivado_em IS NOT NULL) AS arquivado
                    FROM finance_lancamento
                    WHERE id = ?
                    FOR UPDATE
                    """;
        };
        List<AllocationSource> rows = jdbc.query(
                sql,
                (rs, rowNum) -> new AllocationSource(
                        rs.getString("id"),
                        type,
                        rs.getString("obra_id"),
                        rs.getString("moeda"),
                        rs.getBigDecimal("valor_total"),
                        rs.getBoolean("arquivado")
                ),
                sourceId
        );
        return rows.stream().findFirst();
    }

    public Optional<AllocationSnapshot> lockBySource(
            AllocationSourceType type,
            String sourceId
    ) {
        return findHeader(sourceClause(type) + " FOR UPDATE", sourceId)
                .map(header -> snapshot(header, items(header.id(), true)));
    }

    public Optional<AllocationSnapshot> findBySource(
            AllocationSourceType type,
            String sourceId
    ) {
        return findHeader(sourceClause(type), sourceId)
                .map(header -> snapshot(header, items(header.id(), false)));
    }

    public Optional<AllocationSnapshot> findById(String allocationId) {
        return findHeader(" WHERE r.id = ?", allocationId)
                .map(header -> snapshot(header, items(header.id(), false)));
    }

    public Optional<AllocationSnapshot> findByIdCurrent(String allocationId) {
        return findHeader(" WHERE r.id = ? FOR UPDATE", allocationId)
                .map(header -> snapshot(header, items(header.id(), true)));
    }

    public Map<String, AllocationUnit> lockUnits(Set<String> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = unitIds.stream()
                .map(ignored -> "?")
                .collect(Collectors.joining(","));
        List<AllocationUnit> rows = jdbc.query(
                """
                SELECT id, tipo, obra_id, status
                FROM finance_unidade_controle
                WHERE id IN (%s)
                FOR UPDATE
                """.formatted(placeholders),
                (rs, rowNum) -> new AllocationUnit(
                        rs.getString("id"),
                        FinancialUnitType.valueOf(rs.getString("tipo")),
                        rs.getString("obra_id"),
                        rs.getString("status")
                ),
                unitIds.toArray()
        );
        Map<String, AllocationUnit> result = new LinkedHashMap<>();
        rows.forEach(unit -> result.put(unit.id(), unit));
        return result;
    }

    public boolean activeCostCenterExists(String id) {
        return exists(
                """
                SELECT 1 FROM finance_centro_custo
                WHERE id = ? AND status = 'ATIVO'
                """,
                id
        );
    }

    public boolean activeCategoryExists(String id) {
        return exists(
                """
                SELECT 1 FROM finance_categoria
                WHERE id = ? AND status = 'ATIVA'
                """,
                id
        );
    }

    public void insertHeader(AllocationHeaderWrite write) {
        String sql = switch (write.sourceType()) {
            case COMPRA -> """
                    INSERT INTO finance_rateio (
                        id, client_mutation_id, compra_id, moeda,
                        valor_total, status, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, ?, 'ATIVO', ?, ?)
                    """;
            case NOTA_FISCAL -> """
                    INSERT INTO finance_rateio (
                        id, client_mutation_id, nota_fiscal_id, moeda,
                        valor_total, status, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, ?, 'ATIVO', ?, ?)
                    """;
            case LANCAMENTO -> """
                    INSERT INTO finance_rateio (
                        id, client_mutation_id, lancamento_id, moeda,
                        valor_total, status, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, ?, 'ATIVO', ?, ?)
                    """;
        };
        jdbc.update(
                sql,
                write.id(),
                write.clientMutationId(),
                write.sourceId(),
                write.currency(),
                write.total(),
                write.actorId(),
                write.actorId()
        );
    }

    public boolean updateHeader(
            String id,
            long expectedVersion,
            BigDecimal total,
            String actorId
    ) {
        return jdbc.update(
                """
                UPDATE finance_rateio
                SET valor_total = ?, atualizado_por = ?,
                    atualizado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status = 'ATIVO'
                """,
                total,
                actorId,
                id,
                expectedVersion
        ) == 1;
    }

    public void replaceItems(
            String allocationId,
            List<AllocationItemWrite> items,
            String actorId
    ) {
        jdbc.update(
                "DELETE FROM finance_rateio_item WHERE rateio_id = ?",
                allocationId
        );
        for (AllocationItemWrite item : items) {
            jdbc.update(
                    """
                    INSERT INTO finance_rateio_item (
                        id, rateio_id, unidade_controle_id,
                        centro_custo_id, categoria_id, valor_alocado,
                        percentual, ordem, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    item.id(),
                    allocationId,
                    item.unitId(),
                    item.costCenterId(),
                    item.categoryId(),
                    item.value(),
                    item.percentage(),
                    item.order(),
                    actorId,
                    actorId
            );
        }
    }

    public void insertHistory(AllocationHistoryWrite write) {
        jdbc.update(
                """
                INSERT INTO finance_rateio_historico (
                    id, rateio_id, client_mutation_id, operacao,
                    versao_anterior, versao_nova, estado_anterior_json,
                    estado_novo_json, alterado_por,
                    correlacao_id, dispositivo_id
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON),
                          ?, ?, ?)
                """,
                write.id(),
                write.allocationId(),
                write.clientMutationId(),
                write.operation(),
                write.previousVersion(),
                write.newVersion(),
                write.previousStateJson(),
                write.newStateJson(),
                write.actorId(),
                write.correlationId(),
                write.deviceId()
        );
    }

    public List<AllocationHistoryRecord> history(String allocationId) {
        return jdbc.query(
                """
                SELECT id, operacao, versao_anterior, versao_nova,
                       estado_anterior_json, estado_novo_json,
                       alterado_por, alterado_em, client_mutation_id,
                       correlacao_id, dispositivo_id
                FROM finance_rateio_historico
                WHERE rateio_id = ?
                ORDER BY alterado_em, id
                """,
                (rs, rowNum) -> new AllocationHistoryRecord(
                        rs.getString("id"),
                        rs.getString("operacao"),
                        nullableLong(rs, "versao_anterior"),
                        rs.getLong("versao_nova"),
                        rs.getString("estado_anterior_json"),
                        rs.getString("estado_novo_json"),
                        rs.getString("alterado_por"),
                        rs.getTimestamp("alterado_em").toLocalDateTime(),
                        rs.getString("client_mutation_id"),
                        rs.getString("correlacao_id"),
                        rs.getString("dispositivo_id")
                ),
                allocationId
        );
    }

    private Optional<AllocationHeader> findHeader(
            String whereClause,
            Object argument
    ) {
        List<AllocationHeader> rows = jdbc.query(
                headerSql() + whereClause,
                (rs, rowNum) -> mapHeader(rs),
                argument
        );
        return rows.stream().findFirst();
    }

    private List<AllocationItemRecord> items(
            String allocationId,
            boolean currentRead
    ) {
        return jdbc.query(
                """
                SELECT id, unidade_controle_id, centro_custo_id,
                       categoria_id, valor_alocado, percentual, ordem
                FROM finance_rateio_item
                WHERE rateio_id = ?
                ORDER BY ordem%s
                """.formatted(currentRead ? " FOR UPDATE" : ""),
                (rs, rowNum) -> new AllocationItemRecord(
                        rs.getString("id"),
                        rs.getString("unidade_controle_id"),
                        rs.getString("centro_custo_id"),
                        rs.getString("categoria_id"),
                        rs.getBigDecimal("valor_alocado"),
                        rs.getBigDecimal("percentual"),
                        rs.getInt("ordem")
                ),
                allocationId
        );
    }

    private String sourceClause(AllocationSourceType type) {
        return switch (type) {
            case COMPRA -> " WHERE r.compra_id = ?";
            case NOTA_FISCAL -> " WHERE r.nota_fiscal_id = ?";
            case LANCAMENTO -> " WHERE r.lancamento_id = ?";
        };
    }

    private String headerSql() {
        return """
                SELECT r.id, r.client_mutation_id,
                       CASE
                         WHEN r.compra_id IS NOT NULL THEN 'COMPRA'
                         WHEN r.nota_fiscal_id IS NOT NULL THEN 'NOTA_FISCAL'
                         ELSE 'LANCAMENTO'
                       END AS origem_tipo,
                       COALESCE(r.compra_id, r.nota_fiscal_id,
                                r.lancamento_id) AS origem_id,
                       r.moeda, r.valor_total, r.status, r.criado_por,
                       r.criado_em, r.atualizado_por, r.atualizado_em,
                       r.versao_linha
                FROM finance_rateio r
                """;
    }

    private AllocationHeader mapHeader(ResultSet rs) throws SQLException {
        return new AllocationHeader(
                rs.getString("id"),
                rs.getString("client_mutation_id"),
                AllocationSourceType.valueOf(rs.getString("origem_tipo")),
                rs.getString("origem_id"),
                rs.getString("moeda"),
                rs.getBigDecimal("valor_total"),
                rs.getString("status"),
                rs.getString("criado_por"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getString("atualizado_por"),
                rs.getTimestamp("atualizado_em").toLocalDateTime(),
                rs.getLong("versao_linha")
        );
    }

    private AllocationSnapshot snapshot(
            AllocationHeader header,
            List<AllocationItemRecord> items
    ) {
        return new AllocationSnapshot(header, List.copyOf(items));
    }

    private boolean exists(String sql, Object argument) {
        List<Integer> rows = jdbc.query(
                sql + " LIMIT 1",
                (rs, rowNum) -> 1,
                argument
        );
        return !rows.isEmpty();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record AllocationSource(
            String id,
            AllocationSourceType type,
            String worksiteId,
            String currency,
            BigDecimal total,
            boolean archived
    ) {
    }

    public record AllocationUnit(
            String id,
            FinancialUnitType type,
            String worksiteId,
            String status
    ) {
    }

    public record AllocationMutation(
            String allocationId,
            AllocationSourceType sourceType,
            String sourceId
    ) {
    }

    public record AllocationHeader(
            String id,
            String clientMutationId,
            AllocationSourceType sourceType,
            String sourceId,
            String currency,
            BigDecimal total,
            String status,
            String createdBy,
            LocalDateTime createdAt,
            String updatedBy,
            LocalDateTime updatedAt,
            long version
    ) {
    }

    public record AllocationItemRecord(
            String id,
            String unitId,
            String costCenterId,
            String categoryId,
            BigDecimal value,
            BigDecimal percentage,
            int order
    ) {
    }

    public record AllocationSnapshot(
            AllocationHeader header,
            List<AllocationItemRecord> items
    ) {
    }

    public record AllocationHeaderWrite(
            String id,
            String clientMutationId,
            AllocationSourceType sourceType,
            String sourceId,
            String currency,
            BigDecimal total,
            String actorId
    ) {
    }

    public record AllocationItemWrite(
            String id,
            String unitId,
            String costCenterId,
            String categoryId,
            BigDecimal value,
            BigDecimal percentage,
            int order
    ) {
    }

    public record AllocationHistoryWrite(
            String id,
            String allocationId,
            String clientMutationId,
            String operation,
            Long previousVersion,
            long newVersion,
            String previousStateJson,
            String newStateJson,
            String actorId,
            String correlationId,
            String deviceId
    ) {
    }

    public record AllocationHistoryRecord(
            String id,
            String operation,
            Long previousVersion,
            long newVersion,
            String previousStateJson,
            String newStateJson,
            String actorId,
            LocalDateTime at,
            String clientMutationId,
            String correlationId,
            String deviceId
    ) {
    }
}
