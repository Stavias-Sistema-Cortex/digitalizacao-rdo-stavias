package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OfflineGrantDeploymentConfigurationTest {

    @Test
    void deploymentUsesOnlyMountedProductionKeysAndBoundedPolicyVariables()
            throws Exception {
        String application = Files.readString(
                Path.of("src/main/resources/application.yml")
        );
        String environmentExample = Files.readString(
                Path.of("../../.env.example")
        );

        assertThat(application)
                .contains("${CORTEX_AUTH_OFFLINE_GRANT_KEY_ID:}")
                .contains("${CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE:}")
                .contains("${CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE:}")
                .contains("${CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS:86400}")
                .contains("${CORTEX_AUTH_OFFLINE_GRANT_MAX_WORKSITES:500}")
                .doesNotContain("CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY:");
        assertThat(environmentExample)
                .contains("CORTEX_AUTH_OFFLINE_GRANT_KEY_ID=")
                .contains("CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE=")
                .contains("CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE=")
                .contains("CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS=86400")
                .contains("CORTEX_AUTH_OFFLINE_GRANT_MAX_WORKSITES=500")
                .doesNotContain("BEGIN PRIVATE KEY");
    }
}
