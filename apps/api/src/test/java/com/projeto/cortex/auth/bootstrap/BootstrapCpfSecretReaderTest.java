package com.projeto.cortex.auth.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapCpfSecretReaderTest {

    private static final String SYNTHETIC_CPF = "111.444.777-35";

    @TempDir
    Path temporaryDirectory;

    private final BootstrapCpfSecretReader reader = new BootstrapCpfSecretReader();

    @Test
    void acceptsExactlyOneCanonicalizableOwnerOnlyLine() throws Exception {
        Path secret = ownerOnlyFile("bootstrap-cpf", SYNTHETIC_CPF + "\n");

        assertThat(reader.read(secret)).isEqualTo("11144477735");
    }

    @Test
    void rejectsMissingUnsafeAndMultilineFilesWithoutEchoingTheirContent() throws Exception {
        Path missing = temporaryDirectory.resolve("missing");
        Path nonOwnerOnly = ownerOnlyFile("non-owner-only", SYNTHETIC_CPF);
        Files.setPosixFilePermissions(
                nonOwnerOnly,
                PosixFilePermissions.fromString("rw-r--r--")
        );
        Path multiline = ownerOnlyFile("multiline", SYNTHETIC_CPF + "\nother");
        Path invalid = ownerOnlyFile("invalid", "invalid-protected-identifier");

        assertGenericFailure(missing, "missing");
        assertGenericFailure(nonOwnerOnly, SYNTHETIC_CPF);
        assertGenericFailure(multiline, "other");
        assertGenericFailure(invalid, "invalid-protected-identifier");
    }

    @Test
    void rejectsSymlinksEvenWhenTheirTargetWouldOtherwiseBeAccepted() throws Exception {
        Path target = ownerOnlyFile("target", SYNTHETIC_CPF);
        Path link = temporaryDirectory.resolve("bootstrap-link");
        Files.createSymbolicLink(link, target);

        assertGenericFailure(link, SYNTHETIC_CPF);
    }

    @Test
    void sourceUsesNoFollowChannelAndStableNofollowAttributes() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/projeto/cortex/auth/bootstrap/BootstrapCpfSecretReader.java"
        ));

        assertThat(source).contains(
                "SeekableByteChannel",
                "StandardOpenOption.READ",
                "LinkOption.NOFOLLOW_LINKS",
                "attributes.fileKey()",
                "before.equals(after)"
        ).doesNotContain("Files.readAllBytes(path)");
    }

    private Path ownerOnlyFile(String name, String value) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, value);
        Files.setPosixFilePermissions(
                path,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        );
        return path;
    }

    private void assertGenericFailure(Path path, String protectedValue) {
        assertThatThrownBy(() -> reader.read(path))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Arquivo secreto de bootstrap inválido.")
                .hasMessageNotContaining(protectedValue);
    }
}
