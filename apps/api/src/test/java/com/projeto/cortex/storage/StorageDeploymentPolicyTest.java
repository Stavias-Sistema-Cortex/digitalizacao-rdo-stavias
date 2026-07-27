package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StorageDeploymentPolicyTest {

    @Test
    void productionFailsClosedWithoutADurableProvider() {
        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(
                new StorageProperties(),
                false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ObjectStorage");
    }

    @Test
    void productionLocalRequiresExplicitPersistentAbsoluteRoot() {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("local");
        properties.getLocal().setRoot("relative/path");
        properties.getLocal().setPersistent(true);

        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(
                properties,
                false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absoluta");

        properties.getLocal().setRoot(
                Path.of(System.getProperty("java.io.tmpdir"), "objects")
                        .toAbsolutePath()
                        .toString()
        );
        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(
                properties,
                false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("temporário");
    }

    @Test
    void s3RequiresBucketAndRegionInEveryRuntime() {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("s3");

        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(
                properties,
                true
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket");

        properties.getS3().setBucket("cortex-private");
        properties.getS3().setRegion("sa-east-1");
        assertThatCode(() -> StorageDeploymentPolicy.validate(
                properties,
                false
        )).doesNotThrowAnyException();
    }

    @Test
    void sseHeaderCanOnlyBeDisabledForTheHttpsR2Endpoint() {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("s3");
        properties.getS3().setBucket("cortex-private");
        properties.getS3().setRegion("auto");
        properties.getS3().setSendSseHeader(false);

        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(properties, false))
                .hasMessageContaining("R2");

        properties.getS3().setEndpoint(
                "https://account-id.r2.cloudflarestorage.com"
        );
        assertThatCode(() -> StorageDeploymentPolicy.validate(properties, false))
                .doesNotThrowAnyException();

        properties.getS3().setEndpoint(
                "https://r2.cloudflarestorage.com.attacker.example"
        );
        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(properties, false))
                .hasMessageContaining("R2");
    }

    @Test
    void sseHeaderCannotBeDisabledForAMalformedR2Endpoint() {
        assertSseHeaderCannotBeDisabledFor(
                "https://account-id.r2.cloudflarestorage.com/%zz"
        );
    }

    @Test
    void sseHeaderCannotBeDisabledForAnHttpR2Endpoint() {
        assertSseHeaderCannotBeDisabledFor(
                "http://account-id.r2.cloudflarestorage.com"
        );
    }

    @Test
    void sseHeaderCannotBeDisabledForAnR2EndpointWithUserInfo() {
        assertSseHeaderCannotBeDisabledFor(
                "https://r2-user@account-id.r2.cloudflarestorage.com"
        );
    }

    @Test
    void sseHeaderCannotBeDisabledForAnR2EndpointWithAQuery() {
        assertSseHeaderCannotBeDisabledFor(
                "https://account-id.r2.cloudflarestorage.com?check=1"
        );
    }

    @Test
    void sseHeaderCannotBeDisabledForAnR2EndpointWithAFragment() {
        assertSseHeaderCannotBeDisabledFor(
                "https://account-id.r2.cloudflarestorage.com#fragment"
        );
    }

    private static void assertSseHeaderCannotBeDisabledFor(String endpoint) {
        StorageProperties properties = new StorageProperties();
        properties.setProvider("s3");
        properties.getS3().setBucket("cortex-private");
        properties.getS3().setRegion("auto");
        properties.getS3().setSendSseHeader(false);
        properties.getS3().setEndpoint(endpoint);

        assertThatThrownBy(() -> StorageDeploymentPolicy.validate(properties, false))
                .hasMessageContaining("R2");
    }
}
