package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

class ObjectStorageConfigurationTest {

    @Test
    void boundsEveryS3ApiCallAndAttempt() {
        StorageProperties properties = new StorageProperties();
        properties.getS3().setRegion("auto");
        ObjectStorageConfiguration configuration =
                new ObjectStorageConfiguration();

        try (S3Client client = configuration.cortexS3Client(properties)) {
            var overrides = client.serviceClientConfiguration()
                    .overrideConfiguration();

            assertThat(overrides.apiCallAttemptTimeout())
                    .isEqualTo(Optional.of(Duration.ofSeconds(10)));
            assertThat(overrides.apiCallTimeout())
                    .isEqualTo(Optional.of(Duration.ofSeconds(30)));
        }
    }
}
