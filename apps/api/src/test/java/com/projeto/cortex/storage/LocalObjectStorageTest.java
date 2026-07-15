package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {

    @TempDir
    Path root;

    @Test
    void writesAtomicallyAndReadsTheStoredContent() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        byte[] body = "conteudo seguro".getBytes(StandardCharsets.UTF_8);

        storage.put(
                "objects/ab/object-id/hash",
                new ByteArrayInputStream(body),
                body.length,
                "text/plain"
        );

        try (ObjectStorageContent stored = storage.get(
                "objects/ab/object-id/hash"
        )) {
            assertThat(stored.length()).isEqualTo(body.length);
            assertThat(stored.mediaType()).isEqualTo("text/plain");
            assertThat(stored.inputStream().readAllBytes()).isEqualTo(body);
        }
    }

    @Test
    void rejectsTraversalAndAbsoluteKeys() {
        LocalObjectStorage storage = new LocalObjectStorage(root);

        assertThatThrownBy(() -> storage.put(
                "../escape",
                InputStream.nullInputStream(),
                0,
                "text/plain"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> storage.get("/tmp/escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptedUploadLeavesNoReadableObjectOrTemporaryFile()
            throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(root);
        InputStream failing = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ > 2) {
                    throw new IOException("simulated interruption");
                }
                return 'x';
            }
        };

        assertThatThrownBy(() -> storage.put(
                "objects/ab/object-id/hash",
                failing,
                8,
                "text/plain"
        )).isInstanceOf(ObjectStorageException.class);

        assertThatThrownBy(() -> storage.get(
                "objects/ab/object-id/hash"
        )).isInstanceOf(ObjectStorageNotFoundException.class);

        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }
}
