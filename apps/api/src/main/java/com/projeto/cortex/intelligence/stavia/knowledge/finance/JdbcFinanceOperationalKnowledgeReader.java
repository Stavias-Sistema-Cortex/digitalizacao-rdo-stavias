package com.projeto.cortex.intelligence.stavia.knowledge.finance;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcFinanceOperationalKnowledgeReader
        implements FinanceOperationalKnowledgeReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcFinanceOperationalKnowledgeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OverdueInvoice> overdueInvoices(
            String worksiteId,
            LocalDate asOf
    ) {
        if (!hasText(worksiteId) || asOf == null) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT
                    nf.id AS invoice_id,
                    nf.fornecedor_id,
                    COALESCE(NULLIF(f.nome_fantasia, ''), f.razao_social)
                        AS supplier_name,
                    nf.numero,
                    nf.serie,
                    nf.moeda,
                    nf.valor_liquido - COALESCE(SUM(l.valor_liquidado), 0)
                        AS open_amount,
                    nf.vencimento_em,
                    GREATEST(
                        nf.atualizado_em,
                        COALESCE(MAX(l.atualizado_em), nf.atualizado_em)
                    ) AS freshness_at
                FROM finance_nota_fiscal nf
                JOIN finance_fornecedor f
                  ON f.id = nf.fornecedor_id
                LEFT JOIN finance_lancamento l
                  ON l.nota_fiscal_id = nf.id
                 AND l.obra_id = nf.obra_id
                 AND l.arquivado_em IS NULL
                WHERE nf.obra_id = ?
                  AND nf.arquivado_em IS NULL
                  AND nf.vencimento_em IS NOT NULL
                  AND nf.vencimento_em < ?
                GROUP BY
                    nf.id,
                    nf.fornecedor_id,
                    f.nome_fantasia,
                    f.razao_social,
                    nf.numero,
                    nf.serie,
                    nf.moeda,
                    nf.valor_liquido,
                    nf.vencimento_em,
                    nf.atualizado_em
                HAVING open_amount > 0
                ORDER BY nf.vencimento_em, nf.numero, nf.id
                """,
                (rs, row) -> new OverdueInvoice(
                        rs.getString("invoice_id"),
                        rs.getString("fornecedor_id"),
                        rs.getString("supplier_name"),
                        rs.getString("numero"),
                        rs.getString("serie"),
                        rs.getString("moeda"),
                        rs.getBigDecimal("open_amount"),
                        rs.getDate("vencimento_em").toLocalDate(),
                        toInstant(rs.getTimestamp("freshness_at"))
                ),
                worksiteId.trim(),
                asOf
        );
    }

    @Override
    public List<PurchaseHistory> purchaseHistory(
            String worksiteId,
            String purchaseReference
    ) {
        if (!hasText(worksiteId)) {
            return List.of();
        }

        String reference = hasText(purchaseReference)
                ? purchaseReference.trim()
                : null;

        return jdbcTemplate.query(
                """
                SELECT
                    c.id AS purchase_id,
                    c.numero_pedido,
                    c.descricao,
                    c.criado_por,
                    creator.nome AS created_by_name,
                    c.criado_em,
                    h.id AS history_id,
                    previous_status.codigo AS previous_status,
                    new_status.codigo AS new_status,
                    h.ator_id,
                    actor.nome AS actor_name,
                    h.origem,
                    h.resultado,
                    h.ocorrido_em,
                    GREATEST(c.atualizado_em, COALESCE(h.ocorrido_em, c.criado_em))
                        AS freshness_at
                FROM finance_compra c
                JOIN colaborador creator
                  ON creator.id = c.criado_por
                LEFT JOIN finance_status_historico h
                  ON h.entidade_tipo = 'COMPRA'
                 AND h.entidade_id = c.id
                 AND h.obra_id = c.obra_id
                LEFT JOIN finance_status_definicao previous_status
                  ON previous_status.id = h.status_anterior_id
                LEFT JOIN finance_status_definicao new_status
                  ON new_status.id = h.status_novo_id
                LEFT JOIN colaborador actor
                  ON actor.id = h.ator_id
                WHERE c.obra_id = ?
                  AND c.arquivado_em IS NULL
                  AND (? IS NULL OR c.id = ? OR c.numero_pedido = ?)
                ORDER BY c.criado_em DESC, h.ocorrido_em, h.id
                """,
                (rs, row) -> new PurchaseHistory(
                        rs.getString("purchase_id"),
                        rs.getString("numero_pedido"),
                        rs.getString("descricao"),
                        rs.getString("criado_por"),
                        rs.getString("created_by_name"),
                        toInstant(rs.getTimestamp("criado_em")),
                        rs.getString("history_id"),
                        rs.getString("previous_status"),
                        rs.getString("new_status"),
                        rs.getString("ator_id"),
                        rs.getString("actor_name"),
                        rs.getString("origem"),
                        rs.getString("resultado"),
                        toInstant(rs.getTimestamp("ocorrido_em")),
                        toInstant(rs.getTimestamp("freshness_at"))
                ),
                worksiteId.trim(),
                reference,
                reference,
                reference
        );
    }

    @Override
    public List<PurchasedTotal> purchasedTotal(
            String worksiteId,
            LocalDate startInclusive,
            LocalDate endExclusive
    ) {
        if (!hasText(worksiteId)
                || startInclusive == null
                || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT
                    c.moeda,
                    SUM(c.valor_contratado) AS purchased_total,
                    COUNT(*) AS purchase_count,
                    MAX(c.atualizado_em) AS freshness_at
                FROM finance_compra c
                WHERE c.obra_id = ?
                  AND c.arquivado_em IS NULL
                  AND COALESCE(c.pedido_emitido_em, DATE(c.criado_em)) >= ?
                  AND COALESCE(c.pedido_emitido_em, DATE(c.criado_em)) < ?
                GROUP BY c.moeda
                ORDER BY c.moeda
                """,
                (rs, row) -> new PurchasedTotal(
                        rs.getString("moeda"),
                        rs.getBigDecimal("purchased_total"),
                        rs.getInt("purchase_count"),
                        toInstant(rs.getTimestamp("freshness_at"))
                ),
                worksiteId.trim(),
                startInclusive,
                endExclusive
        );
    }

    @Override
    public List<PendingSupplierCharge> suppliersWithPendingCharges(
            String worksiteId
    ) {
        if (!hasText(worksiteId)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT
                    ce.fornecedor_id,
                    COALESCE(NULLIF(f.nome_fantasia, ''), f.razao_social)
                        AS supplier_name,
                    COUNT(*) AS pending_count,
                    MIN(ce.ocorrencia_prevista_em) AS first_expected_at,
                    MAX(ce.atualizado_em) AS freshness_at
                FROM finance_cobranca_email ce
                JOIN finance_fornecedor f
                  ON f.id = ce.fornecedor_id
                WHERE ce.obra_id = ?
                  AND ce.status IN (
                      'RASCUNHO', 'PREVISUALIZADA', 'AGENDADA',
                      'NA_FILA', 'ENVIANDO', 'FALHOU'
                  )
                GROUP BY ce.fornecedor_id, f.nome_fantasia, f.razao_social
                ORDER BY first_expected_at, supplier_name, ce.fornecedor_id
                """,
                (rs, row) -> new PendingSupplierCharge(
                        rs.getString("fornecedor_id"),
                        rs.getString("supplier_name"),
                        rs.getLong("pending_count"),
                        toInstant(rs.getTimestamp("first_expected_at")),
                        toInstant(rs.getTimestamp("freshness_at"))
                ),
                worksiteId.trim()
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
