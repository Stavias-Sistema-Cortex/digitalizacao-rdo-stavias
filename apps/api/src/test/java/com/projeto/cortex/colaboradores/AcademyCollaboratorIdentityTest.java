package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcademyCollaboratorIdentityTest {

    @Test
    void derivesTheExistingAcademyIdentityDeterministically() {
        int sourceUserId = 907_001;

        String first = AcademyCollaboratorIdentity.fromAcademyUserId(sourceUserId);
        String second = AcademyCollaboratorIdentity.fromAcademyUserId(sourceUserId);
        String different = AcademyCollaboratorIdentity.fromAcademyUserId(
                sourceUserId + 1
        );

        assertThat(first).isEqualTo(expectedExistingImportIdentity(sourceUserId));
        assertThat(second).isEqualTo(first);
        assertThat(different).isNotEqualTo(first);
        assertThat(first).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    @Test
    void fullAcademyImportDelegatesIdentityGenerationToTheSharedHelper()
            throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java"
        ));

        assertThat(source).contains(
                "AcademyCollaboratorIdentity.fromAcademyUserId(idUsuario)"
        );
        assertThat(source).doesNotContain("stableColaboradorId(");
    }

    private String expectedExistingImportIdentity(int sourceUserId) {
        return UUID.nameUUIDFromBytes(
                ("dbstavias_acad:usuarios:" + sourceUserId)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
