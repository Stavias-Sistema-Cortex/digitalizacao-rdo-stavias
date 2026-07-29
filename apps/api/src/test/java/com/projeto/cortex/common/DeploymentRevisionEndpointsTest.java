package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.offline.OfflineGrantPublicKeyFingerprint;
import com.projeto.cortex.storage.ObjectStorageReadiness;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentRevisionEndpointsTest {

    private static final String REVISION =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String OFFLINE_PUBLIC_KEY_SHA256 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String RUNTIME_INSTANCE_SHA256 =
            "P6007CJAEZl37rxcPnz9O3ceG-8_Rbi0njDbAmMVb9Y";
    private static final String DATABASE_RELEASE_MARKER =
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA";

    @Test
    void healthPublishesTheExactRuntimeRevision() {
        Map<String, String> response =
                new HealthController(new RuntimeRevision(REVISION)).health();

        assertThat(response)
                .containsEntry("revision", REVISION)
                .doesNotContainKey("offlineGrantPublicKeySha256");
    }

    @Test
    void renderHealthIgnoresTheMigrationRevisionOverride() {
        String migrationRevision = "a".repeat(40);
        String renderRevision = "b".repeat(40);

        new ApplicationContextRunner()
                .withBean(RuntimeRevision.class)
                .withBean(HealthController.class)
                .withPropertyValues(
                        "CORTEX_RELEASE_REVISION=" + migrationRevision,
                        "RENDER_GIT_COMMIT=" + renderRevision
                )
                .run(context -> assertThat(
                        context.getBean(HealthController.class).health()
                ).containsEntry("revision", renderRevision));
    }

    @Test
    void readinessPublishesOnlyPublicReleaseEvidence() {
        RuntimeReadiness ready = new RuntimeReadiness() {
            @Override
            public void verifyRuntimeReadiness() {
            }

            @Override
            public Map<String, String> publicEvidence() {
                return Map.of(
                        "databaseReleaseRevision", REVISION,
                        "databaseReleaseMarker", DATABASE_RELEASE_MARKER
                );
            }
        };
        OfflineGrantPublicKeyFingerprint fingerprint =
                () -> OFFLINE_PUBLIC_KEY_SHA256;
        ObjectStorageReadiness objectStorage = () -> {
        };
        Map<String, String> response =
                new ReadinessController(
                        ready,
                        new RuntimeRevision(REVISION),
                        new RuntimeInstanceFingerprint(
                                "render-instance-contract"
                        ),
                        fingerprint,
                        objectStorage
                )
                        .readiness();

        assertThat(response)
                .containsEntry("revision", REVISION)
                .containsEntry(
                        "offlineGrantPublicKeySha256",
                        OFFLINE_PUBLIC_KEY_SHA256
                )
                .containsEntry("objectStorage", "READY")
                .containsEntry(
                        "runtimeInstanceSha256",
                        RUNTIME_INSTANCE_SHA256
                )
                .containsEntry("databaseReleaseRevision", REVISION)
                .containsEntry(
                        "databaseReleaseMarker",
                        DATABASE_RELEASE_MARKER
                )
                .doesNotContainKeys(
                        "offlineGrantPrivateKey",
                        "offlineGrantPublicKeySpki",
                        "offlineGrantKeyPem",
                        "renderInstanceId",
                        "RENDER_INSTANCE_ID"
                );
    }

    @Test
    void activationReadinessDoesNotRequireProductionOnlyDependencies() {
        RuntimeReadiness activation = new RuntimeReadiness() {
            @Override
            public void verifyRuntimeReadiness() {
            }

            @Override
            public String readinessStatus() {
                return "ATIVACAO_PENDENTE";
            }
        };

        Map<String, String> response =
                new PostgresqlActivationReadinessController(
                        activation,
                        new RuntimeRevision(REVISION)
                ).readiness();

        assertThat(response)
                .containsEntry("status", "ATIVACAO_PENDENTE")
                .containsEntry("service", "cortex-api")
                .containsEntry("revision", REVISION)
                .containsKey("timestamp")
                .doesNotContainKeys(
                        "objectStorage",
                        "runtimeInstanceSha256",
                        "offlineGrantPublicKeySha256"
                );
    }
}
