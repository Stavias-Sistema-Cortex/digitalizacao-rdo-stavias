package com.projeto.cortex.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Public, non-secret identifier of the exact application revision in this process. */
@Component
public final class RuntimeRevision {

    private static final String LOCAL_REVISION = "local";

    private final String value;

    public RuntimeRevision(@Value("${RENDER_GIT_COMMIT:local}") String value) {
        String normalized = value == null ? "" : value.trim();
        this.value = normalized.isEmpty() ? LOCAL_REVISION : normalized;
    }

    public String value() {
        return value;
    }
}
