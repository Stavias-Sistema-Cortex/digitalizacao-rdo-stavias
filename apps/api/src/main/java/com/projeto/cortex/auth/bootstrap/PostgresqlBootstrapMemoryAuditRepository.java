package com.projeto.cortex.auth.bootstrap;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** PostgreSQL-native, redacted operational Memory write for initial bootstrap. */
@Component
@Profile("postgresql-bootstrap")
public final class PostgresqlBootstrapMemoryAuditRepository {

    private static final String COMMIT_SEQUENCE_SQL = """
            UPDATE cortex_evento_commit_sequence
            SET ultima_commit_seq = ultima_commit_seq + 1
            WHERE id = 1
            RETURNING ultima_commit_seq
            """;

    private static final String EVENT_SQL = """
            INSERT INTO cortex_evento_operacional (
                id, commit_seq, tipo_entidade, entidade_id, colaborador_id,
                tipo_evento, fonte, origem, sync_status, sincronizado_em,
                correlacao_id, entidades_relacionadas_json,
                estado_anterior_json, estado_novo_json, resultado,
                versao_entidade, schema_version, payload_json
            ) VALUES (
                ?, ?, 'COLABORADOR', ?, ?,
                'BOOTSTRAP_ALFA_INITIALIZED', 'ACADEMY', 'ONLINE', 'SYNCED', CURRENT_TIMESTAMP(6),
                ?, ?::jsonb, ?::jsonb, ?::jsonb, 'SUCESSO',
                1, 1, ?::jsonb
            )
            RETURNING sequencia
            """;

    private static final String ENTITY_STATE_SQL = """
            INSERT INTO cortex_estado_entidade (
                tipo_entidade, entidade_id, versao_entidade, ultimo_evento_seq
            ) VALUES ('COLABORADOR', ?, 1, ?)
            ON CONFLICT (tipo_entidade, entidade_id)
            DO UPDATE SET
                versao_entidade = cortex_estado_entidade.versao_entidade + 1,
                ultimo_evento_seq = EXCLUDED.ultimo_evento_seq
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlBootstrapMemoryAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void recordInitialAlfaBootstrap(String collaboratorId) {
        Long commitSequence = jdbcTemplate.queryForObject(
                COMMIT_SEQUENCE_SQL,
                Long.class
        );
        if (commitSequence == null) {
            throw new IllegalStateException("Auditoria de bootstrap indisponível.");
        }

        String eventId = UUID.randomUUID().toString();
        String receiptId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String relatedEntities = "{}";
        String previousState = "{}";
        String newState = """
                {"role":"ALFA","capability":"ADMINISTRAR_PAPEIS"}
                """;
        String payload = "{\"action\":\"INITIAL_ALFA_BOOTSTRAP\","
                + "\"role\":\"ALFA\","
                + "\"capability\":\"ADMINISTRAR_PAPEIS\","
                + "\"sourceClass\":\"ACADEMY\","
                + "\"receiptId\":\"" + receiptId + "\"}";

        Long eventSequence = jdbcTemplate.queryForObject(
                EVENT_SQL,
                Long.class,
                eventId,
                commitSequence,
                collaboratorId,
                collaboratorId,
                correlationId,
                relatedEntities,
                previousState,
                newState,
                payload
        );
        if (eventSequence == null) {
            throw new IllegalStateException("Auditoria de bootstrap indisponível.");
        }
        jdbcTemplate.update(ENTITY_STATE_SQL, collaboratorId, eventSequence);
    }
}
