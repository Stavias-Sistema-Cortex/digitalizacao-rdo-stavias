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
}
