package com.projeto.cortex.auth.roles;

import com.projeto.cortex.auth.PapelAcesso;
import java.util.Optional;

interface AdminRoleRepository {

    void lockGovernanceRows();

    Optional<RoleAccount> findActiveAccount(String collaboratorId);

    boolean hasActiveCapability(String collaboratorId);

    long countActiveAlfas();

    long countActiveRoleAdministrators();

    void updateRole(String collaboratorId, PapelAcesso newRole);

    void revokeCapability(
            String collaboratorId,
            String actorId,
            String justification
    );

    int revokeActiveSessions(String collaboratorId, String reason);

    void insertHistory(
            String collaboratorId,
            PapelAcesso previousRole,
            PapelAcesso newRole,
            String actorId,
            String justification
    );
}
