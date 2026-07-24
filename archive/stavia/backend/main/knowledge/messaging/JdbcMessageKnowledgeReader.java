package com.projeto.cortex.intelligence.stavia.knowledge.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;

@Component
public class JdbcMessageKnowledgeReader implements MessageKnowledgeReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMessageKnowledgeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<MessageKnowledgeRecord> findAuthorizedByWorksite(
            String worksiteId,
            String userId
    ) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT
                    mensagem.id,
                    conversa.id AS conversa_id,
                    conversa.tipo AS conversa_tipo,
                    conversa.titulo AS conversa_titulo,
                    COALESCE(conversa.obra_id, equipe_obra.obra_id, referencia_obra.obra_id)
                        AS obra_id,
                    mensagem.remetente_id,
                    remetente.nome AS remetente_nome,
                    LEFT(mensagem.texto, 1000) AS texto,
                    mensagem.enviada_cliente_em,
                    mensagem.criada_servidor_em,
                    (
                        SELECT JSON_ARRAYAGG(JSON_OBJECT(
                            'tipoObjeto', referencia.tipo_objeto,
                            'objetoId', referencia.objeto_id,
                            'obraId', referencia.obra_id
                        ))
                        FROM mensagem_referencia referencia
                        WHERE referencia.mensagem_id = mensagem.id
                    ) AS referencias_json,
                    (
                        SELECT JSON_ARRAYAGG(JSON_OBJECT(
                            'anexoId', anexo.id,
                            'nome', anexo.nome_original,
                            'mimeType', anexo.mime_type,
                            'status', anexo.status
                        ))
                        FROM mensagem_anexo anexo
                        WHERE anexo.mensagem_id = mensagem.id
                          AND anexo.removido_em IS NULL
                    ) AS anexos_json
                FROM mensagem
                JOIN conversa ON conversa.id = mensagem.conversa_id
                JOIN conversa_participante participacao
                  ON participacao.conversa_id = conversa.id
                 AND participacao.colaborador_id = ?
                 AND participacao.status = 'ATIVO'
                 AND participacao.saiu_em IS NULL
                JOIN colaborador remetente ON remetente.id = mensagem.remetente_id
                LEFT JOIN equipe_obra
                  ON equipe_obra.equipe_id = conversa.equipe_id
                 AND equipe_obra.status = 'ATIVO'
                 AND equipe_obra.fim_em IS NULL
                LEFT JOIN mensagem_referencia referencia_obra
                  ON referencia_obra.mensagem_id = mensagem.id
                 AND referencia_obra.obra_id = ?
                WHERE mensagem.removida_em IS NULL
                  AND mensagem.estado <> 'REMOVIDA'
                  AND (
                      conversa.obra_id = ?
                      OR equipe_obra.obra_id = ?
                      OR referencia_obra.obra_id = ?
                  )
                ORDER BY mensagem.enviada_cliente_em DESC,
                         mensagem.criada_servidor_em DESC,
                         mensagem.id DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new MessageKnowledgeRecord(
                        rs.getString("id"),
                        rs.getString("conversa_id"),
                        rs.getString("conversa_tipo"),
                        rs.getString("conversa_titulo"),
                        rs.getString("obra_id"),
                        rs.getString("remetente_id"),
                        rs.getString("remetente_nome"),
                        rs.getString("texto"),
                        toLocalDateTime(rs.getTimestamp("enviada_cliente_em")),
                        rs.getString("referencias_json"),
                        rs.getString("anexos_json")
                ),
                userId,
                worksiteId,
                worksiteId,
                worksiteId,
                worksiteId
        );
    }
}
