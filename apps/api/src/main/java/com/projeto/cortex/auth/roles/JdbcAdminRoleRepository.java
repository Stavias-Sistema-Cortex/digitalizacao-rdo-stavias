package com.projeto.cortex.auth.roles;

import com.projeto.cortex.auth.PapelAcesso;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdminRoleRepository implements AdminRoleRepository {

    private static final String CAPABILITY = "ADMINISTRAR_PAPEIS";

    private final JdbcTemplate jdbc;

    JdbcAdminRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockGovernanceRows() {
        jdbc.queryForList("""
                SELECT id
                FROM colaborador
                WHERE ativo = TRUE
                  AND deletado_em IS NULL
                  AND papel_acesso = 'ALFA'
                FOR UPDATE
                """, String.class);
        jdbc.queryForList("""
                SELECT colaborador_id
                FROM auth_capacidade_administrativa
                WHERE capacidade = 'ADMINISTRAR_PAPEIS'
                  AND ativa = TRUE
                FOR UPDATE
                """, String.class);
    }

    @Override
    public Optional<RoleAccount> findActiveAccount(String collaboratorId) {
        List<RoleAccount> accounts = jdbc.query("""
                SELECT id, nome, papel_acesso
                FROM colaborador
                WHERE id = ?
                  AND ativo = TRUE
                  AND deletado_em IS NULL
                LIMIT 1
                """, (resultSet, rowNumber) -> new RoleAccount(
                resultSet.getString("id"),
                resultSet.getString("nome"),
                PapelAcesso.fromPersistedExact(
                        resultSet.getString("papel_acesso")
                ).orElseThrow(() -> new IllegalStateException(
                        "Papel persistido inválido."
                ))
        ), collaboratorId);
        return accounts.stream().findFirst();
    }

    @Override
    public boolean hasActiveCapability(String collaboratorId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_capacidade_administrativa
                WHERE colaborador_id = ?
                  AND capacidade = ?
                  AND ativa = TRUE
                """, Integer.class, collaboratorId, CAPABILITY);
        return count != null && count > 0;
    }

    @Override
    public long countActiveAlfas() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM colaborador
                WHERE ativo = TRUE
                  AND deletado_em IS NULL
                  AND papel_acesso = 'ALFA'
                """, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveRoleAdministrators() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM auth_capacidade_administrativa capacidade
                INNER JOIN colaborador
                    ON colaborador.id = capacidade.colaborador_id
                WHERE capacidade.capacidade = ?
                  AND capacidade.ativa = TRUE
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso = 'ALFA'
                """, Long.class, CAPABILITY);
        return count == null ? 0 : count;
    }

    @Override
    public void updateRole(String collaboratorId, PapelAcesso newRole) {
        int updated = jdbc.update("""
                UPDATE colaborador
                SET papel_acesso = ?,
                    atualizado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND ativo = TRUE
                  AND deletado_em IS NULL
                """, newRole.name(), collaboratorId);
        if (updated != 1) {
            throw new IllegalStateException("Papel do colaborador não atualizado.");
        }
    }

    @Override
    public void revokeCapability(
            String collaboratorId,
            String actorId,
            String justification
    ) {
        jdbc.update("""
                UPDATE auth_capacidade_administrativa
                SET ativa = FALSE,
                    revogada_em = CURRENT_TIMESTAMP(6),
                    revogada_por = ?,
                    justificativa_revogacao = ?,
                    versao_linha = versao_linha + 1
                WHERE colaborador_id = ?
                  AND capacidade = ?
                  AND ativa = TRUE
                """, actorId, justification, collaboratorId, CAPABILITY);
    }

    @Override
    public int revokeActiveSessions(String collaboratorId, String reason) {
        return jdbc.update("""
                UPDATE auth_session
                SET revogado_em = CURRENT_TIMESTAMP(6),
                    revogado_motivo = ?
                WHERE colaborador_id = ?
                  AND revogado_em IS NULL
                  AND expira_em > CURRENT_TIMESTAMP(6)
                """, reason, collaboratorId);
    }

    @Override
    public void insertHistory(
            String collaboratorId,
            PapelAcesso previousRole,
            PapelAcesso newRole,
            String actorId,
            String justification
    ) {
        jdbc.update("""
                INSERT INTO auth_papel_acesso_historico (
                    id,
                    colaborador_id,
                    papel_anterior,
                    papel_novo,
                    alterado_por,
                    justificativa
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                collaboratorId,
                previousRole.name(),
                newRole.name(),
                actorId,
                justification
        );
    }
}
