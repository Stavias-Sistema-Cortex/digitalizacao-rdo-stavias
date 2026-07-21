package com.projeto.cortex.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit, file-only input for the one-shot PostgreSQL ALFA bootstrap. */
@ConfigurationProperties(prefix = "cortex.postgresql.bootstrap")
public final class BootstrapAdminProperties {

    private boolean enabled;
    private String adminCpfFile;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminCpfFile() {
        return adminCpfFile;
    }

    public void setAdminCpfFile(String adminCpfFile) {
        this.adminCpfFile = adminCpfFile;
    }
}
