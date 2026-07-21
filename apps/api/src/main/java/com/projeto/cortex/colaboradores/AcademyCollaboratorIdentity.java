package com.projeto.cortex.colaboradores;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable identity rule shared by Academy imports and the initial bootstrap. */
public final class AcademyCollaboratorIdentity {

    private static final String SOURCE_NAMESPACE = "dbstavias_acad:usuarios:";

    private AcademyCollaboratorIdentity() {
    }

    public static String fromAcademyUserId(int sourceUserId) {
        return UUID.nameUUIDFromBytes(
                (SOURCE_NAMESPACE + sourceUserId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
