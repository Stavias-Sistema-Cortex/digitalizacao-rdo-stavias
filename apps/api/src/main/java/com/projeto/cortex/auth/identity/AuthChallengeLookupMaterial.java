package com.projeto.cortex.auth.identity;

import java.util.List;

/** Fixed-shape protected lookup values for a real or decoy identifier. */
public record AuthChallengeLookupMaterial(
        List<CpfLookupDigest> candidates,
        String legacyDigest
) {

    public AuthChallengeLookupMaterial {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (candidates.isEmpty()
                || candidates.size() > 2
                || legacyDigest == null
                || !legacyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Material de lookup de autenticação inválido."
            );
        }
    }
}
