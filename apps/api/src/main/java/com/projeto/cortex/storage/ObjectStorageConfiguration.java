package com.projeto.cortex.storage;

import com.projeto.cortex.common.SecurityRuntimeMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
public class ObjectStorageConfiguration implements EnvironmentAware {

    private static final Duration S3_API_CALL_ATTEMPT_TIMEOUT =
            Duration.ofSeconds(10);
    private static final Duration S3_API_CALL_TIMEOUT =
            Duration.ofSeconds(30);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Bean
    InitializingBean objectStorageValidation(StorageProperties properties) {
        return () -> StorageDeploymentPolicy.validate(
                properties,
                SecurityRuntimeMode.isLocalOrTestOnly(environment)
        );
    }

    @Bean
    StoredObjectContentInspector storedObjectContentInspector(
            StorageProperties properties
    ) {
        return new StoredObjectContentInspector(
                properties.getMaxSizeBytes(),
                properties.getAllowedMediaTypes()
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "cortex.storage",
            name = "provider",
            havingValue = "local"
    )
    ObjectStorage localObjectStorage(StorageProperties properties) {
        return new LocalObjectStorage(
                Path.of(properties.getLocal().getRoot())
        );
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "cortex.storage",
            name = "provider",
            havingValue = "s3"
    )
    S3Client cortexS3Client(StorageProperties properties) {
        StorageProperties.S3 configuration = properties.getS3();
        var builder = S3Client.builder()
                .region(Region.of(configuration.getRegion().strip()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(
                                S3_API_CALL_ATTEMPT_TIMEOUT
                        )
                        .apiCallTimeout(S3_API_CALL_TIMEOUT)
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(configuration.isPathStyle())
                        .build());
        if (configuration.getEndpoint() != null
                && !configuration.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(
                    configuration.getEndpoint().strip()
            ));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "cortex.storage",
            name = "provider",
            havingValue = "s3"
    )
    ObjectStorage s3ObjectStorage(
            S3Client cortexS3Client,
            StorageProperties properties
    ) {
        return new S3ObjectStorage(
                cortexS3Client,
                properties.getS3().getBucket(),
                properties.getS3().getPrefix(),
                properties.getS3().isSendSseHeader()
        );
    }
}
