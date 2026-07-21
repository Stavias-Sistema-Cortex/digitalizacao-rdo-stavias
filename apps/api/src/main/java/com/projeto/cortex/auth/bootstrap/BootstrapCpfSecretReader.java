package com.projeto.cortex.auth.bootstrap;

import com.projeto.cortex.auth.identity.CpfNormalizer;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reads the protected bootstrap identifier from a single owner-only file.
 * Neither this reader nor its failures expose the protected value.
 */
@Component
@Profile("postgresql-bootstrap")
public final class BootstrapCpfSecretReader {

    private static final int MAX_SECRET_BYTES = 64;
    private static final Set<PosixFilePermission> OWNER_ONLY_MODE =
            PosixFilePermissions.fromString("rw-------");
    private static final String GENERIC_FAILURE =
            "Arquivo secreto de bootstrap inválido.";

    public String read(Path path) {
        byte[] raw = null;
        try {
            FileSnapshot before = safeSnapshot(path);
            raw = readBoundedNoFollow(path, before.size());
            FileSnapshot after = safeSnapshot(path);
            if (!before.equals(after) || raw.length != before.size()) {
                throw invalid();
            }

            String line = decodeSingleLine(raw);
            return CpfNormalizer.requireValid(line);
        } catch (BootstrapSecretException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw invalid();
        } finally {
            if (raw != null) {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    private static FileSnapshot safeSnapshot(Path path) throws Exception {
        if (path == null || Files.isSymbolicLink(path)) {
            throw invalid();
        }
        PosixFileAttributes attributes = Files.readAttributes(
                path,
                PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile()
                || !attributes.permissions().equals(OWNER_ONLY_MODE)
                || attributes.size() < 1
                || attributes.size() > MAX_SECRET_BYTES) {
            throw invalid();
        }
        return new FileSnapshot(
                attributes.fileKey(),
                attributes.size(),
                attributes.lastModifiedTime().toMillis()
        );
    }

    private static byte[] readBoundedNoFollow(Path path, long expectedSize)
            throws Exception {
        byte[] bytes = new byte[(int) expectedSize];
        int offset = 0;
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        );
        try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
            while (offset < bytes.length) {
                int read = channel.read(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != bytes.length || channel.read(ByteBuffer.allocate(1)) != -1) {
                Arrays.fill(bytes, (byte) 0);
                throw invalid();
            }
            return bytes;
        } catch (Exception exception) {
            Arrays.fill(bytes, (byte) 0);
            throw exception;
        }
    }

    private static String decodeSingleLine(byte[] raw) {
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid();
        }

        if (decoded.indexOf('\r') >= 0) {
            throw invalid();
        }
        int terminalNewline = decoded.indexOf('\n');
        if (terminalNewline >= 0 && terminalNewline != decoded.length() - 1) {
            throw invalid();
        }
        if (terminalNewline >= 0 && decoded.lastIndexOf('\n') != terminalNewline) {
            throw invalid();
        }
        String line = terminalNewline >= 0
                ? decoded.substring(0, decoded.length() - 1)
                : decoded;
        if (line.isEmpty()) {
            throw invalid();
        }
        return line;
    }

    private static BootstrapSecretException invalid() {
        return new BootstrapSecretException();
    }

    private record FileSnapshot(Object fileKey, long size, long modifiedAtMillis) {
    }

    private static final class BootstrapSecretException extends IllegalStateException {

        private BootstrapSecretException() {
            super(GENERIC_FAILURE);
        }
    }
}
