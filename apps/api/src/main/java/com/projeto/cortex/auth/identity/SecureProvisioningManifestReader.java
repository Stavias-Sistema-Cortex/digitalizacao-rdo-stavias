package com.projeto.cortex.auth.identity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

/** Reads one stable, owner-only provisioning file without following links. */
public final class SecureProvisioningManifestReader {

    private static final int MAX_BYTES = 1_048_576;
    private static final Set<PosixFilePermission> ALLOWED_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    public byte[] read(Path path) {
        try {
            BasicFileAttributes before = attributes(path);
            validateFile(path, before);
            byte[] bytes = readBounded(path);
            BasicFileAttributes after = attributes(path);
            validateFile(path, after);
            if (!Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size()
                    || before.lastModifiedTime().compareTo(
                            after.lastModifiedTime()
                    ) != 0
                    || bytes.length != after.size()) {
                throw invalidManifest();
            }
            return bytes;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (UnsupportedOperationException exception) {
            throw new IllegalStateException(
                    "O arquivo secreto de provisionamento exige permissões POSIX."
            );
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "Não foi possível ler o arquivo secreto de provisionamento."
            );
        }
    }

    private BasicFileAttributes attributes(Path path) throws IOException {
        if (path == null) {
            throw new IllegalStateException(
                    "Arquivo secreto de provisionamento inválido."
            );
        }
        return Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private void validateFile(
            Path path,
            BasicFileAttributes attributes
    ) throws IOException {
        if (!attributes.isRegularFile() || attributes.fileKey() == null) {
            throw new IllegalStateException(
                    "Arquivo secreto de provisionamento inválido."
            );
        }
        Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || !ALLOWED_PERMISSIONS.containsAll(permissions)) {
            throw new IllegalStateException(
                    "O arquivo secreto de provisionamento deve usar 0600 ou permissões mais restritas."
            );
        }
    }

    private byte[] readBounded(Path path) throws IOException {
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        );
        try (SeekableByteChannel channel = Files.newByteChannel(
                path,
                options
        ); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int total = 0;
            int read;
            while ((read = channel.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_BYTES) {
                    throw invalidManifest();
                }
                output.write(buffer.array(), 0, read);
                buffer.clear();
            }
            if (total == 0) {
                throw invalidManifest();
            }
            return output.toByteArray();
        }
    }

    private IllegalStateException invalidManifest() {
        return new IllegalStateException(
                "Manifesto de provisionamento inválido."
        );
    }
}
