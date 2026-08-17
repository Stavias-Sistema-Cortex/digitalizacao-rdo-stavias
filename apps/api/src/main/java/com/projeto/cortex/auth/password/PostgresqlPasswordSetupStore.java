package com.projeto.cortex.auth.password;

import com.projeto.cortex.auth.identity.PostgresqlAuthIdentityMutationLock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgresql-common")
public class PostgresqlPasswordSetupStore
        implements PasswordSetupStore {

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlPasswordSetupStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean canManageAccess(String actorId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM auth_capacidade_administrativa capability
                INNER JOIN colaborador actor
                    ON actor.id = capability.colaborador_id
                INNER JOIN auth_identity identity
                    ON identity.colaborador_id = actor.id
                WHERE capability.colaborador_id = ?
                  AND capability.capacidade = 'ADMINISTRAR_PAPEIS'
                  AND capability.ativa = TRUE
                  AND actor.ativo = TRUE
                  AND actor.deletado_em IS NULL
                  AND actor.papel_acesso = 'ALFA'
                  AND identity.status = 'ATIVA'
                """, Integer.class, actorId);
        return count != null && count == 1;
    }

    @Override
    public Optional<PasswordSetupTarget> findActiveAcademyTarget(
            String collaboratorId
    ) {
        List<PasswordSetupTarget> rows = jdbcTemplate.query("""
                SELECT colaborador.id, colaborador.nome
                FROM colaborador
                INNER JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.id = ?
                  AND colaborador.banco_origem = ('dbsta' || 'vias_acad')
                  AND colaborador.tabela_origem = 'usuarios'
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identity.status IN ('PENDENTE', 'ATIVA')
                """, (resultSet, rowNumber) -> new PasswordSetupTarget(
                resultSet.getString("id"),
                resultSet.getString("nome")
        ), collaboratorId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Colaborador Academy ambíguo.");
        }
        return rows.stream().findFirst();
    }

    @Override
    public int invalidatePending(String collaboratorId, String reason) {
        return jdbcTemplate.update("""
                UPDATE auth_password_setup_challenge
                SET status = 'BLOQUEADO', invalidado_motivo = ?
                WHERE colaborador_id = ? AND status = 'PENDENTE'
                """, requireReason(reason), collaboratorId);
    }

    @Override
    public Instant create(
            String challengeId,
            String collaboratorId,
            String codeDigest,
            PasswordSetupPurpose purpose,
            String actorId,
            int ttlSeconds,
            int maxAttempts
    ) {
        if (codeDigest == null || !codeDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Digest de código inválido.");
        }
        if (ttlSeconds < 1 || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Política de código inválida.");
        }
        Timestamp expiresAt = jdbcTemplate.queryForObject("""
                INSERT INTO auth_password_setup_challenge (
                    id, colaborador_id, codigo_digest, finalidade,
                    expira_em, tentativas, max_tentativas, status,
                    criado_por_colaborador_id
                ) VALUES (?, ?, ?, ?,
                    clock_timestamp() + (? * INTERVAL '1 second'),
                    0, ?, 'PENDENTE', ?)
                RETURNING expira_em
                """, Timestamp.class, challengeId, collaboratorId, codeDigest,
                purpose.name(), ttlSeconds, maxAttempts, actorId);
        if (expiresAt == null) {
            throw new IllegalStateException("Código temporário não persistido.");
        }
        return expiresAt.toInstant();
    }

    @Override
    public Optional<LockedPasswordSetupChallenge> lockLatestPending(
            String collaboratorId
    ) {
        List<LockedPasswordSetupChallenge> rows = jdbcTemplate.query("""
                SELECT id, colaborador_id, codigo_digest, expira_em,
                    tentativas, max_tentativas, status,
                    clock_timestamp() AS agora
                FROM auth_password_setup_challenge
                WHERE colaborador_id = ? AND status = 'PENDENTE'
                ORDER BY criado_em DESC
                LIMIT 1
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LockedPasswordSetupChallenge(
                resultSet.getString("id"),
                resultSet.getString("colaborador_id"),
                resultSet.getString("codigo_digest"),
                resultSet.getTimestamp("expira_em").toInstant(),
                resultSet.getInt("tentativas"),
                resultSet.getInt("max_tentativas"),
                resultSet.getString("status"),
                resultSet.getTimestamp("agora").toInstant()
        ), collaboratorId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Código temporário ambíguo.");
        }
        return rows.stream().findFirst();
    }

    @Override
    public int markExpired(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_password_setup_challenge
                SET status = 'EXPIRADO'
                WHERE id = ? AND status = 'PENDENTE'
                  AND expira_em <= clock_timestamp()
                """, challengeId);
    }

    @Override
    public int block(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_password_setup_challenge
                SET status = 'BLOQUEADO'
                WHERE id = ? AND status = 'PENDENTE'
                """, challengeId);
    }

    @Override
    public int recordFailedAttempt(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_password_setup_challenge
                SET tentativas = LEAST(tentativas + 1, max_tentativas),
                    status = CASE
                        WHEN tentativas + 1 >= max_tentativas
                            THEN 'BLOQUEADO'
                        ELSE status
                    END
                WHERE id = ? AND status = 'PENDENTE'
                  AND expira_em > clock_timestamp()
                """, challengeId);
    }

    @Override
    public int activateIdentity(String collaboratorId) {
        PostgresqlAuthIdentityMutationLock.acquire(jdbcTemplate);
        return jdbcTemplate.update("""
                UPDATE auth_identity identity
                SET status = 'ATIVA', versao_linha = versao_linha + 1
                FROM colaborador
                WHERE identity.colaborador_id = colaborador.id
                  AND identity.colaborador_id = ?
                  AND identity.status IN ('PENDENTE', 'ATIVA')
                  AND colaborador.banco_origem = ('dbsta' || 'vias_acad')
                  AND colaborador.tabela_origem = 'usuarios'
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                """, collaboratorId);
    }

    @Override
    public int consume(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_password_setup_challenge
                SET status = 'CONSUMIDO', consumido_em = clock_timestamp()
                WHERE id = ? AND status = 'PENDENTE'
                  AND expira_em > clock_timestamp()
                  AND tentativas < max_tentativas
                """, challengeId);
    }

    @Override
    public void audit(
            String eventType,
            String actorId,
            String collaboratorId,
            String challengeId
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO auth_password_audit (
                    tipo_evento, ator_colaborador_id,
                    alvo_colaborador_id, desafio_id
                ) VALUES (?, ?, ?, ?)
                """, eventType, actorId, collaboratorId, challengeId);
        if (inserted != 1) {
            throw new IllegalStateException("Auditoria de senha não persistida.");
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 120) {
            throw new IllegalArgumentException("Motivo inválido.");
        }
        return reason.strip();
    }
}
