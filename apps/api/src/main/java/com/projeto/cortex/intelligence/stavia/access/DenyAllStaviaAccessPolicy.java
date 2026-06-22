package com.projeto.cortex.intelligence.stavia.access;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fail-closed default policy for every profile except {@code local}. It grants
 * no permissions and no worksite access, so the Stav.IA pipeline denies all
 * queries until a real, deployment-specific {@link StaviaAccessPolicy} is
 * provided. This keeps the application context startable in production without
 * silently authorizing anyone.
 */
@Component
@Profile("!local")
public class DenyAllStaviaAccessPolicy implements StaviaAccessPolicy {

    @Override
    public Set<String> permissionsFor(String userId) {
        return Set.of();
    }

    @Override
    public boolean canAccessWorksite(String userId, String worksiteId) {
        return false;
    }
}
