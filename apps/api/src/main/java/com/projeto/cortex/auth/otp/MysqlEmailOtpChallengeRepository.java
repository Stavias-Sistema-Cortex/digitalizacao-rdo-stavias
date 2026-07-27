package com.projeto.cortex.auth.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Legacy MySQL persistence adapter for single-use e-mail challenges. */
@Repository
@Profile("!postgresql-common")
public class MysqlEmailOtpChallengeRepository implements EmailOtpChallengeStore {

    private final JdbcTemplate jdbcTemplate;

    public MysqlEmailOtpChallengeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Instant create(
            String challengeId,
            String collaboratorId,
            String identifierDigest,
            String codeDigest,
            String clientInstanceHash,
            int ttlSeconds,
            int maxAttempts
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO auth_email_challenge (
                    id, colaborador_id, identifier_digest, codigo_digest,
                    client_instance_hash, client_instance_bound,
                    expira_em, tentativas, max_tentativas, status
                ) VALUES (?, ?, ?, ?, ?, TRUE,
                    CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second'),
                    0, ?, 'PENDENTE')
                """, challengeId, collaboratorId, identifierDigest,
                codeDigest, clientInstanceHash, ttlSeconds, maxAttempts);
        if (inserted != 1) {
            throw new IllegalStateException("Desafio de autenticação não persistido.");
        }
        Timestamp expiresAt = jdbcTemplate.queryForObject("""
                SELECT expira_em FROM auth_email_challenge WHERE id = ?
                """, Timestamp.class, challengeId);
        if (expiresAt == null) {
            throw new IllegalStateException(
                    "Expiração do desafio de autenticação indisponível."
            );
        }
        return expiresAt.toInstant();
    }

    @Override
    public Optional<LockedChallenge> lockForVerification(
            String challengeId,
            String clientInstanceHash
    ) {
        List<LockedChallenge> rows = jdbcTemplate.query("""
                SELECT challenge.id, challenge.colaborador_id,
                    challenge.codigo_digest, challenge.expira_em,
                    challenge.tentativas, challenge.max_tentativas,
                    challenge.status AS challenge_status,
                    CURRENT_TIMESTAMP(6) AS agora,
                    identity.email_autenticacao,
                    identity.status AS identity_status,
                    colaborador.nome, colaborador.papel_acesso,
                    colaborador.ativo, colaborador.deletado_em
                FROM auth_email_challenge challenge
                LEFT JOIN auth_identity identity
                    ON identity.colaborador_id = challenge.colaborador_id
                LEFT JOIN colaborador
                    ON colaborador.id = challenge.colaborador_id
                WHERE challenge.id = ?
                  AND challenge.client_instance_bound = TRUE
                  AND challenge.client_instance_hash = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LockedChallenge(
                        resultSet.getString("id"),
                        resultSet.getString("colaborador_id"),
                        resultSet.getString("codigo_digest"),
                        resultSet.getTimestamp("expira_em").toInstant(),
                        resultSet.getInt("tentativas"),
                        resultSet.getInt("max_tentativas"),
                        resultSet.getString("challenge_status"),
                        resultSet.getTimestamp("agora").toInstant(),
                        resultSet.getString("email_autenticacao"),
                        resultSet.getString("nome"),
                        resultSet.getString("papel_acesso"),
                        resultSet.getString("identity_status"),
                        resultSet.getBoolean("ativo"),
                        resultSet.getTimestamp("deletado_em") != null
                ), challengeId, clientInstanceHash);
        if (rows.size() > 1) {
            throw new IllegalStateException("Desafio de autenticação ambíguo.");
        }
        return rows.stream().findFirst();
    }

    @Override
    public Optional<DeliveryState> deliveryState(String challengeId) {
        List<DeliveryState> rows = jdbcTemplate.query("""
                SELECT status, expira_em, CURRENT_TIMESTAMP(6) AS agora
                FROM auth_email_challenge WHERE id = ?
                """, (resultSet, rowNumber) -> new DeliveryState(
                        resultSet.getString("status"),
                        resultSet.getTimestamp("expira_em").toInstant(),
                        resultSet.getTimestamp("agora").toInstant()
                ), challengeId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Estado de entrega de autenticação ambíguo.");
        }
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int invalidateUndelivered(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_email_challenge SET status = 'BLOQUEADO'
                WHERE id = ? AND status = 'PENDENTE'
                """, challengeId);
    }

    @Override
    public int markExpired(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_email_challenge SET status = 'EXPIRADO'
                WHERE id = ? AND status = 'PENDENTE'
                    AND expira_em <= CURRENT_TIMESTAMP(6)
                """, challengeId);
    }

    @Override
    public int block(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_email_challenge SET status = 'BLOQUEADO'
                WHERE id = ? AND status = 'PENDENTE'
                """, challengeId);
    }

    @Override
    public int recordFailedAttempt(String challengeId) {
        return jdbcTemplate.update("""
                UPDATE auth_email_challenge
                SET status = CASE WHEN tentativas + 1 >= max_tentativas
                        THEN 'BLOQUEADO' ELSE status END,
                    tentativas = LEAST(tentativas + 1, max_tentativas)
                WHERE id = ? AND status = 'PENDENTE'
                    AND expira_em > CURRENT_TIMESTAMP(6)
                """, challengeId);
    }

    @Override
    public int consume(
            String challengeId,
            String collaboratorId,
            String codeDigest,
            String clientInstanceHash
    ) {
        return jdbcTemplate.update("""
                UPDATE auth_email_challenge
                SET status = 'CONSUMIDO', consumido_em = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND colaborador_id = ? AND status = 'PENDENTE'
                    AND expira_em > CURRENT_TIMESTAMP(6)
                    AND tentativas < max_tentativas AND codigo_digest = ?
                    AND client_instance_bound = TRUE
                    AND client_instance_hash = ?
                """, challengeId, collaboratorId, codeDigest,
                clientInstanceHash);
    }

    @Override
    public int activateIdentity(String collaboratorId, String authenticationEmail) {
        return jdbcTemplate.update("""
                UPDATE auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                SET identity.status = 'ATIVA',
                    identity.email_verificado_em = COALESCE(
                        identity.email_verificado_em, CURRENT_TIMESTAMP(6)),
                    identity.versao_linha = identity.versao_linha + 1
                WHERE identity.colaborador_id = ?
                    AND identity.email_autenticacao = ?
                    AND identity.status IN ('PENDENTE', 'ATIVA')
                    AND colaborador.ativo = TRUE
                    AND colaborador.deletado_em IS NULL
                    AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                """, collaboratorId, authenticationEmail);
    }
}
