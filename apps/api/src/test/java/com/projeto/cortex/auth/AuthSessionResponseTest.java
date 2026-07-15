package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthSessionResponseTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String SESSION_ID =
            "20000000-0000-0000-0000-000000000002";
    private static final Instant EXPIRY =
            Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void exposesOnlySafeProfileFieldsAfterOtpVerification() {
        AuthSessionResponse response = AuthSessionResponse.from(
                new AuthenticatedIdentity(
                        COLLABORATOR_ID,
                        "Colaborador Sintético",
                        PapelAcesso.BETA
                ),
                EXPIRY,
                Optional.of(Set.of(
                        "30000000-0000-0000-0000-000000000003"
                ))
        );

        assertThat(response).isEqualTo(new AuthSessionResponse(
                COLLABORATOR_ID,
                "Colaborador Sintético",
                "BETA",
                false,
                java.util.List.of(
                        "30000000-0000-0000-0000-000000000003"
                ),
                EXPIRY
        ));
        assertThat(AuthSessionResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("token", "cpf", "cpfMascarado", "email");
    }

    @Test
    void mapsAnAlreadyResolvedSessionWithoutCredentials() {
        AuthSessionResponse response = AuthSessionResponse.from(
                new ResolvedAuthSession(
                        SESSION_ID,
                        COLLABORATOR_ID,
                        "Colaborador Sintético",
                        PapelAcesso.ALFA,
                        EXPIRY,
                        "a".repeat(64)
                ),
                Optional.empty()
        );

        assertThat(response.papelAcesso()).isEqualTo("ALFA");
        assertThat(response.expiraEm()).isEqualTo(EXPIRY);
        assertThat(response.escopoGlobal()).isTrue();
        assertThat(response.obraIds()).isEmpty();
        assertThat(response.toString()).doesNotContain("a".repeat(64));
    }

    @Test
    void mapsLocalDevAdminOverrideToAnEffectiveAlfaProfile() {
        AuthSessionResponse response = AuthSessionResponse.from(
                new AuthenticatedIdentity(
                        COLLABORATOR_ID,
                        "Colaborador Sintético",
                        PapelAcesso.BETA
                ),
                EXPIRY,
                Optional.empty()
        );

        assertThat(response.papelAcesso()).isEqualTo("ALFA");
        assertThat(response.escopoGlobal()).isTrue();
        assertThat(response.obraIds()).isEmpty();
    }
}
