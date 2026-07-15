package com.projeto.cortex.mensagens;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemAttachmentStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void stagesCommitsAndReadsContentUsingOpaqueServerKey() throws Exception {
        FileSystemAttachmentStorage storage =
                new FileSystemAttachmentStorage(tempDir);
        byte[] content = "conteúdo operacional".getBytes(StandardCharsets.UTF_8);

        String storageKey;
        try (AttachmentStorage.StagedAttachment staged = storage.stage(
                new ByteArrayInputStream(content), 1024
        )) {
            assertThat(staged.sizeBytes()).isEqualTo(content.length);
            assertThat(staged.sha256()).hasSize(64);
            assertThat(staged.header()).startsWith(
                    "conteúdo".getBytes(StandardCharsets.UTF_8)
            );
            storageKey = staged.commit();
        }

        assertThat(storageKey).matches("[0-9a-f]{2}/[0-9a-f-]{36}");
        try (var stored = storage.open(storageKey)) {
            assertThat(stored.readAllBytes()).isEqualTo(content);
        }
        if (tempDir.getFileSystem().supportedFileAttributeViews()
                .contains("posix")) {
            Path storedPath = tempDir.resolve("files").resolve(storageKey);
            assertThat(Files.getPosixFilePermissions(storedPath))
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE
                    );
        }

        storage.delete(storageKey);
        assertThatThrownBy(() -> storage.open(storageKey))
                .isInstanceOf(java.io.FileNotFoundException.class);
    }

    @Test
    void oversizedStageIsRejectedAndLeavesNoTemporaryFile() {
        FileSystemAttachmentStorage storage =
                new FileSystemAttachmentStorage(tempDir);

        assertThatThrownBy(() -> storage.stage(
                new ByteArrayInputStream(new byte[9]), 8
        )).isInstanceOf(AttachmentStorageLimitException.class);

        assertThat(Files.exists(tempDir.resolve("staging")))
                .isTrue();
        assertThat(tempDir.resolve("staging").toFile().list())
                .isEmpty();
    }

    @Test
    void storageKeyCannotEscapeConfiguredDirectory() {
        FileSystemAttachmentStorage storage =
                new FileSystemAttachmentStorage(tempDir);

        assertThatThrownBy(() -> storage.open("../../segredo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("../../segredo"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
