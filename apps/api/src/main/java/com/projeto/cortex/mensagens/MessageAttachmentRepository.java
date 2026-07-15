package com.projeto.cortex.mensagens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
class MessageAttachmentRepository {

    private final JdbcTemplate jdbcTemplate;

    MessageAttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<MessageAttachmentRecord> findById(String attachmentId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id, mensagem_id, enviado_por, client_attachment_id,
                    nome_original, nome_seguro, mime_type, tamanho_bytes,
                    hash_sha256, storage_key, status, ultimo_erro,
                    criado_em, atualizado_em, disponivel_em, versao_linha
                FROM mensagem_anexo
                WHERE id = ?
                  AND removido_em IS NULL
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new MessageAttachmentRecord(
                        resultSet.getString("id"),
                        resultSet.getString("mensagem_id"),
                        resultSet.getString("enviado_por"),
                        resultSet.getString("client_attachment_id"),
                        resultSet.getString("nome_original"),
                        resultSet.getString("nome_seguro"),
                        resultSet.getString("mime_type"),
                        resultSet.getLong("tamanho_bytes"),
                        resultSet.getString("hash_sha256"),
                        resultSet.getString("storage_key"),
                        resultSet.getString("status"),
                        resultSet.getString("ultimo_erro"),
                        instant(resultSet.getTimestamp("criado_em")),
                        instant(resultSet.getTimestamp("atualizado_em")),
                        instant(resultSet.getTimestamp("disponivel_em")),
                        resultSet.getLong("versao_linha")
                ),
                attachmentId
        ).stream().findFirst();
    }

    int markAvailable(
            String attachmentId,
            long expectedVersion,
            String storageKey,
            String safeFilename,
            String mimeType,
            Instant now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE mensagem_anexo
                SET storage_key = ?,
                    nome_seguro = ?,
                    mime_type = ?,
                    status = 'DISPONIVEL',
                    ultimo_erro = NULL,
                    disponivel_em = ?,
                    atualizado_em = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status IN ('PENDENTE', 'FALHOU')
                  AND removido_em IS NULL
                """,
                storageKey,
                safeFilename,
                mimeType,
                sqlTimestamp(now),
                sqlTimestamp(now),
                attachmentId,
                expectedVersion
        );
    }

    int markFailedIfVersion(
            String attachmentId,
            long expectedVersion,
            String error,
            Instant now
    ) {
        String safeError = error == null
                ? "Falha ao processar o anexo."
                : error.substring(0, Math.min(1000, error.length()));
        return jdbcTemplate.update(
                """
                UPDATE mensagem_anexo
                SET storage_key = NULL,
                    status = 'FALHOU',
                    ultimo_erro = ?,
                    disponivel_em = NULL,
                    atualizado_em = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status IN ('PENDENTE', 'FALHOU')
                  AND removido_em IS NULL
                """,
                safeError,
                sqlTimestamp(now),
                attachmentId,
                expectedVersion
        );
    }

    int markMissingIfVersion(
            String attachmentId,
            long expectedVersion,
            Instant now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE mensagem_anexo
                SET storage_key = NULL,
                    status = 'FALHOU',
                    ultimo_erro = 'Conteúdo armazenado não localizado.',
                    disponivel_em = NULL,
                    atualizado_em = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status = 'DISPONIVEL'
                  AND removido_em IS NULL
                """,
                sqlTimestamp(now),
                attachmentId,
                expectedVersion
        );
    }

    private Timestamp sqlTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
