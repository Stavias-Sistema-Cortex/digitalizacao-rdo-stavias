package com.projeto.cortex.common;

import java.util.Arrays;
import java.util.Objects;
import org.springframework.core.env.Environment;

/** Central fail-closed distinction between isolated development and runtime. */
public final class SecurityRuntimeMode {

    private SecurityRuntimeMode() {
    }

    public static boolean isLocalOrTestOnly(Environment environment) {
        Objects.requireNonNull(environment, "Ambiente obrigatório.");
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0
                && Arrays.stream(activeProfiles).allMatch(
                        profile -> "local".equals(profile)
                                || "test".equals(profile)
                );
    }

    public static boolean isProduction(Environment environment) {
        return !isLocalOrTestOnly(environment);
    }
}
