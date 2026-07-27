package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3ObjectStorageTest {

    @Test
    void usesPrivateServerSideEncryptedObjectsUnderConfiguredPrefix()
            throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3ObjectStorage storage = new S3ObjectStorage(
                client,
                "cortex-private",
                "production/tenant-a",
                true
        );
        byte[] body = "conteudo".getBytes(StandardCharsets.UTF_8);

        storage.put(
                "objects/ab/id/hash",
                new ByteArrayInputStream(body),
                body.length,
                "text/plain"
        );

        ArgumentCaptor<PutObjectRequest> request =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("cortex-private");
        assertThat(request.getValue().key())
                .isEqualTo("production/tenant-a/objects/ab/id/hash");
        assertThat(request.getValue().contentType()).isEqualTo("text/plain");
        assertThat(request.getValue().serverSideEncryption())
                .isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.getValue().acl()).isNull();
    }

    @Test
    void omitsTheUnsupportedSseHeaderForR2() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3ObjectStorage storage = new S3ObjectStorage(
                client,
                "cortex-private",
                "production",
                false
        );

        storage.put(
                "objects/ab/id/hash",
                new ByteArrayInputStream(new byte[]{1}),
                1,
                "application/octet-stream"
        );

        ArgumentCaptor<PutObjectRequest> request =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().serverSideEncryption()).isNull();
    }

    @Test
    void streamsReadsAndDeletesWithoutGeneratingPublicUrls() throws Exception {
        byte[] body = "conteudo".getBytes(StandardCharsets.UTF_8);
        S3Client client = mock(S3Client.class);
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) body.length)
                .contentType("text/plain")
                .build();
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(
                        response,
                        AbortableInputStream.create(new ByteArrayInputStream(body))
                )
        );
        S3ObjectStorage storage = new S3ObjectStorage(
                client,
                "cortex-private",
                "",
                true
        );

        try (ObjectStorageContent content = storage.get(
                "objects/ab/id/hash"
        )) {
            assertThat(content.inputStream().readAllBytes()).isEqualTo(body);
            assertThat(content.mediaType()).isEqualTo("text/plain");
        }
        storage.delete("objects/ab/id/hash");

        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }
}
