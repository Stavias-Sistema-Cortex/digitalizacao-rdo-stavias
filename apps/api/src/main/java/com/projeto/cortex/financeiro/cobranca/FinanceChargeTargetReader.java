package com.projeto.cortex.financeiro.cobranca;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class FinanceChargeTargetReader {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private final JdbcTemplate jdbcTemplate;

    public FinanceChargeTargetReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TargetData read(String obraId, String targetType, String targetId) {
        String type = FinanceValidation.requiredText(
                targetType, "alvoTipo", 20
        ).toUpperCase(Locale.ROOT);
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        String id = FinanceValidation.uuid(targetId, "alvoId");
        String sql = switch (type) {
            case "COMPRA" -> """
                    SELECT c.obra_id, c.fornecedor_id,
                           c.responsavel_compra_id AS responsavel_id,
                           COALESCE(c.numero_pedido, c.id) AS numero_documento,
                           c.descricao, c.moeda,
                           c.valor_contratado AS valor_total,
                           GREATEST(c.valor_contratado
                               - COALESCE(c.valor_pago, 0), 0) AS valor_aberto,
                           c.entrega_prevista_em AS vencimento_em,
                           o.nome AS obra_nome,
                           f.razao_social AS fornecedor_nome,
                           f.cnpj_normalizado AS fornecedor_cnpj,
                           f.email_financeiro AS destinatario
                    FROM finance_compra c
                    JOIN obra o ON o.id = c.obra_id
                    JOIN finance_fornecedor f ON f.id = c.fornecedor_id
                    WHERE c.id = ? AND c.obra_id = ?
                      AND c.arquivado_em IS NULL
                      AND f.status = 'ATIVO'
                    LIMIT 1
                    """;
            case "NOTA_FISCAL" -> """
                    SELECT n.obra_id, n.fornecedor_id, n.responsavel_id,
                           CONCAT(n.numero,
                               CASE WHEN n.serie = '' THEN ''
                                    ELSE CONCAT('/', n.serie) END)
                               AS numero_documento,
                           COALESCE(NULLIF(n.observacoes, ''),
                               CONCAT('Nota fiscal ', n.numero)) AS descricao,
                           n.moeda, n.valor_liquido AS valor_total,
                           GREATEST(n.valor_liquido
                               - COALESCE(SUM(l.valor_liquidado), 0), 0)
                               AS valor_aberto,
                           n.vencimento_em, o.nome AS obra_nome,
                           f.razao_social AS fornecedor_nome,
                           f.cnpj_normalizado AS fornecedor_cnpj,
                           f.email_financeiro AS destinatario
                    FROM finance_nota_fiscal n
                    JOIN obra o ON o.id = n.obra_id
                    JOIN finance_fornecedor f ON f.id = n.fornecedor_id
                    LEFT JOIN finance_lancamento l
                      ON l.nota_fiscal_id = n.id
                     AND l.arquivado_em IS NULL
                    WHERE n.id = ? AND n.obra_id = ?
                      AND n.arquivado_em IS NULL
                      AND f.status = 'ATIVO'
                    GROUP BY n.id, n.obra_id, n.fornecedor_id,
                             n.responsavel_id, n.numero, n.serie,
                             n.observacoes, n.moeda, n.valor_liquido,
                             n.vencimento_em, o.nome, f.razao_social,
                             f.cnpj_normalizado, f.email_financeiro
                    LIMIT 1
                    """;
            case "LANCAMENTO" -> """
                    SELECT l.obra_id, l.fornecedor_id, l.responsavel_id,
                           COALESCE(l.numero_documento, l.id)
                               AS numero_documento,
                           l.descricao, l.moeda,
                           l.valor_liquido AS valor_total,
                           GREATEST(l.valor_liquido
                               - l.valor_liquidado, 0) AS valor_aberto,
                           l.vencimento_em, o.nome AS obra_nome,
                           f.razao_social AS fornecedor_nome,
                           f.cnpj_normalizado AS fornecedor_cnpj,
                           f.email_financeiro AS destinatario
                    FROM finance_lancamento l
                    JOIN obra o ON o.id = l.obra_id
                    JOIN finance_fornecedor f ON f.id = l.fornecedor_id
                    WHERE l.id = ? AND l.obra_id = ?
                      AND l.arquivado_em IS NULL
                      AND f.status = 'ATIVO'
                    LIMIT 1
                    """;
            default -> throw FinanceValidation.badRequest(
                    "alvoTipo deve ser COMPRA, NOTA_FISCAL ou LANCAMENTO."
            );
        };

        TargetData result = jdbcTemplate.query(
                sql,
                rs -> rs.next() ? map(rs, type, id) : null,
                id,
                worksite
        );
        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Alvo financeiro não encontrado nesta obra."
            );
        }
        if (FinanceValidation.email(result.recipient()) == null) {
            throw FinanceValidation.badRequest(
                    "O fornecedor não possui e-mail financeiro válido."
            );
        }
        return result;
    }

    private TargetData map(ResultSet rs, String type, String targetId)
            throws SQLException {
        LocalDate dueDate = rs.getObject("vencimento_em", LocalDate.class);
        BigDecimal total = rs.getBigDecimal("valor_total");
        BigDecimal open = rs.getBigDecimal("valor_aberto");
        String currency = rs.getString("moeda");
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "obraNome", rs.getString("obra_nome"));
        put(values, "fornecedorNome", rs.getString("fornecedor_nome"));
        put(values, "fornecedorCnpj", rs.getString("fornecedor_cnpj"));
        put(values, "numeroDocumento", rs.getString("numero_documento"));
        put(values, "descricao", rs.getString("descricao"));
        put(values, "moeda", currency);
        put(values, "valorTotal", money(currency, total));
        put(values, "valorAberto", money(currency, open));
        put(values, "vencimento", dueDate == null ? null : DATE.format(dueDate));
        put(values, "tipoAlvo", type);
        return new TargetData(
                type,
                targetId,
                rs.getString("obra_id"),
                rs.getString("fornecedor_id"),
                rs.getString("responsavel_id"),
                FinanceValidation.email(rs.getString("destinatario")),
                dueDate,
                Map.copyOf(values)
        );
    }

    private String money(String currency, BigDecimal value) {
        if (currency == null || value == null) {
            return null;
        }
        return currency + " "
                + value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    public record TargetData(
            String targetType,
            String targetId,
            String obraId,
            String supplierId,
            String responsibleId,
            String recipient,
            LocalDate dueDate,
            Map<String, String> renderValues
    ) {
    }
}
