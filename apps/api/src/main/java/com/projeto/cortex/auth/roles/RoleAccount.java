package com.projeto.cortex.auth.roles;

import com.projeto.cortex.auth.PapelAcesso;
import java.time.LocalDateTime;

record RoleAccount(
        String id,
        String name,
        PapelAcesso role,
        long rowVersion,
        LocalDateTime updatedAt
) {

    RoleAccount(String id, String name, PapelAcesso role) {
        this(id, name, role, -1L, null);
    }
}
