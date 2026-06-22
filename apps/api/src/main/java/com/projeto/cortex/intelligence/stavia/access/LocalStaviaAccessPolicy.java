package com.projeto.cortex.intelligence.stavia.access;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Permissive policy for the {@code local} profile: it grants the consult
 * permission to any identified user and allows access to any identified
 * worksite. It still denies blank users/worksites, so the authorization seam is
 * exercised end-to-end.
 *
 * <p><strong>Not for production.</strong> A real deployment must replace this
 * with a policy backed by an actual roles/worksite-access store; until then no
 * non-local profile registers a {@link StaviaAccessPolicy}, so the controller
 * is intentionally unavailable outside {@code local}.
 */
@Component
@Profile("local")
public class LocalStaviaAccessPolicy implements StaviaAccessPolicy {

    @Override
    public Set<String> permissionsFor(String userId) {
        return hasText(userId)
                ? Set.of(StaviaEngine.REQUIRED_PERMISSION)
                : Set.of();
    }

    @Override
    public boolean canAccessWorksite(String userId, String worksiteId) {
        return hasText(userId) && hasText(worksiteId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
