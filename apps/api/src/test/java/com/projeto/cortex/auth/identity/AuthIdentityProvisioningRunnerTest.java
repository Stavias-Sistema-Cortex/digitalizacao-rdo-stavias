package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@EnabledOnOs({OS.LINUX, OS.MAC})
class AuthIdentityProvisioningRunnerTest {

    private static final String SYNTHETIC_CPF = "11144477735";
    private static final String SYNTHETIC_EMAIL =
            "alfa@example.invalid";

    @TempDir
    Path tempDir;

    @Mock
    AuthIdentityRepository identityRepository;

    @Mock
    ProvisioningReceiptRepository receiptRepository;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void processesOwnerOnlyMountedManifestOnceAsPending() throws Exception {
        Path manifest = mountedManifest("""
                {"version":1,"identities":[
                  {"cpf":"111.444.777-35","email":"alfa@example.invalid"}
                ]}
                """);
        when(receiptRepository.claim(anyString(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(true, false);
        AuthIdentityProvisioningRunner runner = runner(manifest);

        runner.run();
        runner.run();

        verify(identityRepository, times(1)).upsertProvisionedIdentity(
                SYNTHETIC_CPF,
                SYNTHETIC_EMAIL,
                AuthIdentityRepository.MANUAL_PENDING_SOURCE
        );
        verify(receiptRepository, times(2)).claim(anyString(),
                org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void refusesManifestReadableByGroupBeforeParsing() throws Exception {
        Path manifest = mountedManifest("""
                {"version":1,"identities":[
                  {"cpf":"11144477735","email":"alfa@example.invalid"}
                ]}
                """);
        Files.setPosixFilePermissions(manifest, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        ));

        assertThatThrownBy(() -> runner(manifest).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0600")
                .hasMessageNotContaining(SYNTHETIC_CPF)
                .hasMessageNotContaining(SYNTHETIC_EMAIL);

        verifyNoInteractions(identityRepository, receiptRepository);
    }

    @Test
    void refusesWebLaunchWithoutReadingManifest() throws Exception {
        Path manifest = mountedManifest("not-json");

        AuthIdentityProvisioningRunner runner = new AuthIdentityProvisioningRunner(
                manifest,
                new ObjectMapper(),
                identityRepository,
                receiptRepository,
                false
        );

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não web")
                .hasMessageNotContaining("not-json");
        verifyNoInteractions(identityRepository, receiptRepository);
    }

    @Test
    void validatesEveryIdentityBeforeClaimingReceipt() throws Exception {
        Path manifest = mountedManifest("""
                {"version":1,"identities":[
                  {"cpf":"11144477735","email":"alfa@example.invalid"},
                  {"cpf":"invalid","email":"alfa@example.invalid"}
                ]}
                """);

        assertThatThrownBy(() -> runner(manifest).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Manifesto de provisionamento inválido.")
                .hasMessageNotContaining(SYNTHETIC_CPF)
                .hasMessageNotContaining(SYNTHETIC_EMAIL);

        verifyNoInteractions(identityRepository, receiptRepository);
    }

    @Test
    void sanitizesUnknownOrInactiveIdentityFailureAfterClaim() throws Exception {
        Path manifest = mountedManifest("""
                {"version":1,"identities":[
                  {"cpf":"11144477735","email":"alfa@example.invalid"}
                ]}
                """);
        when(receiptRepository.claim(anyString(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "Identidade indisponível para provisionamento."
                ))
                .when(identityRepository)
                .upsertProvisionedIdentity(
                        SYNTHETIC_CPF,
                        SYNTHETIC_EMAIL,
                        AuthIdentityRepository.MANUAL_PENDING_SOURCE
                );

        assertThatThrownBy(() -> runner(manifest).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade indisponível para provisionamento.")
                .hasMessageNotContaining(SYNTHETIC_CPF)
                .hasMessageNotContaining(SYNTHETIC_EMAIL);

        verify(receiptRepository).claim(anyString(),
                org.mockito.ArgumentMatchers.eq(1));
        verify(identityRepository).upsertProvisionedIdentity(
                SYNTHETIC_CPF,
                SYNTHETIC_EMAIL,
                AuthIdentityRepository.MANUAL_PENDING_SOURCE
        );
        verify(identityRepository, never()).upsertAcademyIdentity(
                anyString(), anyString(), anyString()
        );
    }

    private AuthIdentityProvisioningRunner runner(Path manifest) {
        return new AuthIdentityProvisioningRunner(
                manifest,
                new ObjectMapper(),
                identityRepository,
                receiptRepository,
                true
        );
    }

    private Path mountedManifest(String contents) throws Exception {
        Path manifest = tempDir.resolve("provisioning-secret.json");
        Files.writeString(manifest, contents);
        Files.setPosixFilePermissions(manifest, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
        return manifest;
    }
}
