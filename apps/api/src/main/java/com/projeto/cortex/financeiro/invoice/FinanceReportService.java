package com.projeto.cortex.financeiro.invoice;

import static com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos.*;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FinanceReportService {

    private static final Set<String> LEDGER_TYPES = Set.of("PAGAR", "RECEBER");
    private static final String REPORT_FROM = """
            FROM finance_lancamento l
            LEFT JOIN finance_fornecedor f ON f.id = l.fornecedor_id
            LEFT JOIN finance_categoria c ON c.id = l.categoria_id
            LEFT JOIN finance_centro_custo cc ON cc.id = l.centro_custo_id
            JOIN colaborador r ON r.id = l.responsavel_id
            """;

    private final JdbcTemplate jdbc;

    public FinanceReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OverviewResponse overview(ReportFilter filter) {
        ReportFilter value = filter.normalized();
        SqlFilter scoped = scoped(value);
        OverviewTotals totals = jdbc.queryForObject(
                """
                SELECT
                    COALESCE(SUM(l.valor_liquido), 0) AS comprometido,
                    COALESCE(SUM(l.valor_liquidado), 0) AS liquidado,
                    COALESCE(SUM(l.valor_liquido - l.valor_liquidado), 0)
                        AS aberto,
                    COALESCE(SUM(CASE
                        WHEN l.vencimento_em < ?
                         AND l.valor_liquidado < l.valor_liquido
                        THEN l.valor_liquido - l.valor_liquidado ELSE 0 END), 0)
                        AS vencido,
                    COALESCE(SUM(CASE WHEN l.tipo = 'PAGAR'
                        THEN l.valor_liquido - l.valor_liquidado ELSE 0 END), 0)
                        AS a_pagar,
                    COALESCE(SUM(CASE WHEN l.tipo = 'RECEBER'
                        THEN l.valor_liquido - l.valor_liquidado ELSE 0 END), 0)
                        AS a_receber,
                    COUNT(*) AS quantidade,
                    SUM(CASE
                        WHEN l.vencimento_em < ?
                         AND l.valor_liquidado < l.valor_liquido
                        THEN 1 ELSE 0 END) AS quantidade_vencidos
                """ + REPORT_FROM + scoped.where(),
                (rs, row) -> new OverviewTotals(
                        rs.getBigDecimal("comprometido"),
                        rs.getBigDecimal("liquidado"),
                        rs.getBigDecimal("aberto"),
                        rs.getBigDecimal("vencido"),
                        rs.getBigDecimal("a_pagar"),
                        rs.getBigDecimal("a_receber"),
                        rs.getLong("quantidade"),
                        rs.getLong("quantidade_vencidos")
                ),
                withPrefix(
                        scoped.args(), value.referenceDate(),
                        value.referenceDate()
                ).toArray()
        );
        BudgetTotal budget = linkedBudget(value, scoped);
        return new OverviewResponse(
                value.worksiteId(), value.referenceDate(), value.currency(),
                budget.count() == 0 ? null : budget.amount(),
                totals.committed(), totals.settled(), totals.open(),
                totals.overdue(), totals.payable(), totals.receivable(),
                totals.count(), totals.overdueCount(), totals.count() > 0,
                budget.count() > 0
        );
    }

    public List<ReportRow> report(String groupBy, ReportFilter filter) {
        ReportFilter value = filter.normalized();
        Dimension dimension = Dimension.from(groupBy);
        SqlFilter scoped = scoped(value);
        String sql = """
                SELECT %s AS grupo_id, %s AS grupo_nome, l.moeda,
                       SUM(l.valor_liquido) AS valor_liquido,
                       SUM(l.valor_liquidado) AS valor_liquidado,
                       SUM(l.valor_liquido - l.valor_liquidado) AS valor_aberto,
                       COUNT(*) AS quantidade
                """.formatted(dimension.idExpression, dimension.nameExpression)
                + REPORT_FROM + scoped.where()
                + " GROUP BY " + dimension.idExpression + ", "
                + dimension.nameExpression + ", l.moeda"
                + " ORDER BY grupo_nome, grupo_id";
        return jdbc.query(
                sql,
                (rs, row) -> new ReportRow(
                        rs.getString("grupo_id"),
                        rs.getString("grupo_nome"),
                        rs.getString("moeda"),
                        rs.getBigDecimal("valor_liquido"),
                        rs.getBigDecimal("valor_liquidado"),
                        rs.getBigDecimal("valor_aberto"),
                        rs.getLong("quantidade")
                ),
                scoped.args().toArray()
        );
    }

    public byte[] exportCsv(String groupBy, ReportFilter filter) {
        List<ReportRow> rows = report(groupBy, filter);
        StringBuilder csv = new StringBuilder(
                "grupo_id,grupo_nome,moeda,valor_liquido,valor_liquidado,valor_aberto,quantidade\r\n"
        );
        for (ReportRow row : rows) {
            csv.append(csv(row.grupoId())).append(',')
                    .append(csv(row.grupoNome())).append(',')
                    .append(csv(row.moeda())).append(',')
                    .append(row.valorLiquido().toPlainString()).append(',')
                    .append(row.valorLiquidado().toPlainString()).append(',')
                    .append(row.valorAberto().toPlainString()).append(',')
                    .append(row.quantidade()).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private BudgetTotal linkedBudget(
            ReportFilter filter,
            SqlFilter scoped
    ) {
        String sql = """
                SELECT COALESCE(SUM(b.valor_total), 0) AS valor_total,
                       COUNT(*) AS quantidade
                FROM (
                    SELECT DISTINCT i.id, i.valor_total
                """ + REPORT_FROM + """
                    JOIN finance_lancamento_orcamento_vinculo v
                      ON v.lancamento_id = l.id
                     AND v.obra_id = l.obra_id
                     AND v.arquivado_em IS NULL
                    JOIN item_contratual i
                      ON i.id = v.item_contratual_id
                     AND i.obra_id = l.obra_id
                """ + scoped.where() + ") b";
        return jdbc.queryForObject(
                sql,
                (rs, row) -> new BudgetTotal(
                        rs.getBigDecimal("valor_total"),
                        rs.getLong("quantidade")
                ),
                scoped.args().toArray()
        );
    }

    private SqlFilter scoped(ReportFilter filter) {
        StringBuilder where = new StringBuilder(
                " WHERE l.obra_id = ? AND l.moeda = ?"
                        + " AND l.arquivado_em IS NULL"
        );
        List<Object> args = new ArrayList<>();
        args.add(filter.worksiteId());
        args.add(filter.currency());
        if (filter.from() != null) {
            where.append(" AND l.vencimento_em >= ?");
            args.add(filter.from());
        }
        if (filter.to() != null) {
            where.append(" AND l.vencimento_em <= ?");
            args.add(filter.to());
        }
        appendEquals(where, args, "l.tipo", filter.type());
        appendEquals(where, args, "l.status_id", filter.statusId());
        appendEquals(where, args, "l.fornecedor_id", filter.supplierId());
        appendEquals(where, args, "l.responsavel_id", filter.responsibleId());
        appendEquals(where, args, "l.centro_custo_id", filter.costCenterId());
        appendEquals(where, args, "l.categoria_id", filter.categoryId());
        if (filter.query() != null) {
            where.append("""
                     AND (LOWER(l.descricao) LIKE ?
                       OR LOWER(COALESCE(l.numero_documento, '')) LIKE ?
                       OR LOWER(COALESCE(f.razao_social, '')) LIKE ?)
                    """);
            String like = "%" + filter.query().toLowerCase(Locale.ROOT) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return new SqlFilter(where.toString(), List.copyOf(args));
    }

    private void appendEquals(
            StringBuilder sql,
            List<Object> args,
            String column,
            Object value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private List<Object> withPrefix(
            List<Object> values,
            Object first,
            Object second
    ) {
        List<Object> result = new ArrayList<>(values.size() + 2);
        result.add(first);
        result.add(second);
        result.addAll(values);
        return result;
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public record ReportFilter(
            String worksiteId,
            String currency,
            LocalDate from,
            LocalDate to,
            String type,
            String statusId,
            String supplierId,
            String responsibleId,
            String costCenterId,
            String categoryId,
            String query,
            LocalDate referenceDate
    ) {
        ReportFilter normalized() {
            if (from != null && to != null && to.isBefore(from)) {
                throw FinanceValidation.badRequest("Período inválido.");
            }
            return new ReportFilter(
                    FinanceValidation.uuid(worksiteId, "obraId"),
                    FinanceValidation.currency(currency), from, to,
                    type == null || type.isBlank() ? null
                            : FinanceInvoiceValidation.enumValue(
                                    type, "tipo", LEDGER_TYPES
                            ),
                    FinanceValidation.optionalUuid(statusId, "statusId"),
                    FinanceValidation.optionalUuid(supplierId, "fornecedorId"),
                    FinanceValidation.optionalUuid(responsibleId, "responsavelId"),
                    FinanceValidation.optionalUuid(costCenterId, "centroCustoId"),
                    FinanceValidation.optionalUuid(categoryId, "categoriaId"),
                    FinanceValidation.optionalText(query, "query", 200),
                    referenceDate == null ? LocalDate.now() : referenceDate
            );
        }
    }

    private enum Dimension {
        FORNECEDOR(
                "l.fornecedor_id",
                "COALESCE(f.razao_social, 'Não informado')"
        ),
        CATEGORIA(
                "l.categoria_id",
                "COALESCE(c.nome, 'Não informado')"
        ),
        RESPONSAVEL("l.responsavel_id", "r.nome"),
        CENTRO_CUSTO(
                "l.centro_custo_id",
                "COALESCE(cc.nome, 'Não informado')"
        );

        private final String idExpression;
        private final String nameExpression;

        Dimension(String idExpression, String nameExpression) {
            this.idExpression = idExpression;
            this.nameExpression = nameExpression;
        }

        private static Dimension from(String value) {
            String normalized = FinanceValidation.requiredText(
                    value, "agruparPor", 30
            ).toUpperCase(Locale.ROOT);
            try {
                return Dimension.valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw FinanceValidation.badRequest("Agrupamento inválido.");
            }
        }
    }

    private record SqlFilter(String where, List<Object> args) {
    }

    private record BudgetTotal(BigDecimal amount, long count) {
    }

    private record OverviewTotals(
            BigDecimal committed,
            BigDecimal settled,
            BigDecimal open,
            BigDecimal overdue,
            BigDecimal payable,
            BigDecimal receivable,
            long count,
            long overdueCount
    ) {
    }
}
