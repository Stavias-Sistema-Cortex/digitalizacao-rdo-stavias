package com.projeto.cortex.rdos;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import com.projeto.cortex.storage.StoredObjectRepository;
import com.projeto.cortex.storage.StoredObjectService;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * O binário da foto do RDO: amarrar o que subiu e servir a quem tem acesso.
 *
 * <p>Separado de {@link RdoAttachmentService} de propósito: aquele serviço
 * cuida da ficha do anexo dentro do sync e não conhece armazenamento de
 * objeto; este cuida só do binário, com as dependências que isso pede. A
 * ficha continua chegando primeiro, pelo sync; o binário sobe pela rota
 * genérica de objetos, e aqui os dois se encontram.
 */
@Service
public class RdoAttachmentObjectService {

    private final JdbcTemplate jdbcTemplate;
    private final StoredObjectRepository objectRepository;
    private final StoredObjectService storedObjectService;
    private final CurrentUserService currentUserService;

    public RdoAttachmentObjectService(
            JdbcTemplate jdbcTemplate,
            StoredObjectRepository objectRepository,
            StoredObjectService storedObjectService,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectRepository = objectRepository;
        this.storedObjectService = storedObjectService;
        this.currentUserService = currentUserService;
    }

    /**
     * Amarra o binário já enviado ao anexo que o descrevia.
     *
     * <p>O anexo do RDO sempre subiu como ficha — nome, tamanhos, um
     * storage_ref sintético apontando para o IndexedDB de origem — e a foto
     * em si ficava no aparelho de quem fotografou. O binário agora sobe pela
     * rota genérica de objetos e este método faz o vínculo, com as mesmas
     * réguas do anexo de mensagem: objeto disponível, dono igual a quem
     * amarra, hash conferido e obra do objeto igual à obra do RDO. Sem as
     * réguas, qualquer pessoa com acesso ao RDO poderia pendurar nele um
     * objeto alheio.
     *
     * <p>Idempotente de propósito: o aplicativo reenvia o vínculo quando a
     * resposta se perde, e amarrar de novo o mesmo objeto não é erro. Trocar
     * de objeto é — indica dois uploads disputando o mesmo anexo.
     */
    @Transactional
    public void vincularObjeto(
            String rdoId,
            String attachmentId,
            String objetoId,
            String sha256
    ) {
        String normalizedRdoId = requireReference(rdoId, "rdoId");
        String normalizedAttachmentId =
                requireReference(attachmentId, "attachmentId");
        if (isBlank(objetoId)) {
            throw badRequest("objetoId é obrigatório.");
        }
        if (isBlank(sha256)) {
            throw badRequest("sha256 é obrigatório.");
        }

        Map<String, String> anexo = jdbcTemplate.query(
                """
                SELECT obra_id, stored_object_id
                FROM rdo_attachment
                WHERE id = ? AND rdo_id = ? AND removido_em IS NULL
                """,
                rs -> rs.next()
                        ? Map.of(
                                "obraId", rs.getString("obra_id"),
                                "vinculado", String.valueOf(
                                        rs.getString("stored_object_id")
                                )
                        )
                        : null,
                normalizedAttachmentId,
                normalizedRdoId
        );
        if (anexo == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Anexo não encontrado neste RDO."
            );
        }
        String vinculado = anexo.get("vinculado");
        if (objetoId.trim().equals(vinculado)) {
            return;
        }
        if (!"null".equals(vinculado)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O anexo já está amarrado a outro objeto."
            );
        }

        StoredObjectRecord object = objectRepository
                .findAvailableById(objetoId.trim())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Objeto de anexo disponível não encontrado."
                ));
        String actorId = currentUserService.requireUserId();
        if (!actorId.equals(object.ownerId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Somente o proprietário do upload pode anexá-lo."
            );
        }
        if (!object.sha256().equals(sha256.trim().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O hash do anexo não corresponde ao objeto validado."
            );
        }
        String obraDoAnexo = anexo.get("obraId");
        if (object.obraId() == null || !object.obraId().equals(obraDoAnexo)) {
            throw badRequest("O objeto não pertence à obra do RDO.");
        }

        jdbcTemplate.update(
                """
                UPDATE rdo_attachment
                SET stored_object_id = ?,
                    sync_status = 'SYNCED',
                    atualizado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND rdo_id = ? AND removido_em IS NULL
                """,
                object.id(),
                normalizedAttachmentId,
                normalizedRdoId
        );
        /*
         * O carimbo do RDO avança junto — sem tocar em versao_linha, porque
         * amarrar uma foto não é editar o documento e não pode fabricar
         * conflito. O carimbo é o que os outros aparelhos comparam para
         * decidir rebuscar o conteúdo: sem ele, quem puxou o RDO antes de a
         * foto subir nunca rebuscaria, e a ficha semeada ficaria sem vínculo
         * — a foto existiria no servidor e não chegaria a ninguém.
         */
        jdbcTemplate.update(
                """
                UPDATE rdo
                SET atualizado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """,
                normalizedRdoId
        );
    }

    /**
     * O conteúdo da foto para quem tem acesso ao RDO.
     *
     * <p>A autorização de obra é de quem chama (o controller exige acesso ao
     * RDO antes); aqui resolve-se apenas o vínculo — e um anexo sem binário
     * no servidor é 404 com frase própria, porque "ainda não subiu" e "não
     * existe" pedem reações diferentes de quem lê.
     */
    public StoredObjectDownload conteudoDoAnexo(
            String rdoId,
            String attachmentId
    ) {
        String objetoId = jdbcTemplate.query(
                """
                SELECT stored_object_id
                FROM rdo_attachment
                WHERE id = ? AND rdo_id = ? AND removido_em IS NULL
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                requireReference(attachmentId, "attachmentId"),
                requireReference(rdoId, "rdoId")
        );
        if (objetoId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "A foto deste anexo ainda não chegou ao servidor."
            );
        }
        return storedObjectService.downloadAuthorized(objetoId, object -> {
        });
    }

    private static String requireReference(String value, String field) {
        if (isBlank(value)) {
            throw badRequest(field + " é obrigatório.");
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
