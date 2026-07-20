package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SecureProvisioningManifestReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsOwnerOnlyFileThroughBoundedNoFollowPath() throws Exception {
        Path manifest = ownerOnly("manifest.json", "conteudo-sintetico");

        byte[] bytes = new SecureProvisioningManifestReader().read(manifest);

        assertThat(new String(bytes)).isEqualTo("conteudo-sintetico");
    }

    @Test
    void refusesSymbolicLinkWithoutDisclosingTargetContents() throws Exception {
        Path target = ownerOnly("target.json", "segredo-sintetico");
        Path link = tempDir.resolve("manifest-link.json");
        Files.createSymbolicLink(link, target);

        assertThatThrownBy(() -> new SecureProvisioningManifestReader().read(link))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("segredo-sintetico");
    }

    @Test
    void refusesMoreThanOneMebibyteWithoutReturningPartialContents()
            throws Exception {
        Path manifest = tempDir.resolve("oversized.json");
        Files.write(manifest, new byte[1_048_577]);
        Files.setPosixFilePermissions(manifest, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));

        assertThatThrownBy(() -> new SecureProvisioningManifestReader()
                .read(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Manifesto de provisionamento inválido.");
    }

    private Path ownerOnly(String name, String contents) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, contents);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
        return path;
    }
}
