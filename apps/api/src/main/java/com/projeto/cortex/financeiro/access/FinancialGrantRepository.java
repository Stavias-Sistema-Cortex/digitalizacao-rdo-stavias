package com.projeto.cortex.financeiro.access;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialGrantRepository {

    private final JdbcTemplate jdbcTemplate;

    public FinancialGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsActive(
            String colaboradorId,
            String obraId,
            FinancialPermission permission
    ) {
        Integer result = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM permissao_financeira_colaborador
                    WHERE colaborador_id = ?
                      AND obra_id = ?
                      AND permissao = ?
                      AND status = 'ATIVA'
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                colaboradorId,
                obraId,
                permission.name()
        );
        return result != null && result == 1;
    }

    public Set<String> findActiveObraIds(
            String colaboradorId,
            FinancialPermission permission
    ) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                """
                SELECT obra_id
                FROM permissao_financeira_colaborador
                WHERE colaborador_id = ?
                  AND permissao = ?
                  AND status = 'ATIVA'
                ORDER BY obra_id
                """,
                String.class,
                colaboradorId,
                permission.name()
        ));
    }

    public Set<String> findAllActiveObraIds() {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                """
                SELECT id
                FROM obra
                WHERE arquivado_em IS NULL
                ORDER BY id
                """,
                String.class
        ));
    }

    public boolean activeBetaCollaboratorExists(String colaboradorId) {
        return exists(
                """
                SELECT 1 FROM colaborador
                WHERE id = ?
                  AND ativo = 1
                  AND deletado_em IS NULL
                  AND papel_acesso = 'BETA'
                """,
                colaboradorId
        );
    }

    public boolean worksiteExists(String obraId) {
        return exists(
                "SELECT 1 FROM obra WHERE id = ? AND arquivado_em IS NULL",
                obraId
        );
    }

    public Optional<FinancialGrantRecord> find(
            String colaboradorId,
            String obraId,
            FinancialPermission permission
    ) {
        List<FinancialGrantRecord> rows = jdbcTemplate.query(
                selectSql() + """
                WHERE p.colaborador_id = ?
                  AND p.obra_id = ?
                  AND p.permissao = ?
                LIMIT 1
                """,
                (rs, rowNum) -> map(rs),
                colaboradorId,
                obraId,
                permission.name()
        );
        return rows.stream().findFirst();
    }

    public List<FinancialGrantRecord> findByWorksite(String obraId) {
        return jdbcTemplate.query(
                selectSql() + """
                WHERE p.obra_id = ?
                ORDER BY (p.status = 'ATIVA') DESC, c.nome, p.permissao
                """,
                (rs, rowNum) -> map(rs),
                obraId
        );
    }

    public void insert(
            String id,
            String colaboradorId,
            String obraId,
            FinancialPermission permission,
            String justification,
            String actorId,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO permissao_financeira_colaborador (
                    id, colaborador_id, obra_id, permissao, status,
                    concedido_em, concedido_por, justificativa
                ) VALUES (?, ?, ?, ?, 'ATIVA', ?, ?, ?)
                """,
                id,
                colaboradorId,
                obraId,
                permission.name(),
                now,
                actorId,
                justification
        );
    }

    public boolean reactivate(
            String id,
            String justification,
            String actorId,
            LocalDateTime now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE permissao_financeira_colaborador
                SET status = 'ATIVA',
                    concedido_em = ?,
                    concedido_por = ?,
                    revogado_em = NULL,
                    revogado_por = NULL,
                    justificativa = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'REVOGADA'
                """,
                now,
                actorId,
                justification,
                id
        ) == 1;
    }

    public boolean revoke(
            String id,
            String justification,
            String actorId,
            LocalDateTime now
    ) {
        return jdbcTemplate.update(
                """
                UPDATE permissao_financeira_colaborador
                SET status = 'REVOGADA',
                    revogado_em = ?,
                    revogado_por = ?,
                    justificativa = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'ATIVA'
                """,
                now,
                actorId,
                justification,
                id
        ) == 1;
    }

    public long countActive(String colaboradorId, String obraId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM permissao_financeira_colaborador
                WHERE colaborador_id = ?
                  AND obra_id = ?
                  AND status = 'ATIVA'
                """,
                Long.class,
                colaboradorId,
                obraId
        );
        return count == null ? 0 : count;
    }

    public LocalDateTime databaseNow() {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP(6)",
                Timestamp.class
        );
        if (timestamp == null) {
            throw new IllegalStateException("O banco não retornou o horário atual.");
        }
        return timestamp.toLocalDateTime();
    }

    private boolean exists(String sql, String id) {
        List<Integer> rows = jdbcTemplate.query(
                sql + " LIMIT 1",
                (rs, rowNum) -> 1,
                id
        );
        return !rows.isEmpty();
    }

    private String selectSql() {
        return """
                SELECT
                    p.id,
                    p.obra_id,
                    p.colaborador_id,
                    c.nome AS colaborador_nome,
                    p.permissao,
                    p.status,
                    p.justificativa,
                    p.concedido_em,
                    p.concedido_por,
                    p.revogado_em,
                    p.revogado_por
                FROM permissao_financeira_colaborador p
                JOIN colaborador c ON c.id = p.colaborador_id
                """;
    }

    private FinancialGrantRecord map(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp revokedAt = rs.getTimestamp("revogado_em");
        return new FinancialGrantRecord(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("colaborador_id"),
                rs.getString("colaborador_nome"),
                FinancialPermission.valueOf(rs.getString("permissao")),
                rs.getString("status"),
                rs.getString("justificativa"),
                rs.getTimestamp("concedido_em").toLocalDateTime(),
                rs.getString("concedido_por"),
                revokedAt == null ? null : revokedAt.toLocalDateTime(),
                rs.getString("revogado_por")
        );
    }
}
