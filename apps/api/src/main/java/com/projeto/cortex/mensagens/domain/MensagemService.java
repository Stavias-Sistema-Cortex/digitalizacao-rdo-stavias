package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.AttachmentResponse;
import com.projeto.cortex.mensagens.api.AttachmentReferenceRequest;
import com.projeto.cortex.mensagens.api.MessageCreateRequest;
import com.projeto.cortex.mensagens.api.MessageEditRequest;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import com.projeto.cortex.storage.StoredObjectRepository;
import com.projeto.cortex.storage.StoredObjectService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MensagemService {

    private static final int MAX_BODY_LENGTH = 20_000;
    private static final int MAX_ATTACHMENTS = 10;
    private static final Pattern CLIENT_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final ConversaAccessPolicy accessPolicy;
    private final StoredObjectRepository objectRepository;
    private final StoredObjectService objectService;
    private final MessagingOperationalEventService eventService;

    public MensagemService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            ConversaAccessPolicy accessPolicy,
            StoredObjectRepository objectRepository,
            StoredObjectService objectService,
            MessagingOperationalEventService eventService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.accessPolicy = accessPolicy;
        this.objectRepository = objectRepository;
        this.objectService = objectService;
        this.eventService = eventService;
    }

    @Transactional
    public MessageResponse send(
            String conversationId,
            MessageCreateRequest request,
            MessagingAuditContext audit
    ) {
        ConversationScope scope = accessPolicy.requireAccess(conversationId);
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        if (request == null) {
            throw badRequest("Dados da mensagem são obrigatórios.");
        }
        String messageId = optionalUuid(request.id(), UUID.randomUUID().toString());
        String body = normalizeBody(request.corpo());
        String clientMutationId = requireClientId(request.clientMutationId());
        LocalDateTime clientCreatedAt = request.criadaNoClienteEm() == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : request.criadaNoClienteEm();
        List<AttachmentReferenceRequest> attachmentReferences =
                normalizeAttachments(request.anexos());
        if (body == null && attachmentReferences.isEmpty()) {
            throw badRequest("A mensagem precisa conter texto ou anexo.");
        }

        MessageResponse existing = findByAuthorMutation(
                actorId,
                clientMutationId
        );
        if (existing != null) {
            if (!scope.id().equals(existing.conversaId())
                    || !Objects.equals(body, existing.corpo())
                    || !existing.anexos().stream()
                            .map(AttachmentResponse::objetoId)
                            .toList()
                            .equals(attachmentReferences.stream()
                                    .map(AttachmentReferenceRequest::objetoId)
                                    .toList())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "clientMutationId já foi usado com outro conteúdo."
                );
            }
            return existing;
        }

        List<StoredObjectRecord> objects = attachmentReferences.stream()
                .map(reference -> requireAttachableObject(
                        reference.objetoId(),
                        reference.sha256(),
                        actorId,
                        scope
                ))
                .toList();
        jdbcTemplate.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo, client_mutation_id,
                    criado_cliente_em
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                messageId,
                scope.id(),
                actorId,
                body,
                clientMutationId,
                clientCreatedAt
        );
        eventService.recordSuccess(
                "MENSAGEM",
                messageId,
                "MENSAGEM_CRIADA",
                scope,
                audit,
                Map.of(),
                Map.of(
                        "autorId", actorId,
                        "corpo", body == null ? "" : body,
                        "clientMutationId", clientMutationId,
                        "criadaNoClienteEm", clientCreatedAt.toString(),
                        "anexoIds", attachmentReferences.stream()
                                .map(AttachmentReferenceRequest::objetoId)
                                .toList()
                )
        );
        for (int index = 0; index < objects.size(); index++) {
            addAttachmentMetadata(
                    UUID.randomUUID().toString(),
                    messageId,
                    objects.get(index),
                    index,
                    actorId,
                    scope,
                    audit
            );
        }
        touchConversation(scope.id());
        return get(messageId);
    }

    public List<MessageResponse> history(
            String conversationId,
            LocalDateTime before,
            int requestedLimit
    ) {
        ConversationScope scope = accessPolicy.requireAccess(conversationId);
        int limit = normalizeLimit(requestedLimit);
        if (before == null) {
            return jdbcTemplate.query(
                    """
                    SELECT m.*, c.nome AS autor_nome
                    FROM mensagem m
                    JOIN colaborador c ON c.id = m.autor_id
                    WHERE m.conversa_id = ?
                    ORDER BY m.criado_em DESC, m.id DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> mapMessage(rs),
                    scope.id(),
                    limit
            );
        }
        return jdbcTemplate.query(
                """
                SELECT m.*, c.nome AS autor_nome
                FROM mensagem m
                JOIN colaborador c ON c.id = m.autor_id
                WHERE m.conversa_id = ? AND m.criado_em < ?
                ORDER BY m.criado_em DESC, m.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapMessage(rs),
                scope.id(),
                before,
                limit
        );
    }

    public List<MessageResponse> search(String query, int requestedLimit) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.length() < 2 || normalized.length() > 200) {
            throw badRequest("A busca deve conter entre 2 e 200 caracteres.");
        }
        String userId = currentUserService.requireUserId();
        int limit = normalizeLimit(requestedLimit);
        List<Object> arguments = new ArrayList<>();
        String access;
        if (currentUserService.isAlfa(userId)) {
            access = "";
        } else {
            access = """
                    AND EXISTS (
                        SELECT 1
                        FROM conversa_participante cp
                        WHERE cp.conversa_id = cv.id
                          AND cp.colaborador_id = ?
                          AND cp.status = 'ATIVO'
                          AND cp.removido_em IS NULL
                          AND cp.deletado_em IS NULL
                    )
                    AND (
                        cv.tipo IN ('DIRETA', 'GRUPO')
                        OR (cv.tipo = 'OBRA' AND EXISTS (
                            SELECT 1 FROM vinculo_colaborador_obra v
                            WHERE v.obra_id = cv.obra_id
                              AND v.colaborador_id = ? AND v.status = 'ATIVO'
                        ))
                        OR (cv.tipo = 'EQUIPE' AND EXISTS (
                            SELECT 1
                            FROM equipe e
                            JOIN equipe_membro em ON em.equipe_id = e.id
                            JOIN vinculo_colaborador_obra v
                              ON v.obra_id = e.obra_id
                            WHERE e.id = cv.equipe_id
                              AND e.obra_id = cv.obra_id
                              AND e.status = 'ATIVA'
                              AND e.deletado_em IS NULL
                              AND em.colaborador_id = ?
                              AND em.status = 'ATIVO'
                              AND em.removido_em IS NULL
                              AND em.deletado_em IS NULL
                              AND v.colaborador_id = ?
                              AND v.status = 'ATIVO'
                        ))
                    )
                    """;
            arguments.add(userId);
            arguments.add(userId);
            arguments.add(userId);
            arguments.add(userId);
        }
        arguments.add(escapeLikeLiteral(normalized));
        arguments.add(limit);

        return jdbcTemplate.query(
                """
                SELECT m.*, author.nome AS autor_nome
                FROM mensagem m
                JOIN conversa cv ON cv.id = m.conversa_id
                JOIN colaborador author ON author.id = m.autor_id
                WHERE cv.status = 'ATIVA' AND cv.deletado_em IS NULL
                  AND m.status <> 'EXCLUIDA'
                """ + access + """
                  AND LOWER(COALESCE(m.corpo, ''))
                      LIKE LOWER(CONCAT('%', ?, '%')) ESCAPE '!'
                ORDER BY m.criado_em DESC, m.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapMessage(rs),
                arguments.toArray()
        );
    }

    private static String escapeLikeLiteral(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    @Transactional
    public MessageResponse edit(
            String messageId,
            MessageEditRequest request,
            MessagingAuditContext audit
    ) {
        MessageResponse existing = get(messageId);
        ConversationScope scope = accessPolicy.requireAccess(
                existing.conversaId()
        );
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        requireAuthorOrAlfa(existing, actorId);
        String body = normalizeBody(request == null ? null : request.corpo());
        if (body == null) {
            throw badRequest("O texto editado não pode ficar vazio.");
        }
        if ("EXCLUIDA".equals(existing.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Mensagem excluída não pode ser editada."
            );
        }
        jdbcTemplate.update(
                """
                UPDATE mensagem
                SET corpo = ?, status = 'EDITADA',
                    editado_em = CURRENT_TIMESTAMP(6), editado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                """,
                body,
                actorId,
                existing.id()
        );
        eventService.recordSuccess(
                "MENSAGEM",
                existing.id(),
                "MENSAGEM_EDITADA",
                scope,
                audit,
                Map.of("corpo", existing.corpo(), "status", existing.status()),
                Map.of("corpo", body, "status", "EDITADA")
        );
        touchConversation(scope.id());
        return get(existing.id());
    }

    @Transactional
    public MessageResponse delete(
            String messageId,
            MessagingAuditContext audit
    ) {
        MessageResponse existing = get(messageId);
        ConversationScope scope = accessPolicy.requireAccess(
                existing.conversaId()
        );
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        requireAuthorOrAlfa(existing, actorId);
        if (!"EXCLUIDA".equals(existing.status())) {
            jdbcTemplate.update(
                    """
                    UPDATE mensagem
                    SET corpo = NULL, status = 'EXCLUIDA',
                        deletado_em = CURRENT_TIMESTAMP(6), deletado_por = ?,
                        versao_linha = versao_linha + 1
                    WHERE id = ?
                    """,
                    actorId,
                    existing.id()
            );
            eventService.recordSuccess(
                    "MENSAGEM",
                    existing.id(),
                    "MENSAGEM_EXCLUIDA",
                    scope,
                    audit,
                    Map.of("status", existing.status()),
                    Map.of("status", "EXCLUIDA")
            );
            touchConversation(scope.id());
        }
        return get(existing.id());
    }

    @Transactional
    public AttachmentResponse addAttachment(
            String messageId,
            String requestedAttachmentId,
            String objectId,
            String expectedSha256,
            MessagingAuditContext audit
    ) {
        MessageResponse message = get(messageId);
        ConversationScope scope = accessPolicy.requireAccess(
                message.conversaId()
        );
        String actorId = currentUserService.requireUserId();
        requireActor(audit, actorId);
        requireAuthorOrAlfa(message, actorId);
        StoredObjectRecord object = requireAttachableObject(
                objectId,
                requireSha256(expectedSha256),
                actorId,
                scope
        );
        int order = message.anexos().stream()
                .mapToInt(AttachmentResponse::ordem)
                .max()
                .orElse(-1) + 1;
        String attachmentId = addAttachmentMetadata(
                optionalUuid(
                        requestedAttachmentId,
                        UUID.randomUUID().toString()
                ),
                message.id(),
                object,
                order,
                actorId,
                scope,
                audit
        );
        return attachment(attachmentId);
    }

    public StoredObjectDownload downloadAttachment(String attachmentId) {
        AttachmentLocation location = jdbcTemplate.query(
                """
                SELECT ma.id, ma.stored_object_id, m.conversa_id
                FROM mensagem_anexo ma
                JOIN mensagem m ON m.id = ma.mensagem_id
                WHERE ma.id = ? AND ma.status = 'ATIVO'
                  AND ma.deletado_em IS NULL
                LIMIT 1
                """,
                rs -> rs.next()
                        ? new AttachmentLocation(
                                rs.getString("id"),
                                rs.getString("stored_object_id"),
                                rs.getString("conversa_id")
                        )
                        : null,
                requiredUuid(attachmentId, "anexoId")
        );
        if (location == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Anexo não encontrado."
            );
        }
        accessPolicy.requireAccess(location.conversationId());
        return objectService.downloadAuthorized(
                location.objectId(),
                object -> {
                    if (!location.objectId().equals(object.id())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Objeto do anexo não corresponde ao registro."
                        );
                    }
                }
        );
    }

    public MessageResponse get(String messageId) {
        String id = requiredUuid(messageId, "mensagemId");
        MessageResponse message = jdbcTemplate.query(
                """
                SELECT m.*, c.nome AS autor_nome
                FROM mensagem m
                JOIN colaborador c ON c.id = m.autor_id
                WHERE m.id = ? LIMIT 1
                """,
                rs -> rs.next() ? mapMessage(rs) : null,
                id
        );
        if (message == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Mensagem não encontrada."
            );
        }
        accessPolicy.requireAccess(message.conversaId());
        return message;
    }

    private MessageResponse findByAuthorMutation(
            String authorId,
            String mutationId
    ) {
        MessageResponse message = jdbcTemplate.query(
                """
                SELECT m.*, c.nome AS autor_nome
                FROM mensagem m
                JOIN colaborador c ON c.id = m.autor_id
                WHERE m.autor_id = ? AND m.client_mutation_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? mapMessage(rs) : null,
                authorId,
                mutationId
        );
        if (message != null) {
            accessPolicy.requireAccess(message.conversaId());
        }
        return message;
    }

    private MessageResponse mapMessage(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        return new MessageResponse(
                id,
                rs.getString("conversa_id"),
                rs.getString("autor_id"),
                rs.getString("autor_nome"),
                rs.getString("corpo"),
                rs.getString("status"),
                rs.getString("client_mutation_id"),
                rs.getTimestamp("criado_cliente_em").toLocalDateTime(),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                timestamp(rs, "editado_em"),
                timestamp(rs, "deletado_em"),
                rs.getLong("versao_linha"),
                loadAttachments(id)
        );
    }

    private List<AttachmentResponse> loadAttachments(String messageId) {
        return jdbcTemplate.query(
                """
                SELECT ma.id, ma.stored_object_id, ma.nome_exibicao, ma.ordem,
                       so.media_type_detectado, so.tamanho_bytes, so.sha256
                FROM mensagem_anexo ma
                JOIN stored_object so ON so.id = ma.stored_object_id
                WHERE ma.mensagem_id = ? AND ma.status = 'ATIVO'
                  AND ma.deletado_em IS NULL AND so.status = 'DISPONIVEL'
                ORDER BY ma.ordem, ma.id
                """,
                (rs, rowNum) -> mapAttachment(rs),
                messageId
        );
    }

    private AttachmentResponse attachment(String attachmentId) {
        return jdbcTemplate.query(
                """
                SELECT ma.id, ma.stored_object_id, ma.nome_exibicao, ma.ordem,
                       so.media_type_detectado, so.tamanho_bytes, so.sha256
                FROM mensagem_anexo ma
                JOIN stored_object so ON so.id = ma.stored_object_id
                WHERE ma.id = ? LIMIT 1
                """,
                rs -> rs.next() ? mapAttachment(rs) : null,
                attachmentId
        );
    }

    private AttachmentResponse mapAttachment(ResultSet rs)
            throws SQLException {
        return new AttachmentResponse(
                rs.getString("id"),
                rs.getString("stored_object_id"),
                rs.getString("nome_exibicao"),
                rs.getString("media_type_detectado"),
                rs.getLong("tamanho_bytes"),
                rs.getString("sha256"),
                rs.getInt("ordem")
        );
    }

    private String addAttachmentMetadata(
            String attachmentId,
            String messageId,
            StoredObjectRecord object,
            int order,
            String actorId,
            ConversationScope scope,
            MessagingAuditContext audit
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO mensagem_anexo (
                    id, mensagem_id, stored_object_id, nome_exibicao, ordem,
                    criado_por
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                attachmentId,
                messageId,
                object.id(),
                object.originalName(),
                order,
                actorId
        );
        eventService.recordSuccess(
                "MENSAGEM_ANEXO",
                attachmentId,
                "MENSAGEM_ANEXO_ADICIONADO",
                scope,
                audit,
                Map.of(),
                Map.of(
                        "mensagemId", messageId,
                        "objetoId", object.id(),
                        "sha256", object.sha256(),
                        "tamanhoBytes", object.size()
                )
        );
        return attachmentId;
    }

    private StoredObjectRecord requireAttachableObject(
            String objectId,
            String expectedSha256,
            String actorId,
            ConversationScope scope
    ) {
        StoredObjectRecord object = objectRepository.findAvailableById(
                requiredUuid(objectId, "objetoId")
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Objeto de anexo disponível não encontrado."
        ));
        if (!actorId.equals(object.ownerId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Somente o proprietário do upload pode anexá-lo."
            );
        }
        if (!object.sha256().equals(expectedSha256)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O hash do anexo não corresponde ao objeto validado."
            );
        }
        if (scope.obraId() == null && object.obraId() != null) {
            throw badRequest("O anexo de conversa sem obra não pode ter escopo de obra.");
        }
        if (scope.obraId() != null
                && !scope.obraId().equals(object.obraId())) {
            throw badRequest("O anexo não pertence à obra da conversa.");
        }
        return object;
    }

    private List<AttachmentReferenceRequest> normalizeAttachments(
            List<AttachmentReferenceRequest> attachments
    ) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        Set<String> uniqueIds = new LinkedHashSet<>();
        List<AttachmentReferenceRequest> normalized = new ArrayList<>();
        for (AttachmentReferenceRequest attachment : attachments) {
            if (attachment == null) {
                throw badRequest("Referência de anexo inválida.");
            }
            String id = requiredUuid(attachment.objetoId(), "anexos.objetoId");
            if (uniqueIds.add(id)) {
                normalized.add(new AttachmentReferenceRequest(
                        id,
                        requireSha256(attachment.sha256())
                ));
            }
        }
        if (normalized.size() > MAX_ATTACHMENTS) {
            throw badRequest("Máximo de 10 anexos por mensagem.");
        }
        return List.copyOf(normalized);
    }

    private String requireSha256(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) {
            throw badRequest("sha256 do anexo inválido.");
        }
        return value.toLowerCase();
    }

    private String normalizeBody(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_BODY_LENGTH) {
            throw badRequest("Mensagem excede 20.000 caracteres.");
        }
        return normalized;
    }

    private String requireClientId(String value) {
        if (value == null || !CLIENT_ID.matcher(value).matches()) {
            throw badRequest("clientMutationId inválido.");
        }
        return value;
    }

    private String optionalUuid(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : requiredUuid(value, "id");
    }

    private String requiredUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        try {
            return UUID.fromString(value.strip()).toString();
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " deve ser um UUID válido.");
        }
    }

    private void requireAuthorOrAlfa(
            MessageResponse message,
            String actorId
    ) {
        if (!actorId.equals(message.autorId())
                && !currentUserService.isAlfa(actorId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Somente o autor ou um usuário Alfa pode alterar a mensagem."
            );
        }
    }

    private void requireActor(MessagingAuditContext audit, String actorId) {
        if (audit == null || !actorId.equals(audit.actorId())) {
            throw new IllegalArgumentException(
                    "Contexto de auditoria não corresponde ao usuário autenticado."
            );
        }
    }

    private void touchConversation(String conversationId) {
        jdbcTemplate.update(
                """
                UPDATE conversa
                SET atualizado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE id = ?
                """,
                conversationId
        );
    }

    private LocalDateTime timestamp(ResultSet rs, String column)
            throws SQLException {
        return rs.getTimestamp(column) == null
                ? null
                : rs.getTimestamp(column).toLocalDateTime();
    }

    private int normalizeLimit(int value) {
        return value <= 0 ? 50 : Math.min(value, 100);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record AttachmentLocation(
            String id,
            String objectId,
            String conversationId
    ) {
    }
}
