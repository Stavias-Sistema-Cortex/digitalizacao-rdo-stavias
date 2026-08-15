package com.projeto.cortex.rdos;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Separa o apontamento operacional da decisão financeira.
 *
 * <p>As rotas normais de RDO registram produção; elas não podem, por meio de
 * um payload, transformá-la em receita aceita ou recusada. A transição é
 * responsabilidade exclusiva do comando financeiro auditado.
 */
final class RdoFinancialExecutionMutationGuard {

    private static final Set<String> TERMINAL_FINANCIAL_STATUSES = Set.of(
            "VALIDADA",
            "REJEITADA",
            "CANCELADA"
    );

    private RdoFinancialExecutionMutationGuard() {
    }

    static void assertCreateCanProceed(RdoCreateRequest request) {
        for (RdoCreateRequest.ServicoExecutadoItem item : services(request)) {
            if (isTerminalFinancialStatus(item.statusValidacao())) {
                financialDecisionRequired();
            }
        }
    }

    /**
     * An RDO with a financial decision is immutable through the generic edit
     * path. This check comes before changing the RDO header or replacing child
     * collections, so a rejected/accepted line cannot be lost halfway through
     * a normal update.
     *
     * <p>Evidência legada também é imutável pelo PUT genérico. Ela precisa
     * passar pela revalidação/rejeição financeira auditada, nunca por um
     * replay que poderia alterar o cabeçalho ou os filhos do RDO.
     */
    static void assertUpdateCanProceed(
            JdbcTemplate jdbcTemplate,
            String rdoId,
            RdoCreateRequest request
    ) {
        Integer evidenceCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM execucao_servico_rdo
                WHERE rdo_id = ?
                  AND revenue_evidence_id IS NOT NULL
                """,
                Integer.class,
                rdoId
        );
        if (evidenceCount != null && evidenceCount > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "RDO_EXECUTION_REVENUE_EVIDENCE_IMMUTABLE"
            );
        }
        Integer decisionCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM rdo_execucao_decisao
                WHERE rdo_id = ?
                """,
                Integer.class,
                rdoId
        );
        if (decisionCount != null && decisionCount > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "RDO_EXECUTION_FINANCIAL_DECISION_IMMUTABLE"
            );
        }
        rejectNewTerminalStatuses(request);
    }

    private static void rejectNewTerminalStatuses(RdoCreateRequest request) {
        for (RdoCreateRequest.ServicoExecutadoItem item : services(request)) {
            String status = normalizeStatus(item.statusValidacao());
            if (!TERMINAL_FINANCIAL_STATUSES.contains(status)) {
                continue;
            }
            financialDecisionRequired();
        }
    }

    private static List<RdoCreateRequest.ServicoExecutadoItem> services(
            RdoCreateRequest request
    ) {
        if (request == null || request.servicosExecutados() == null) {
            return List.of();
        }
        return request.servicosExecutados();
    }

    private static boolean isTerminalFinancialStatus(String rawStatus) {
        return TERMINAL_FINANCIAL_STATUSES.contains(normalizeStatus(rawStatus));
    }

    private static String normalizeStatus(String rawStatus) {
        return rawStatus == null || rawStatus.isBlank()
                ? "REGISTRADA"
                : rawStatus.strip().toUpperCase(Locale.ROOT);
    }

    private static void financialDecisionRequired() {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "RDO_EXECUTION_FINANCIAL_DECISION_REQUIRED"
        );
    }
}
