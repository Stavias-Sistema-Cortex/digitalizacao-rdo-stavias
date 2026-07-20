package com.projeto.cortex.financeiro.access;

import com.projeto.cortex.financeiro.unit.FinancialUnitType;
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

    public boolean existsActiveForUnit(
            String colaboradorId,
            String unitId,
            FinancialPermission permission
    ) {
        Integer result = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM permissao_financeira_colaborador
                    WHERE colaborador_id = ?
                      AND unidade_controle_id = ?
                      AND permissao = ?
                      AND status = 'ATIVA'
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                colaboradorId,
                unitId,
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

    public Set<String> findActiveUnitIds(
            String colaboradorId,
            FinancialPermission permission
    ) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                """
                SELECT DISTINCT p.unidade_controle_id
                FROM permissao_financeira_colaborador p
                JOIN finance_unidade_controle u
                  ON u.id = p.unidade_controle_id
                 AND u.status = 'ATIVA'
                LEFT JOIN vinculo_colaborador_obra v
                  ON u.tipo = 'OBRA'
                 AND v.obra_id = u.obra_id
                 AND v.colaborador_id = p.colaborador_id
                 AND v.status = 'ATIVO'
                WHERE p.colaborador_id = ?
                  AND p.permissao = ?
                  AND p.status = 'ATIVA'
                  AND (
                      u.tipo = 'ATIVO'
                      OR (u.tipo = 'OBRA' AND v.id IS NOT NULL)
                  )
                ORDER BY p.unidade_controle_id
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

    public boolean activeWorksiteLinkExists(
            String colaboradorId,
            String obraId
    ) {
        return exists(
                """
                SELECT 1 FROM vinculo_colaborador_obra
                WHERE colaborador_id = ?
                  AND obra_id = ?
                  AND status = 'ATIVO'
                """,
                colaboradorId,
                obraId
        );
    }

    public Optional<FinancialGrantRecord> findByUnit(
            String colaboradorId,
            String unitId,
            FinancialPermission permission
    ) {
        List<FinancialGrantRecord> rows = jdbcTemplate.query(
                selectSql() + """
                WHERE p.colaborador_id = ?
                  AND p.unidade_controle_id = ?
                  AND p.permissao = ?
                LIMIT 1
                """,
                (rs, rowNum) -> map(rs),
                colaboradorId,
                unitId,
                permission.name()
        );
        return rows.stream().findFirst();
    }

    public List<FinancialGrantRecord> findByUnit(String unitId) {
        return jdbcTemplate.query(
                selectSql() + """
                WHERE p.unidade_controle_id = ?
                ORDER BY (p.status = 'ATIVA') DESC, c.nome, p.permissao
                """,
                (rs, rowNum) -> map(rs),
                unitId
        );
    }

    public void insertUnit(
            String id,
            String colaboradorId,
            String unitId,
            String obraId,
            FinancialPermission permission,
            String justification,
            String actorId,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO permissao_financeira_colaborador (
                    id, colaborador_id, obra_id, unidade_controle_id,
                    permissao, status, concedido_em, concedido_por,
                    justificativa
                ) VALUES (?, ?, ?, ?, ?, 'ATIVA', ?, ?, ?)
                """,
                id,
                colaboradorId,
                obraId,
                unitId,
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

    public long countActiveForUnit(String colaboradorId, String unitId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM permissao_financeira_colaborador
                WHERE colaborador_id = ?
                  AND unidade_controle_id = ?
                  AND status = 'ATIVA'
                """,
                Long.class,
                colaboradorId,
                unitId
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

    private boolean exists(String sql, Object... args) {
        List<Integer> rows = jdbcTemplate.query(
                sql + " LIMIT 1",
                (rs, rowNum) -> 1,
                args
        );
        return !rows.isEmpty();
    }

    private String selectSql() {
        return """
                SELECT
                    p.id,
                    p.unidade_controle_id,
                    u.tipo AS unidade_tipo,
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
                JOIN finance_unidade_controle u
                  ON u.id = p.unidade_controle_id
                """;
    }

    private FinancialGrantRecord map(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp revokedAt = rs.getTimestamp("revogado_em");
        return new FinancialGrantRecord(
                rs.getString("id"),
                rs.getString("unidade_controle_id"),
                FinancialUnitType.valueOf(rs.getString("unidade_tipo")),
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
