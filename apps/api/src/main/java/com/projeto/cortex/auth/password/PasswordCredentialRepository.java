package com.projeto.cortex.auth.password;

import java.util.Optional;

public interface PasswordCredentialRepository {

    Optional<String> findHashByCollaboratorId(String collaboratorId);

    Optional<Long> findAuthEpochByCollaboratorId(String collaboratorId);

    long rotateHashAndEpoch(String collaboratorId, String passwordHash);

    long invalidateEpoch(String collaboratorId);
}
