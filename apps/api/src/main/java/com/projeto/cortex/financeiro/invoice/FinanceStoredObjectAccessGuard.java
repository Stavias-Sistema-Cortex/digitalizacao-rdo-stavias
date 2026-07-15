package com.projeto.cortex.financeiro.invoice;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.storage.StoredObjectDomainAccessGuard;
import com.projeto.cortex.storage.StoredObjectRecord;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FinanceStoredObjectAccessGuard
        implements StoredObjectDomainAccessGuard {

    private final JdbcTemplate jdbc;
    private final FinancialAccessService access;

    public FinanceStoredObjectAccessGuard(
            JdbcTemplate jdbc,
            FinancialAccessService access
    ) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Override
    public boolean protects(StoredObjectRecord object) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM finance_nota_fiscal_documento
                WHERE stored_object_id = ?
                """,
                Integer.class,
                object.id()
        );
        return count != null && count > 0;
    }

    @Override
    public void authorize(StoredObjectRecord object) {
        String worksiteId = jdbc.query(
                """
                SELECT obra_id
                FROM finance_nota_fiscal_documento
                WHERE stored_object_id = ?
                ORDER BY criado_em
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                object.id()
        );
        if (worksiteId == null || !worksiteId.equals(object.obraId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Documento financeiro fora do escopo autorizado."
            );
        }
        access.requirePermission(
                worksiteId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }
}
