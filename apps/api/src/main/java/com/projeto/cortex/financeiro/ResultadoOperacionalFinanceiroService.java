package com.projeto.cortex.financeiro;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResultadoOperacionalFinanceiroService {
    private final JdbcTemplate jdbc;
    private final PrevisaoFinanceiraService previsao;

    public ResultadoOperacionalFinanceiroService(
            JdbcTemplate jdbc, PrevisaoFinanceiraService previsao
    ) {
        this.jdbc = jdbc;
        this.previsao = previsao;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResultadoOperacionalFinanceiroResponse buscar(
            String obraId, LocalDate de, LocalDate ate
    ) {
        String obra = FinanceValidation.uuid(obraId, "obraId");
        if (de != null && ate != null && ate.isBefore(de)) {
            throw FinanceValidation.badRequest("Período inválido.");
        }
        Filtro filtro = new Filtro(obra, de, ate);
        Totais totais = jdbc.queryForObject(SQL_TOTAIS, (rs, row) -> new Totais(
                rs.getLong("eligible_count"),
                rs.getLong("evidence_count"),
                rs.getBigDecimal("producao"),
                rs.getBigDecimal("receita"),
                rs.getBigDecimal("medida"),
                rs.getBigDecimal("aprovada"),
                rs.getBigDecimal("faturada"),
                rs.getBigDecimal("recebida")
        ), filtro.args().toArray());
        List<ResultadoOperacionalFinanceiroResponse.Servico> servicos = jdbc.query(
                SQL_SERVICOS, (rs, row) -> {
                    BigDecimal receita = rs.getBigDecimal("receita");
                    return new ResultadoOperacionalFinanceiroResponse.Servico(
                            rs.getString("nome"), rs.getString("unidade"),
                            rs.getBigDecimal("quantidade"), receita,
                            rs.getLong("rdos")
                    );
                }, filtro.args().toArray());
        return new ResultadoOperacionalFinanceiroResponse(
                obra, de, ate, coverage(totais), totais.evidenceCount,
                totais.producao, totais.receita,
                totais.medida, totais.aprovada, totais.faturada, totais.recebida,
                servicos, previsao.buscarAtualSeExistente(obra)
        );
    }

    private String coverage(Totais totals) {
        if (totals.evidenceCount == 0) {
            return "NO_ACCEPTED_EVIDENCE";
        }
        return totals.evidenceCount == totals.eligibleCount
                ? "COMPLETE_ACCEPTED_EXACT"
                : "PARTIAL_ACCEPTED_EXACT";
    }

    private record Totais(long eligibleCount, long evidenceCount,
                          BigDecimal producao, BigDecimal receita,
                          BigDecimal medida, BigDecimal aprovada, BigDecimal faturada,
                          BigDecimal recebida) { }
    private record Filtro(String obraId, LocalDate de, LocalDate ate) {
        List<Object> args() {
            List<Object> args = new ArrayList<>(List.of(obraId));
            args.add(de);
            args.add(de);
            args.add(ate);
            args.add(ate);
            return args;
        }
    }

    private static final String CANONICAL_CTE = """
            WITH eligible AS (
                SELECT execution.*
                FROM execucao_servico_rdo execution
                WHERE execution.obra_id = ?
                  AND %s
                  AND (? IS NULL OR execution.data_execucao >= ?)
                  AND (? IS NULL OR execution.data_execucao <= ?)
            ),
            canonical AS (
                SELECT execution.*
                FROM eligible execution
                %s
                WHERE %s
            )
            """.formatted(
            CanonicalRevenueEvidenceSql.ELIGIBLE_EXECUTION_PREDICATE,
            CanonicalRevenueEvidenceSql.CANONICAL_EVENT_JOIN,
            CanonicalRevenueEvidenceSql.ACCEPTED_EVIDENCE_PREDICATE
    );

    private static final String SQL_TOTAIS = CANONICAL_CTE + """
            SELECT (SELECT COUNT(*) FROM eligible) AS eligible_count,
                   COUNT(*) AS evidence_count,
                   SUM(quantidade_executada) AS producao,
                   SUM(revenue_amount) AS receita,
                   SUM(CASE
                       WHEN estado_receita IN (
                           'RECEITA_MEDIDA',
                           'RECEITA_APROVADA',
                           'RECEITA_FATURADA',
                           'RECEITA_RECEBIDA'
                       ) THEN revenue_amount
                       ELSE 0::numeric(31,2)
                   END) AS medida,
                   SUM(CASE
                       WHEN estado_receita IN (
                           'RECEITA_APROVADA',
                           'RECEITA_FATURADA',
                           'RECEITA_RECEBIDA'
                       ) THEN revenue_amount
                       ELSE 0::numeric(31,2)
                   END) AS aprovada,
                   SUM(CASE
                       WHEN estado_receita IN (
                           'RECEITA_FATURADA',
                           'RECEITA_RECEBIDA'
                       ) THEN revenue_amount
                       ELSE 0::numeric(31,2)
                   END) AS faturada,
                   SUM(CASE
                       WHEN estado_receita = 'RECEITA_RECEBIDA'
                       THEN revenue_amount
                       ELSE 0::numeric(31,2)
                   END) AS recebida
            FROM canonical
            """;

    private static final String SQL_SERVICOS = CANONICAL_CTE + """
            SELECT service.nome AS nome,
                   execution.unidade_medida AS unidade,
                   SUM(execution.quantidade_executada) AS quantidade,
                   SUM(execution.revenue_amount) AS receita,
                   COUNT(DISTINCT execution.rdo_id) AS rdos
            FROM canonical execution
            JOIN catalogo_servico service
              ON service.id = execution.service_id
            GROUP BY execution.service_id,
                     service.nome,
                     execution.unidade_medida
            ORDER BY service.nome, execution.unidade_medida
            """;
}
