package com.projeto.cortex.mensagens;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FileSystemAttachmentStorage implements AttachmentStorage {

    private static final int HEADER_LIMIT = 512;
    private static final Pattern STORAGE_KEY = Pattern.compile(
            "[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}"
    );

    private final Path root;
    private final Path stagingDirectory;
    private final Path filesDirectory;

    @Autowired
    public FileSystemAttachmentStorage(MessageAttachmentProperties properties) {
        this(Path.of(properties.getStoragePath()));
    }

    FileSystemAttachmentStorage(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            this.stagingDirectory = this.root.resolve("staging");
            this.filesDirectory = this.root.resolve("files");
            Files.createDirectories(stagingDirectory);
            Files.createDirectories(filesDirectory);
            restrictDirectory(this.root);
            restrictDirectory(stagingDirectory);
            restrictDirectory(filesDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível preparar o armazenamento protegido de anexos.",
                    exception
            );
        }
    }

    @Override
    public StagedAttachment stage(InputStream content, long maxBytes)
            throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("Conteúdo do anexo é obrigatório.");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Limite do anexo deve ser positivo.");
        }

        Path temporary = Files.createTempFile(stagingDirectory, "upload-", ".tmp");
        restrictFile(temporary);
        MessageDigest digest = sha256();
        ByteArrayOutputStream header = new ByteArrayOutputStream(HEADER_LIMIT);
        long written = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = content;
             OutputStream output = Files.newOutputStream(
                     temporary,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    throw new AttachmentStorageLimitException(
                            "O arquivo excede o limite permitido."
                    );
                }
                digest.update(buffer, 0, read);
                int remainingHeader = HEADER_LIMIT - header.size();
                if (remainingHeader > 0) {
                    header.write(buffer, 0, Math.min(read, remainingHeader));
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }

        return new FileStagedAttachment(
                temporary,
                written,
                HexFormat.of().formatHex(digest.digest()),
                header.toByteArray()
        );
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        Path path = resolveStorageKey(storageKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException("Conteúdo do anexo não encontrado.");
        }
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolveStorageKey(storageKey));
    }

    private Path resolveStorageKey(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Chave de armazenamento inválida.");
        }
        Path resolved = filesDirectory.resolve(storageKey).normalize();
        if (!resolved.startsWith(filesDirectory)) {
            throw new IllegalArgumentException("Chave de armazenamento inválida.");
        }
        return resolved;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível.", exception);
        }
    }

    private final class FileStagedAttachment implements StagedAttachment {
        private final Path temporary;
        private final long sizeBytes;
        private final String sha256;
        private final byte[] header;
        private boolean committed;
        private boolean closed;

        private FileStagedAttachment(
                Path temporary,
                long sizeBytes,
                String sha256,
                byte[] header
        ) {
            this.temporary = temporary;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.header = header.clone();
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public String sha256() {
            return sha256;
        }

        @Override
        public byte[] header() {
            return header.clone();
        }

        @Override
        public InputStream open() throws IOException {
            requireOpen();
            return Files.newInputStream(temporary, StandardOpenOption.READ);
        }

        @Override
        public String commit() throws IOException {
            requireOpen();
            String id = UUID.randomUUID().toString();
            String storageKey = id.substring(0, 2) + "/" + id;
            Path destination = resolveStorageKey(storageKey);
            Files.createDirectories(destination.getParent());
            restrictDirectory(destination.getParent());
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            committed = true;
            restrictFile(destination);
            return storageKey;
        }

        @Override
        public void close() throws IOException {
            if (!closed && !committed) {
                Files.deleteIfExists(temporary);
            }
            closed = true;
        }

        private void requireOpen() {
            if (closed || committed) {
                throw new IllegalStateException("Staging do anexo já foi encerrado.");
            }
        }
    }

    private void restrictDirectory(Path directory) {
        setPosixPermissions(directory, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
    }

    private void restrictFile(Path file) {
        setPosixPermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
    }

    private void setPosixPermissions(
            Path path,
            Set<PosixFilePermission> permissions
    ) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Sistemas sem POSIX continuam protegidos pela raiz privada e
            // pela ausência de qualquer rota pública de arquivos estáticos.
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível restringir as permissões do armazenamento.",
                    exception
            );
        }
    }
}
