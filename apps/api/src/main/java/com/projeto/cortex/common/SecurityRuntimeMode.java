package com.projeto.cortex.common;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Central fail-closed distinction between isolated development and runtime. */
public final class SecurityRuntimeMode {

    private static final Set<String> ISOLATED_RUNTIME_PROFILES = Set.of(
            "local",
            "test",
            "postgresql",
            "postgresql-common",
            "postgresql-migrate",
            "postgresql-bootstrap",
            "postgresql-activation"
    );

    private SecurityRuntimeMode() {
    }

    public static boolean isLocalOrTestOnly(Environment environment) {
        Objects.requireNonNull(environment, "Ambiente obrigatório.");
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            return false;
        }
        if (Arrays.stream(activeProfiles).noneMatch(
                profile -> "local".equals(profile) || "test".equals(profile)
        )) {
            return false;
        }
        return Arrays.stream(activeProfiles).allMatch(
                ISOLATED_RUNTIME_PROFILES::contains
        );
    }

    public static boolean isProduction(Environment environment) {
        return !isLocalOrTestOnly(environment);
    }
}
