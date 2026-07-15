package com.projeto.cortex.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

public final class LocalObjectStorage implements ObjectStorage {

    private static final String METADATA_SUFFIX = ".cortex-meta";
    private static final int MAX_KEY_LENGTH = 700;

    private final Path root;

    public LocalObjectStorage(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("A raiz local é obrigatória.");
        }
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            if (Files.isSymbolicLink(this.root)) {
                throw new IllegalArgumentException(
                        "A raiz local não pode ser um link simbólico."
                );
            }
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Não foi possível preparar o armazenamento local.",
                    exception
            );
        }
    }

    @Override
    public String backend() {
        return "LOCAL";
    }

    @Override
    public void put(
            String key,
            InputStream inputStream,
            long length,
            String mediaType
    ) {
        if (inputStream == null || length < 0) {
            throw new IllegalArgumentException("Conteúdo local inválido.");
        }
        String normalizedMediaType = requireMetadataValue(mediaType);
        Path target = resolveSafe(key);
        Path metadata = metadataPath(target);
        Path parent = target.getParent();
        Path tempBody = null;
        Path tempMetadata = null;
        boolean bodyMoved = false;

        try {
            Files.createDirectories(parent);
            requireNoSymlinkBetweenRootAnd(parent);
            tempBody = Files.createTempFile(parent, ".upload-", ".tmp");
            tempMetadata = Files.createTempFile(parent, ".metadata-", ".tmp");

            long copied;
            try (OutputStream output = Files.newOutputStream(
                    tempBody,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                copied = inputStream.transferTo(output);
            }
            if (copied != length) {
                throw new ObjectStorageException(
                        "O tamanho gravado diverge do conteúdo validado."
                );
            }
            Files.writeString(
                    tempMetadata,
                    length + "\n" + normalizedMediaType + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            moveAtomically(tempBody, target);
            bodyMoved = true;
            moveAtomically(tempMetadata, metadata);
        } catch (ObjectStorageException exception) {
            cleanupAfterFailure(tempBody, tempMetadata, target, metadata, bodyMoved);
            throw exception;
        } catch (IOException exception) {
            cleanupAfterFailure(tempBody, tempMetadata, target, metadata, bodyMoved);
            throw new ObjectStorageException(
                    "Não foi possível gravar o objeto local.",
                    exception
            );
        }
    }

    @Override
    public ObjectStorageContent get(String key) {
        Path target = resolveSafe(key);
        Path metadata = metadataPath(target);
        try {
            requireNoSymlinkBetweenRootAnd(target.getParent());
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(
                            metadata,
                            LinkOption.NOFOLLOW_LINKS
                    )) {
                throw new ObjectStorageNotFoundException(
                        "Objeto armazenado não encontrado."
                );
            }
            List<String> lines = Files.readAllLines(
                    metadata,
                    StandardCharsets.UTF_8
            );
            if (lines.size() != 2) {
                throw new ObjectStorageException(
                        "Metadados do objeto local estão inválidos."
                );
            }
            long expectedLength = Long.parseLong(lines.get(0));
            long actualLength = Files.size(target);
            if (expectedLength != actualLength) {
                throw new ObjectStorageException(
                        "Integridade do objeto local não confirmada."
                );
            }
            return new ObjectStorageContent(
                    Files.newInputStream(target, StandardOpenOption.READ),
                    actualLength,
                    requireMetadataValue(lines.get(1))
            );
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException | NumberFormatException exception) {
            throw new ObjectStorageException(
                    "Não foi possível abrir o objeto local.",
                    exception
            );
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolveSafe(key);
        try {
            requireNoSymlinkBetweenRootAnd(target.getParent());
            Files.deleteIfExists(metadataPath(target));
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Não foi possível remover o objeto local.",
                    exception
            );
        }
    }

    private Path resolveSafe(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || key.startsWith("/") || key.startsWith("\\")
                || key.contains("\\") || key.contains("//")) {
            throw new IllegalArgumentException("Chave de objeto inválida.");
        }
        String[] segments = key.split("/");
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment)
                    || "..".equals(segment)
                    || !segment.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Chave de objeto inválida.");
            }
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Chave de objeto inválida.");
        }
        return resolved;
    }

    private Path metadataPath(Path target) {
        return target.resolveSibling(
                target.getFileName().toString() + METADATA_SUFFIX
        );
    }

    private void requireNoSymlinkBetweenRootAnd(Path path) throws IOException {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        Path cursor = root;
        for (Path segment : relative) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(cursor)) {
                throw new ObjectStorageException(
                        "Links simbólicos não são aceitos no armazenamento local."
                );
            }
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ObjectStorageException(
                    "O volume local não oferece escrita atômica.",
                    exception
            );
        }
    }

    private void cleanupAfterFailure(
            Path tempBody,
            Path tempMetadata,
            Path target,
            Path metadata,
            boolean bodyMoved
    ) {
        deleteQuietly(tempBody);
        deleteQuietly(tempMetadata);
        if (bodyMoved) {
            deleteQuietly(target);
            deleteQuietly(metadata);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The primary storage failure is preserved for the caller.
        }
    }

    private static String requireMetadataValue(String value) {
        if (value == null || value.isBlank() || value.contains("\n")
                || value.contains("\r")) {
            throw new IllegalArgumentException("Media type inválido.");
        }
        return value.strip();
    }
}
