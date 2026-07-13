package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacCpfLookupDigestServiceTest {

    private static final String SYNTHETIC_CPF = "111.444.777-35";
    private static final String CURRENT_SECRET =
            "test-only-current-hmac-secret-material-0001";
    private static final String PREVIOUS_SECRET =
            "test-only-previous-hmac-secret-material-0001";

    @TempDir
    Path tempDir;

    @Test
    void readsMountedKeyAndProducesKeyedNonShaDigest() throws Exception {
        Path keyFile = tempDir.resolve("cpf-hmac-key");
        Files.writeString(keyFile, CURRENT_SECRET);

        HmacCpfLookupDigestService service = new HmacCpfLookupDigestService(
                "k2026-07",
                keyFile,
                null,
                null
        );

        CpfLookupDigest first = service.current(SYNTHETIC_CPF);
        CpfLookupDigest second = service.current("11144477735");

        assertThat(first).isEqualTo(second);
        assertThat(first.keyId()).isEqualTo("k2026-07");
        assertThat(first.value()).hasSize(64);
        assertThat(first.value())
                .isNotEqualTo(CpfHasher.hashDeDigitos("11144477735"));
    }

    @Test
    void returnsCurrentThenPreviousCandidateDuringRotation() throws Exception {
        Path currentFile = tempDir.resolve("current-key");
        Path previousFile = tempDir.resolve("previous-key");
        Files.writeString(currentFile, CURRENT_SECRET);
        Files.writeString(previousFile, PREVIOUS_SECRET);

        HmacCpfLookupDigestService service = new HmacCpfLookupDigestService(
                "k2026-07",
                currentFile,
                null,
                new HmacCpfLookupDigestService.PreviousKey(
                        "k2026-06",
                        previousFile,
                        null
                )
        );

        List<CpfLookupDigest> candidates = service.candidates(SYNTHETIC_CPF);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).isEqualTo(service.current(SYNTHETIC_CPF));
        assertThat(candidates.get(1).keyId()).isEqualTo("k2026-06");
        assertThat(candidates.get(1).value())
                .isNotEqualTo(candidates.get(0).value());
    }

    @Test
    void rejectsInvalidCpfWithoutEchoingIt() {
        HmacCpfLookupDigestService service = new HmacCpfLookupDigestService(
                "k2026-07",
                null,
                CURRENT_SECRET,
                null
        );

        assertThatThrownBy(() -> service.current("111.444.777-34"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identificador inválido.")
                .hasMessageNotContaining("111");
        assertThatThrownBy(() -> service.current("000.000.000-00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identificador inválido.");
        assertThatThrownBy(() -> service.current(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identificador inválido.");
    }

    @Test
    void refusesMissingOrShortCurrentKey() {
        assertThatThrownBy(() -> new HmacCpfLookupDigestService(
                "k2026-07",
                tempDir.resolve("missing"),
                null,
                null
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new HmacCpfLookupDigestService(
                "k2026-07",
                null,
                "test-only-short",
                null
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAmbiguousRotationIdentifiersAndMaterial() {
        assertThatThrownBy(() -> new HmacCpfLookupDigestService(
                "same-key-id",
                null,
                CURRENT_SECRET,
                new HmacCpfLookupDigestService.PreviousKey(
                        "same-key-id",
                        null,
                        PREVIOUS_SECRET
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distintos");

        assertThatThrownBy(() -> new HmacCpfLookupDigestService(
                "current-key-id",
                null,
                CURRENT_SECRET,
                new HmacCpfLookupDigestService.PreviousKey(
                        "previous-key-id",
                        null,
                        CURRENT_SECRET
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("materiais distintos");
    }
}
