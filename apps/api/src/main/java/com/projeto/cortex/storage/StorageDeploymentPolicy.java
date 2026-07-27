package com.projeto.cortex.storage;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

public final class StorageDeploymentPolicy {

    private StorageDeploymentPolicy() {
    }

    public static void validate(
            StorageProperties properties,
            boolean localOrTestOnly
    ) {
        if (properties == null) {
            throw new IllegalStateException(
                    "Configuração de ObjectStorage ausente."
            );
        }
        if (properties.getMaxSizeBytes() <= 0
                || properties.getAllowedMediaTypes() == null
                || properties.getAllowedMediaTypes().isEmpty()) {
            throw new IllegalStateException(
                    "Limites de ObjectStorage inválidos."
            );
        }
        String provider = normalize(properties.getProvider());
        if (provider == null) {
            throw new IllegalStateException(
                    "Provider de ObjectStorage não configurado."
            );
        }
        switch (provider) {
            case "local" -> validateLocal(properties, localOrTestOnly);
            case "s3" -> validateS3(properties);
            default -> throw new IllegalStateException(
                    "Provider de ObjectStorage não suportado."
            );
        }
    }

    private static void validateLocal(
            StorageProperties properties,
            boolean localOrTestOnly
    ) {
        String rootValue = strip(properties.getLocal().getRoot());
        if (rootValue == null) {
            throw new IllegalStateException(
                    "ObjectStorage local exige uma raiz configurada."
            );
        }
        Path root = Path.of(rootValue);
        if (!root.isAbsolute()) {
            throw new IllegalStateException(
                    "A raiz do ObjectStorage local deve ser absoluta."
            );
        }
        if (localOrTestOnly) {
            return;
        }
        if (!properties.getLocal().isPersistent()) {
            throw new IllegalStateException(
                    "ObjectStorage local em produção exige volume persistente."
            );
        }
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath()
                .normalize();
        if (root.toAbsolutePath().normalize().startsWith(temporaryRoot)) {
            throw new IllegalStateException(
                    "ObjectStorage de produção não pode usar diretório temporário."
            );
        }
    }

    private static void validateS3(StorageProperties properties) {
        if (strip(properties.getS3().getBucket()) == null) {
            throw new IllegalStateException(
                    "ObjectStorage S3 exige bucket privado."
            );
        }
        if (strip(properties.getS3().getRegion()) == null) {
            throw new IllegalStateException(
                    "ObjectStorage S3 exige region."
            );
        }
        if (!properties.getS3().isSendSseHeader()) {
            validateR2Endpoint(properties);
        }
    }

    private static void validateR2Endpoint(StorageProperties properties) {
        URI endpoint;
        try {
            endpoint = URI.create(strip(properties.getS3().getEndpoint()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "O header SSE só pode ser omitido para um endpoint HTTPS R2."
            );
        }
        String host = endpoint.getHost();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || host == null
                || !host.endsWith(".r2.cloudflarestorage.com")
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalStateException(
                    "O header SSE só pode ser omitido para um endpoint HTTPS R2."
            );
        }
    }

    private static String normalize(String value) {
        String stripped = strip(value);
        return stripped == null ? null : stripped.toLowerCase(Locale.ROOT);
    }

    private static String strip(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
