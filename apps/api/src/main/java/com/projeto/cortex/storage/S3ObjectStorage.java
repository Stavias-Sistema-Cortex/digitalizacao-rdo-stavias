package com.projeto.cortex.storage;

import java.io.InputStream;
import java.util.Objects;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

public final class S3ObjectStorage implements ObjectStorage {

    private static final int MAX_KEY_LENGTH = 700;

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3ObjectStorage(S3Client client, String bucket, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = requireSimpleValue(bucket, "bucket");
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public String backend() {
        return "S3";
    }

    @Override
    public void put(
            String key,
            InputStream inputStream,
            long length,
            String mediaType
    ) {
        if (inputStream == null || length < 0) {
            throw new IllegalArgumentException("Conteúdo S3 inválido.");
        }
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(resolveKey(key))
                            .contentLength(length)
                            .contentType(requireSimpleValue(
                                    mediaType,
                                    "mediaType"
                            ))
                            .serverSideEncryption(
                                    ServerSideEncryption.AES256
                            )
                            .build(),
                    RequestBody.fromInputStream(inputStream, length)
            );
        } catch (S3Exception exception) {
            throw new ObjectStorageException(
                    "Não foi possível gravar o objeto no S3.",
                    exception
            );
        }
    }

    @Override
    public ObjectStorageContent get(String key) {
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(resolveKey(key))
                            .build()
            );
            GetObjectResponse metadata = response.response();
            return new ObjectStorageContent(
                    response,
                    metadata.contentLength() == null
                            ? -1
                            : metadata.contentLength(),
                    metadata.contentType() == null
                            ? "application/octet-stream"
                            : metadata.contentType()
            );
        } catch (NoSuchKeyException exception) {
            throw new ObjectStorageNotFoundException(
                    "Objeto armazenado não encontrado."
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ObjectStorageNotFoundException(
                        "Objeto armazenado não encontrado."
                );
            }
            throw new ObjectStorageException(
                    "Não foi possível abrir o objeto no S3.",
                    exception
            );
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(resolveKey(key))
                            .build()
            );
        } catch (S3Exception exception) {
            throw new ObjectStorageException(
                    "Não foi possível remover o objeto no S3.",
                    exception
            );
        }
    }

    private String resolveKey(String key) {
        validateKey(key);
        return prefix.isEmpty() ? key : prefix + "/" + key;
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || key.startsWith("/") || key.contains("\\")
                || key.contains("//")) {
            throw new IllegalArgumentException("Chave de objeto inválida.");
        }
        for (String segment : key.split("/")) {
            if (segment.isBlank() || ".".equals(segment)
                    || "..".equals(segment)
                    || !segment.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Chave de objeto inválida.");
            }
        }
    }

    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        validateKey(normalized);
        return normalized;
    }

    private static String requireSimpleValue(String value, String label) {
        if (value == null || value.isBlank() || value.contains("\r")
                || value.contains("\n")) {
            throw new IllegalArgumentException(label + " inválido.");
        }
        return value.strip();
    }
}
