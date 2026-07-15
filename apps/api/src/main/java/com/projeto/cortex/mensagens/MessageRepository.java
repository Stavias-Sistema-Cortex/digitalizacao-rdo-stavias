package com.projeto.cortex.mensagens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<MensagemResponse> findByClientMessage(
            String senderId,
            String clientMessageId
    ) {
        return findBase(
                """
                WHERE m.remetente_id = ?
                  AND m.client_message_id = ?
                LIMIT 1
                """,
                senderId,
                clientMessageId
        ).stream().findFirst().map(this::enrich);
    }

    Optional<MensagemResponse> findById(String messageId) {
        return findBase(" WHERE m.id = ? LIMIT 1", messageId)
                .stream()
                .findFirst()
                .map(this::enrich);
    }

    MensagemPageResponse list(
            String conversationId,
            MensagemCursor cursor,
            int limit
    ) {
        StringBuilder where = new StringBuilder(" WHERE m.conversa_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(conversationId);
        if (cursor != null) {
            where.append("""
                    AND (
                        m.enviada_cliente_em < ?
                        OR (
                            m.enviada_cliente_em = ?
                            AND m.criada_servidor_em < ?
                        )
                        OR (
                            m.enviada_cliente_em = ?
                            AND m.criada_servidor_em = ?
                            AND m.id < ?
                        )
                    )
                    """);
            params.add(sqlTimestamp(cursor.enviadaClienteEm()));
            params.add(sqlTimestamp(cursor.enviadaClienteEm()));
            params.add(sqlTimestamp(cursor.criadaServidorEm()));
            params.add(sqlTimestamp(cursor.enviadaClienteEm()));
            params.add(sqlTimestamp(cursor.criadaServidorEm()));
            params.add(cursor.id());
        }
        params.add(limit + 1);

        List<MensagemResponse> newestFirst = findBase(
                where + """
                ORDER BY
                    m.enviada_cliente_em DESC,
                    m.criada_servidor_em DESC,
                    m.id DESC
                LIMIT ?
                """,
                params.toArray()
        );
        boolean hasMore = newestFirst.size() > limit;
        if (hasMore) {
            newestFirst = new ArrayList<>(newestFirst.subList(0, limit));
        } else {
            newestFirst = new ArrayList<>(newestFirst);
        }
        List<MensagemResponse> items = newestFirst.stream()
                .map(this::enrich)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(items);

        MensagemCursor nextCursor = hasMore && !items.isEmpty()
                ? cursorOf(items.getFirst())
                : null;
        return new MensagemPageResponse(List.copyOf(items), nextCursor, hasMore);
    }

    void insertMessage(
            MensagemCreateRequest request,
            String senderId,
            Instant serverNow
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, remetente_id, client_message_id, texto,
                    estado, enviada_cliente_em, criada_servidor_em,
                    atualizada_em, versao_linha
                ) VALUES (?, ?, ?, ?, ?, 'ENVIADA', ?, ?, ?, 1)
                """,
                request.id(),
                request.conversaId(),
                senderId,
                request.clientMessageId(),
                request.texto(),
                sqlTimestamp(request.enviadaClienteEm()),
                sqlTimestamp(serverNow),
                sqlTimestamp(serverNow)
        );
        jdbcTemplate.update(
                """
                UPDATE conversa
                SET ultima_atividade_em = GREATEST(ultima_atividade_em, ?),
                    atualizado_em = ?
                WHERE id = ?
                """,
                sqlTimestamp(serverNow),
                sqlTimestamp(serverNow),
                request.conversaId()
        );
    }

    void insertReference(
            String messageId,
            ResolvedMessageReference reference,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO mensagem_referencia (
                    id, mensagem_id, tipo_objeto, objeto_id, obra_id, criada_por
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                reference.id(),
                messageId,
                reference.objectType(),
                reference.objectId(),
                reference.worksiteId(),
                actorId
        );
    }

    void prepareAttachment(
            String messageId,
            MensagemAnexoPreparacaoRequest attachment,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO mensagem_anexo (
                    id, mensagem_id, client_attachment_id, enviado_por,
                    nome_original, nome_seguro, mime_type, tamanho_bytes,
                    hash_sha256, status, versao_linha
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDENTE', 1)
                """,
                attachment.id(),
                messageId,
                attachment.clientAttachmentId(),
                actorId,
                attachment.nomeOriginal(),
                safeFilename(attachment.nomeOriginal()),
                attachment.mimeType(),
                attachment.tamanhoBytes(),
                attachment.hashSha256()
        );
    }

    void initializeReceipts(
            String messageId,
            String conversationId,
            String senderId,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO mensagem_recibo (
                    id, mensagem_id, colaborador_id, entregue_em, lida_em
                )
                SELECT
                    UUID(),
                    ?,
                    cp.colaborador_id,
                    CASE WHEN cp.colaborador_id = ? THEN ? ELSE NULL END,
                    CASE WHEN cp.colaborador_id = ? THEN ? ELSE NULL END
                FROM conversa_participante cp
                WHERE cp.conversa_id = ?
                  AND cp.status = 'ATIVO'
                  AND cp.saiu_em IS NULL
                ON DUPLICATE KEY UPDATE mensagem_id = VALUES(mensagem_id)
                """,
                messageId,
                senderId,
                sqlTimestamp(now),
                senderId,
                sqlTimestamp(now),
                conversationId
        );
    }

    Optional<MensagemReciboResponse> findReceipt(
            String messageId,
            String collaboratorId
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, mensagem_id, colaborador_id, entregue_em, lida_em
                FROM mensagem_recibo
                WHERE mensagem_id = ?
                  AND colaborador_id = ?
                LIMIT 1
                """,
                receiptMapper(),
                messageId,
                collaboratorId
        ).stream().findFirst();
    }

    int markDelivered(
            String messageId,
            String collaboratorId,
            Instant now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE mensagem_recibo
                SET entregue_em = COALESCE(entregue_em, ?),
                    atualizado_em = ?
                WHERE mensagem_id = ?
                  AND colaborador_id = ?
                  AND entregue_em IS NULL
                """,
                sqlTimestamp(now),
                sqlTimestamp(now),
                messageId,
                collaboratorId
        );
    }

    int markRead(String messageId, String collaboratorId, Instant now) {
        return jdbcTemplate.update(
                """
                UPDATE mensagem_recibo
                SET entregue_em = COALESCE(entregue_em, ?),
                    lida_em = COALESCE(lida_em, ?),
                    atualizado_em = ?
                WHERE mensagem_id = ?
                  AND colaborador_id = ?
                  AND lida_em IS NULL
                """,
                sqlTimestamp(now),
                sqlTimestamp(now),
                sqlTimestamp(now),
                messageId,
                collaboratorId
        );
    }

    private List<MensagemResponse> findBase(String suffix, Object... args) {
        return jdbcTemplate.query(
                """
                SELECT
                    m.id,
                    m.conversa_id,
                    m.remetente_id,
                    sender.nome AS remetente_nome,
                    m.client_message_id,
                    m.texto,
                    m.estado,
                    m.enviada_cliente_em,
                    m.criada_servidor_em,
                    m.atualizada_em,
                    m.versao_linha
                FROM mensagem m
                JOIN colaborador sender ON sender.id = m.remetente_id
                """ + suffix,
                messageMapper(),
                args
        );
    }

    private MensagemResponse enrich(MensagemResponse message) {
        return new MensagemResponse(
                message.id(),
                message.conversaId(),
                message.remetenteId(),
                message.remetenteNome(),
                message.clientMessageId(),
                message.texto(),
                message.estado(),
                message.enviadaClienteEm(),
                message.criadaServidorEm(),
                message.atualizadaEm(),
                message.versaoEntidade(),
                findReferences(message.id()),
                findAttachments(message.id()),
                findReceipts(message.id())
        );
    }

    private List<MensagemReferenciaResponse> findReferences(String messageId) {
        return jdbcTemplate.query(
                """
                SELECT id, mensagem_id, tipo_objeto, objeto_id, obra_id, criado_em
                FROM mensagem_referencia
                WHERE mensagem_id = ?
                ORDER BY criado_em, id
                """,
                (rs, rowNum) -> new MensagemReferenciaResponse(
                        rs.getString("id"),
                        rs.getString("mensagem_id"),
                        rs.getString("tipo_objeto"),
                        rs.getString("objeto_id"),
                        rs.getString("obra_id"),
                        instant(rs.getTimestamp("criado_em"))
                ),
                messageId
        );
    }

    private List<MensagemAnexoResponse> findAttachments(String messageId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id, mensagem_id, client_attachment_id, nome_original,
                    nome_seguro, mime_type, tamanho_bytes, hash_sha256, status,
                    ultimo_erro, criado_em, atualizado_em, disponivel_em,
                    versao_linha
                FROM mensagem_anexo
                WHERE mensagem_id = ?
                  AND removido_em IS NULL
                ORDER BY criado_em, id
                """,
                (rs, rowNum) -> new MensagemAnexoResponse(
                        rs.getString("id"),
                        rs.getString("mensagem_id"),
                        rs.getString("client_attachment_id"),
                        rs.getString("nome_original"),
                        rs.getString("nome_seguro"),
                        rs.getString("mime_type"),
                        rs.getLong("tamanho_bytes"),
                        rs.getString("hash_sha256"),
                        rs.getString("status"),
                        rs.getString("ultimo_erro"),
                        instant(rs.getTimestamp("criado_em")),
                        instant(rs.getTimestamp("atualizado_em")),
                        instant(rs.getTimestamp("disponivel_em")),
                        rs.getLong("versao_linha")
                ),
                messageId
        );
    }

    private List<MensagemReciboResponse> findReceipts(String messageId) {
        return jdbcTemplate.query(
                """
                SELECT id, mensagem_id, colaborador_id, entregue_em, lida_em
                FROM mensagem_recibo
                WHERE mensagem_id = ?
                ORDER BY colaborador_id
                """,
                receiptMapper(),
                messageId
        );
    }

    private RowMapper<MensagemResponse> messageMapper() {
        return (rs, rowNum) -> new MensagemResponse(
                rs.getString("id"),
                rs.getString("conversa_id"),
                rs.getString("remetente_id"),
                rs.getString("remetente_nome"),
                rs.getString("client_message_id"),
                rs.getString("texto"),
                rs.getString("estado"),
                instant(rs.getTimestamp("enviada_cliente_em")),
                instant(rs.getTimestamp("criada_servidor_em")),
                instant(rs.getTimestamp("atualizada_em")),
                rs.getLong("versao_linha"),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private RowMapper<MensagemReciboResponse> receiptMapper() {
        return (rs, rowNum) -> new MensagemReciboResponse(
                rs.getString("id"),
                rs.getString("mensagem_id"),
                rs.getString("colaborador_id"),
                instant(rs.getTimestamp("entregue_em")),
                instant(rs.getTimestamp("lida_em"))
        );
    }

    private MensagemCursor cursorOf(MensagemResponse message) {
        return new MensagemCursor(
                message.enviadaClienteEm(),
                message.criadaServidorEm(),
                message.id()
        );
    }

    private String safeFilename(String filename) {
        String base = filename == null ? "anexo" : filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).trim();
        base = base.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        return base.isBlank() ? "anexo" : base.substring(0, Math.min(255, base.length()));
    }

    private Timestamp sqlTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
