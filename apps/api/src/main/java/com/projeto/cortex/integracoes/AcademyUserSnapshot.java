package com.projeto.cortex.integracoes;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of one full Academy source scan.
 *
 * <p>The completeness marker is intentionally explicit so callers cannot
 * deactivate missing collaborators from a partial source result.</p>
 */
public record AcademyUserSnapshot(
        List<AcademySourceAdapter.UsuarioAcademyRecord> users,
        boolean complete
) {

    public AcademyUserSnapshot {
        users = List.copyOf(Objects.requireNonNull(users, "users"));
    }

    public static AcademyUserSnapshot complete(
            List<AcademySourceAdapter.UsuarioAcademyRecord> users
    ) {
        return new AcademyUserSnapshot(users, true);
    }
}
