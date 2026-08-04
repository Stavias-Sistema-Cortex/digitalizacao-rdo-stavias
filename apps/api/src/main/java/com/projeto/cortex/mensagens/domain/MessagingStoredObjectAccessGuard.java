package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.storage.StoredObjectDomainAccessGuard;
import com.projeto.cortex.storage.StoredObjectRecord;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * O anexo de uma conversa pertence a quem está nela, e a mais ninguém.
 *
 * Os mesmos bytes tinham duas portas com regras diferentes. Pela rota de
 * mensagens, a autorização é a participação na conversa — que é a regra certa.
 * Pela rota genérica de objetos, valia a regra padrão: dono, ou qualquer
 * pessoa com acesso à obra quando o objeto trazia {@code obraId}. Como o anexo
 * de uma conversa de obra é gravado justamente com esse {@code obraId},
 * bastava conhecer o id do objeto para ler documento de uma conversa da qual
 * não se participa. E num grupo direto, sem obra, a regra caía em
 * "dono ou administrador", o que abria conversa privada para o papel Alfa.
 *
 * Conversa é o registro mais pessoal do app: nela circulam foto de documento,
 * atestado, endereço. Uma segunda porta mais frouxa que a primeira não é
 * detalhe de implementação, é acesso indevido a dado pessoal — e o guard fecha
 * as duas com a mesma regra, porque a autorização tem de morar no domínio, não
 * na rota por onde alguém chegou.
 */
@Component
public class MessagingStoredObjectAccessGuard
        implements StoredObjectDomainAccessGuard {

    private final JdbcTemplate jdbc;
    private final ConversaAccessPolicy accessPolicy;

    public MessagingStoredObjectAccessGuard(
            JdbcTemplate jdbc,
            ConversaAccessPolicy accessPolicy
    ) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public boolean protects(StoredObjectRecord object) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM mensagem_anexo
                WHERE stored_object_id = ?
                """,
                Integer.class,
                object.id()
        );
        return count != null && count > 0;
    }

    @Override
    public void authorize(StoredObjectRecord object) {
        /*
         * O anexo apagado deixa de ser legível pela rota genérica também: sem
         * isso, remover a mensagem tiraria o arquivo da tela e o manteria ao
         * alcance de quem soubesse o id do objeto.
         */
        String conversationId = jdbc.query(
                """
                SELECT m.conversa_id
                FROM mensagem_anexo ma
                JOIN mensagem m ON m.id = ma.mensagem_id
                WHERE ma.stored_object_id = ?
                  AND ma.status = 'ATIVO'
                  AND ma.deletado_em IS NULL
                ORDER BY ma.criado_em
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                object.id()
        );
        if (conversationId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Anexo não encontrado."
            );
        }
        accessPolicy.requireAccess(conversationId);
    }
}
