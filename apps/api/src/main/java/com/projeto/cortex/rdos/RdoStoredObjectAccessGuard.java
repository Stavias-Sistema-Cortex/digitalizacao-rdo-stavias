package com.projeto.cortex.rdos;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.storage.StoredObjectDomainAccessGuard;
import com.projeto.cortex.storage.StoredObjectRecord;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * A foto de um RDO pertence a quem participa da obra, e a mais ninguém.
 *
 * É a mesma lição do guard de mensagens: os mesmos bytes têm duas portas — a
 * rota do RDO e a rota genérica de objetos — e as duas precisam cobrar a mesma
 * regra, senão a mais frouxa vira a porta de verdade. Aqui a regra do domínio
 * é a participação na obra: quem vê o RDO vê as fotos dele, que é exatamente o
 * que o campo pediu — todos da obra enxergando o que cada um fotografou.
 *
 * O anexo removido sai das duas portas de uma vez: sem a checagem de
 * {@code removido_em}, apagar a foto do RDO a tiraria da tela e a manteria ao
 * alcance de quem soubesse o id do objeto.
 */
@Component
public class RdoStoredObjectAccessGuard
        implements StoredObjectDomainAccessGuard {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    public RdoStoredObjectAccessGuard(
            JdbcTemplate jdbc,
            CurrentUserService currentUserService
    ) {
        this.jdbc = jdbc;
        this.currentUserService = currentUserService;
    }

    @Override
    public boolean protects(StoredObjectRecord object) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM rdo_attachment
                WHERE stored_object_id = ?
                """,
                Integer.class,
                object.id()
        );
        return count != null && count > 0;
    }

    @Override
    public void authorize(StoredObjectRecord object) {
        String obraId = jdbc.query(
                """
                SELECT obra_id
                FROM rdo_attachment
                WHERE stored_object_id = ?
                  AND removido_em IS NULL
                ORDER BY criado_em
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                object.id()
        );
        if (obraId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Anexo não encontrado."
            );
        }
        currentUserService.requireWorksiteAccess(obraId);
    }
}
