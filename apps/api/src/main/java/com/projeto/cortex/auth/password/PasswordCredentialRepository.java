package com.projeto.cortex.auth.password;

import java.util.Optional;

public interface PasswordCredentialRepository {

    Optional<String> findHashByCollaboratorId(String collaboratorId);

    void upsertHash(String collaboratorId, String passwordHash);
}
